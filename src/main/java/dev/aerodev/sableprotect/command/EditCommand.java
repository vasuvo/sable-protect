package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.audit.AuditLog;
import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.aerodev.sableprotect.permissions.Permissions;
import dev.aerodev.sableprotect.util.Lang;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class EditCommand {

    private EditCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register(final ClaimRegistry registry) {
        return Commands.literal("edit")
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (Permissions.has(player, Permissions.Nodes.EDIT_OVERRIDE, 2)) {
                                for (final String name : registry.getAllNames()) {
                                    builder.suggest(name);
                                }
                            } else {
                                for (final UUID id : registry.getOwnedBy(player.getUUID())) {
                                    final String name = registry.getNameByUuid(id);
                                    if (name != null) builder.suggest(name);
                                }
                            }
                            return builder.buildFuture();
                        })
                        // /sp edit <name> blocks protected|unprotected
                        .then(Commands.literal("blocks")
                                .then(Commands.literal("protected")
                                        .executes(ctx -> executeToggle(ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "name"),
                                                "blocks", true, registry)))
                                .then(Commands.literal("unprotected")
                                        .executes(ctx -> executeToggle(ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "name"),
                                                "blocks", false, registry))))
                        // /sp edit <name> interactions protected|unprotected
                        .then(Commands.literal("interactions")
                                .then(Commands.literal("protected")
                                        .executes(ctx -> executeToggle(ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "name"),
                                                "interactions", true, registry)))
                                .then(Commands.literal("unprotected")
                                        .executes(ctx -> executeToggle(ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "name"),
                                                "interactions", false, registry))))
                        // /sp edit <name> inventories protected|unprotected
                        .then(Commands.literal("inventories")
                                .then(Commands.literal("protected")
                                        .executes(ctx -> executeToggle(ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "name"),
                                                "inventories", true, registry)))
                                .then(Commands.literal("unprotected")
                                        .executes(ctx -> executeToggle(ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "name"),
                                                "inventories", false, registry))))
                        // /sp edit <name> rename <newname>
                        .then(Commands.literal("rename")
                                .then(Commands.argument("newname", StringArgumentType.string())
                                        .executes(ctx -> executeRename(ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "name"),
                                                StringArgumentType.getString(ctx, "newname"),
                                                registry))))
                        // /sp edit <name> changeowner <player>
                        .then(Commands.literal("changeowner")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> executeChangeOwner(ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "name"),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                registry))))
                        // /sp edit <name> addmember <player>
                        .then(Commands.literal("addmember")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> executeAddMember(ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "name"),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                registry))))
                        // /sp edit <name> removemember <player>
                        .then(Commands.literal("removemember")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> executeRemoveMember(ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "name"),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                registry)))));
    }

    private static int executeToggle(final ServerPlayer player, final String name,
                                     final String category, final boolean protect,
                                     final ClaimRegistry registry) {
        final ResolvedClaim resolved = resolve(player, name, registry);
        if (resolved == null) return 0;

        // Invariant: blocks unprotected → interactions and inventories must also be
        // unprotected. Toggling cascades in both directions to maintain it:
        //   * unprotecting blocks      → also unprotect interactions + inventories
        //   * protecting interactions  → also protect blocks (if currently unprotected)
        //   * protecting inventories   → also protect blocks (if currently unprotected)
        switch (category) {
            case "blocks" -> {
                resolved.data.setBlocksProtected(protect);
                if (!protect) {
                    resolved.data.setInteractionsProtected(false);
                    resolved.data.setInventoriesProtected(false);
                }
            }
            case "interactions" -> {
                resolved.data.setInteractionsProtected(protect);
                if (protect) resolved.data.setBlocksProtected(true);
            }
            case "inventories" -> {
                resolved.data.setInventoriesProtected(protect);
                if (protect) resolved.data.setBlocksProtected(true);
            }
        }

        persist(registry, resolved);

        InfoCommand.sendInfoWindow(player, resolved.subLevelId, resolved.subLevel, resolved.data);
        return 1;
    }

    private static int executeRename(final ServerPlayer player, final String oldName,
                                     final String newName, final ClaimRegistry registry) {
        final ResolvedClaim resolved = resolve(player, oldName, registry);
        if (resolved == null) return 0;

        // Check new name uniqueness
        final UUID existingHolder = registry.getSubLevelByName(newName);
        if (existingHolder != null && !existingHolder.equals(resolved.subLevelId)) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.claim.name_taken", newName), false);
            return 0;
        }

        resolved.data.setName(newName);
        persist(registry, resolved);

        player.displayClientMessage(
                Lang.tr("sableprotect.edit.renamed", oldName, newName), false);
        InfoCommand.sendInfoWindow(player, resolved.subLevelId, resolved.subLevel, resolved.data);
        return 1;
    }

    private static int executeChangeOwner(final ServerPlayer player, final String name,
                                          final ServerPlayer newOwner, final ClaimRegistry registry) {
        final ResolvedClaim resolved = resolve(player, name, registry);
        if (resolved == null) return 0;

        final java.util.UUID previousOwner = resolved.data.getOwner();
        resolved.data.setOwner(newOwner.getUUID());
        // Remove new owner from members if they were a member
        resolved.data.getMembers().remove(newOwner.getUUID());
        persist(registry, resolved);

        AuditLog.logTransfer(player.getServer(), name, resolved.subLevelId,
                previousOwner, newOwner.getUUID(), "changeowner");

        player.displayClientMessage(
                Lang.tr("sableprotect.edit.owner_changed", name,
                        newOwner.getGameProfile().getName()), false);
        InfoCommand.sendInfoWindow(player, resolved.subLevelId, resolved.subLevel, resolved.data);
        return 1;
    }

    private static int executeAddMember(final ServerPlayer player, final String name,
                                        final ServerPlayer member, final ClaimRegistry registry) {
        final ResolvedClaim resolved = resolve(player, name, registry);
        if (resolved == null) return 0;

        if (member.getUUID().equals(resolved.data.getOwner())) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.edit.already_owner"), false);
            return 0;
        }

        if (!resolved.data.getMembers().add(member.getUUID())) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.edit.already_member",
                            member.getGameProfile().getName()), false);
            return 0;
        }

        persist(registry, resolved);

        player.displayClientMessage(
                Lang.tr("sableprotect.edit.member_added",
                        member.getGameProfile().getName(), name), false);
        InfoCommand.sendInfoWindow(player, resolved.subLevelId, resolved.subLevel, resolved.data);
        return 1;
    }

    private static int executeRemoveMember(final ServerPlayer player, final String name,
                                           final ServerPlayer member, final ClaimRegistry registry) {
        final ResolvedClaim resolved = resolve(player, name, registry);
        if (resolved == null) return 0;

        if (!resolved.data.getMembers().remove(member.getUUID())) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.edit.not_a_member",
                            member.getGameProfile().getName()), false);
            return 0;
        }

        persist(registry, resolved);

        player.displayClientMessage(
                Lang.tr("sableprotect.edit.member_removed",
                        member.getGameProfile().getName(), name), false);
        InfoCommand.sendInfoWindow(player, resolved.subLevelId, resolved.subLevel, resolved.data);
        return 1;
    }

    /**
     * Resolves the claim by name, validates it exists and the player is the owner.
     * The sub-level is looked up but may be null (claim's sub-level is unloaded);
     * edits proceed against the persistent storage regardless.
     * Returns null and sends an error message if any check fails.
     */
    private static ResolvedClaim resolve(final ServerPlayer player, final String name,
                                         final ClaimRegistry registry) {
        final UUID subLevelId = registry.getSubLevelByName(name);
        if (subLevelId == null) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.not_found", name), false);
            return null;
        }

        final ClaimData data = registry.getClaim(subLevelId);
        if (data == null) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.not_found", name), false);
            return null;
        }

        if (data.getRole(player.getUUID()) != ClaimRole.OWNER
                && !Permissions.has(player, Permissions.Nodes.EDIT_OVERRIDE, 2)) {
            player.displayClientMessage(
                    Lang.tr("sableprotect.not_owner"), false);
            return null;
        }

        // May be null if the sub-level is currently unloaded — that's fine for edits.
        final ServerSubLevel subLevel = UnclaimCommand.findSubLevel(player, subLevelId);
        return new ResolvedClaim(subLevel, data, subLevelId);
    }

    /** Persist a claim mutation: re-index storage, mirror to userDataTag if sub-level loaded. */
    static void persist(final ClaimRegistry registry, final ResolvedClaim resolved) {
        registry.touchClaim(resolved.subLevelId);
        if (resolved.subLevel != null) {
            ClaimData.write(resolved.subLevel, resolved.data);
        }
    }

    private record ResolvedClaim(@org.jetbrains.annotations.Nullable ServerSubLevel subLevel,
                                  ClaimData data, UUID subLevelId) {}
}
