package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.claim.ClaimRole;
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
                            for (final UUID id : registry.getOwnedBy(player.getUUID())) {
                                final String name = registry.getNameByUuid(id);
                                if (name != null) builder.suggest(name);
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

        switch (category) {
            case "blocks" -> resolved.data.setBlocksProtected(protect);
            case "interactions" -> resolved.data.setInteractionsProtected(protect);
            case "inventories" -> resolved.data.setInventoriesProtected(protect);
        }

        ClaimData.write(resolved.subLevel, resolved.data);
        registry.update(resolved.subLevel.getUniqueId(), resolved.data);

        InfoCommand.sendInfoWindow(player, resolved.subLevel.getUniqueId(), resolved.data);
        return 1;
    }

    private static int executeRename(final ServerPlayer player, final String oldName,
                                     final String newName, final ClaimRegistry registry) {
        final ResolvedClaim resolved = resolve(player, oldName, registry);
        if (resolved == null) return 0;

        // Check new name uniqueness
        final UUID existingHolder = registry.getSubLevelByName(newName);
        if (existingHolder != null && !existingHolder.equals(resolved.subLevel.getUniqueId())) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.claim.name_taken", newName), false);
            return 0;
        }

        resolved.data.setName(newName);
        ClaimData.write(resolved.subLevel, resolved.data);
        registry.update(resolved.subLevel.getUniqueId(), resolved.data);

        player.displayClientMessage(
                Component.translatable("sableprotect.edit.renamed", oldName, newName), false);
        InfoCommand.sendInfoWindow(player, resolved.subLevel.getUniqueId(), resolved.data);
        return 1;
    }

    private static int executeChangeOwner(final ServerPlayer player, final String name,
                                          final ServerPlayer newOwner, final ClaimRegistry registry) {
        final ResolvedClaim resolved = resolve(player, name, registry);
        if (resolved == null) return 0;

        resolved.data.setOwner(newOwner.getUUID());
        // Remove new owner from members if they were a member
        resolved.data.getMembers().remove(newOwner.getUUID());
        ClaimData.write(resolved.subLevel, resolved.data);
        registry.update(resolved.subLevel.getUniqueId(), resolved.data);

        player.displayClientMessage(
                Component.translatable("sableprotect.edit.owner_changed", name,
                        newOwner.getGameProfile().getName()), false);
        InfoCommand.sendInfoWindow(player, resolved.subLevel.getUniqueId(), resolved.data);
        return 1;
    }

    private static int executeAddMember(final ServerPlayer player, final String name,
                                        final ServerPlayer member, final ClaimRegistry registry) {
        final ResolvedClaim resolved = resolve(player, name, registry);
        if (resolved == null) return 0;

        if (member.getUUID().equals(resolved.data.getOwner())) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.edit.already_owner"), false);
            return 0;
        }

        if (!resolved.data.getMembers().add(member.getUUID())) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.edit.already_member",
                            member.getGameProfile().getName()), false);
            return 0;
        }

        ClaimData.write(resolved.subLevel, resolved.data);
        registry.update(resolved.subLevel.getUniqueId(), resolved.data);

        player.displayClientMessage(
                Component.translatable("sableprotect.edit.member_added",
                        member.getGameProfile().getName(), name), false);
        InfoCommand.sendInfoWindow(player, resolved.subLevel.getUniqueId(), resolved.data);
        return 1;
    }

    private static int executeRemoveMember(final ServerPlayer player, final String name,
                                           final ServerPlayer member, final ClaimRegistry registry) {
        final ResolvedClaim resolved = resolve(player, name, registry);
        if (resolved == null) return 0;

        if (!resolved.data.getMembers().remove(member.getUUID())) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.edit.not_a_member",
                            member.getGameProfile().getName()), false);
            return 0;
        }

        ClaimData.write(resolved.subLevel, resolved.data);
        registry.update(resolved.subLevel.getUniqueId(), resolved.data);

        player.displayClientMessage(
                Component.translatable("sableprotect.edit.member_removed",
                        member.getGameProfile().getName(), name), false);
        InfoCommand.sendInfoWindow(player, resolved.subLevel.getUniqueId(), resolved.data);
        return 1;
    }

    /**
     * Resolves the claim by name, validates it exists, is loaded, and the player is the owner.
     * Returns null and sends an error message if any check fails.
     */
    private static ResolvedClaim resolve(final ServerPlayer player, final String name,
                                         final ClaimRegistry registry) {
        final UUID subLevelId = registry.getSubLevelByName(name);
        if (subLevelId == null) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.not_found", name), false);
            return null;
        }

        final ServerSubLevel subLevel = UnclaimCommand.findSubLevel(player, subLevelId);
        if (subLevel == null) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.not_loaded", name), false);
            return null;
        }

        final ClaimData data = ClaimData.read(subLevel);
        if (data == null) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.not_found", name), false);
            return null;
        }

        if (data.getRole(player.getUUID()) != ClaimRole.OWNER) {
            player.displayClientMessage(
                    Component.translatable("sableprotect.not_owner"), false);
            return null;
        }

        return new ResolvedClaim(subLevel, data);
    }

    private record ResolvedClaim(ServerSubLevel subLevel, ClaimData data) {}
}
