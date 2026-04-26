package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.permissions.Permissions;
import dev.aerodev.sableprotect.util.Lang;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class ClaimUuidCommand {

    private static final UUID SERVER_UUID = new UUID(0L, 0L);

    private ClaimUuidCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register(final ClaimRegistry registry) {
        return Commands.literal("claimuuid")
                .requires(src -> Permissions.has(src, Permissions.Nodes.COMMAND_CLAIMUUID, 2))
                .then(Commands.argument("uuid", StringArgumentType.string())
                        .then(Commands.argument("name", StringArgumentType.string())
                                // /sp claimuuid <uuid> <name> — owner defaults to sender
                                .executes(ctx -> {
                                    final ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    final String uuidStr = StringArgumentType.getString(ctx, "uuid");
                                    final String name = StringArgumentType.getString(ctx, "name");
                                    return execute(player, uuidStr, name, player.getUUID(),
                                            player.getGameProfile().getName(), registry);
                                })
                                // /sp claimuuid <uuid> <name> <player> — online player
                                .then(Commands.argument("owner", EntityArgument.player())
                                        .executes(ctx -> {
                                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            final String uuidStr = StringArgumentType.getString(ctx, "uuid");
                                            final String name = StringArgumentType.getString(ctx, "name");
                                            final ServerPlayer owner = EntityArgument.getPlayer(ctx, "owner");
                                            return execute(player, uuidStr, name, owner.getUUID(),
                                                    owner.getGameProfile().getName(), registry);
                                        }))
                                // /sp claimuuid <uuid> <name> owneruuid [owner-uuid] — raw UUID, defaults to all-zeroes
                                .then(Commands.literal("owneruuid")
                                        // /sp claimuuid <uuid> <name> owneruuid — all-zeroes owner
                                        .executes(ctx -> {
                                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            final String uuidStr = StringArgumentType.getString(ctx, "uuid");
                                            final String name = StringArgumentType.getString(ctx, "name");
                                            return execute(player, uuidStr, name, SERVER_UUID,
                                                    "server (00000000...)", registry);
                                        })
                                        // /sp claimuuid <uuid> <name> owneruuid <owner-uuid> — specific UUID
                                        .then(Commands.argument("owner_uuid", StringArgumentType.string())
                                                .executes(ctx -> {
                                                    final ServerPlayer player = ctx.getSource().getPlayerOrException();
                                                    final String uuidStr = StringArgumentType.getString(ctx, "uuid");
                                                    final String name = StringArgumentType.getString(ctx, "name");
                                                    final String ownerUuidStr = StringArgumentType.getString(ctx, "owner_uuid");

                                                    final UUID ownerUuid;
                                                    try {
                                                        ownerUuid = UUID.fromString(ownerUuidStr);
                                                    } catch (final IllegalArgumentException e) {
                                                        player.displayClientMessage(
                                                                Lang.tr("sableprotect.claimuuid.invalid_uuid", ownerUuidStr), false);
                                                        return 0;
                                                    }

                                                    return execute(player, uuidStr, name, ownerUuid,
                                                            ownerUuidStr.substring(0, 8) + "...", registry);
                                                })))));
    }

    private static int execute(final ServerPlayer sender, final String uuidStr, final String name,
                               final UUID ownerUuid, final String ownerDisplay,
                               final ClaimRegistry registry) {
        // Parse sub-level UUID
        final UUID subLevelId;
        try {
            subLevelId = UUID.fromString(uuidStr);
        } catch (final IllegalArgumentException e) {
            sender.displayClientMessage(
                    Lang.tr("sableprotect.claimuuid.invalid_uuid", uuidStr), false);
            return 0;
        }

        // Check name uniqueness (but allow if overriding the same sub-level's existing name)
        final UUID existingHolder = registry.getSubLevelByName(name);
        if (existingHolder != null && !existingHolder.equals(subLevelId)) {
            sender.displayClientMessage(
                    Lang.tr("sableprotect.claim.name_taken", name), false);
            return 0;
        }

        // Find sub-level across all dimensions
        ServerSubLevel subLevel = null;
        for (final ServerLevel level : sender.getServer().getAllLevels()) {
            final SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            final SubLevel sub = container.getSubLevel(subLevelId);
            if (sub instanceof ServerSubLevel serverSub) {
                subLevel = serverSub;
                break;
            }
        }

        if (subLevel == null) {
            sender.displayClientMessage(
                    Lang.tr("sableprotect.claimuuid.not_found", uuidStr), false);
            return 0;
        }

        // Override any existing claim — putClaim handles re-indexing/dirty automatically.
        final ClaimData data = new ClaimData(ownerUuid, name);
        final var pos = subLevel.logicalPose().position();
        data.setLastKnownPosition(new net.minecraft.world.phys.Vec3(pos.x(), pos.y(), pos.z()));
        data.setLastKnownDimension(subLevel.getLevel().dimension());
        registry.putClaim(subLevel.getUniqueId(), data);
        ClaimData.write(subLevel, data);

        sender.displayClientMessage(
                Lang.tr("sableprotect.claimuuid.success", name, ownerDisplay), false);
        return 1;
    }
}
