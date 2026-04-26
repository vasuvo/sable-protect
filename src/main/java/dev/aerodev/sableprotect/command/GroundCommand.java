package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.freeze.FreezeManager;
import dev.aerodev.sableprotect.freeze.PendingFetchManager;
import dev.aerodev.sableprotect.protection.ProtectionHelper;
import dev.aerodev.sableprotect.util.CrewPresence;
import dev.aerodev.sableprotect.util.Lang;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

/**
 * Emergency landing command. Teleports a sub-level straight down to the safe surface above
 * the ground at its current XZ, snaps the orientation to upright, and freezes physics for
 * the configured duration. Requires the issuing player to be a crew member (owner or
 * member) and the entire crew (including the issuer and anyone on board) to be outside the
 * configured absence radius.
 *
 * <p>Designed as a backup option for owners who can't physically reach their ship — the
 * teleport is intentionally heavy-handed (vertical drop + identity orientation) and may
 * cause minor collision artifacts at the destination.
 */
public final class GroundCommand {

    /** Buffer above the surface heightmap to drop the sub-level at. Higher than fetch's
     *  +5 to give plenty of room for awkward hulls and avoid clipping into terrain. */
    private static final int GROUND_HEIGHT_BUFFER = 20;

    private GroundCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register(
            final ClaimRegistry registry, final FreezeManager freezeManager,
            final PendingFetchManager pendingFetchManager) {
        return Commands.literal("ground")
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                            for (final UUID id : registry.getOwnedBy(player.getUUID())) {
                                final String n = registry.getNameByUuid(id);
                                if (n != null) builder.suggest(n);
                            }
                            for (final UUID id : registry.getMemberOf(player.getUUID())) {
                                final String n = registry.getNameByUuid(id);
                                if (n != null) builder.suggest(n);
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            final ServerPlayer player = ctx.getSource().getPlayerOrException();
                            final String name = StringArgumentType.getString(ctx, "name");
                            return execute(player, name, registry, freezeManager, pendingFetchManager);
                        }));
    }

    private static int execute(final ServerPlayer player, final String name,
                               final ClaimRegistry registry, final FreezeManager freezeManager,
                               final PendingFetchManager pendingFetchManager) {
        final UUID subLevelId = registry.getSubLevelByName(name);
        if (subLevelId == null) {
            player.displayClientMessage(Lang.tr("sableprotect.not_found", name), false);
            return 0;
        }

        final ClaimData data = registry.getClaim(subLevelId);
        if (data == null) {
            player.displayClientMessage(Lang.tr("sableprotect.not_found", name), false);
            return 0;
        }

        if (data.getRole(player.getUUID()) == ClaimRole.DEFAULT) {
            player.displayClientMessage(Lang.tr("sableprotect.ground.not_authorized"), false);
            return 0;
        }

        if (freezeManager.isFrozen(subLevelId)) {
            player.displayClientMessage(Lang.tr("sableprotect.fetch.already_frozen", name), false);
            return 0;
        }
        if (pendingFetchManager.isPending(subLevelId)) {
            player.displayClientMessage(Lang.tr("sableprotect.fetch.already_pending", name), false);
            return 0;
        }

        final ServerSubLevel subLevel = UnclaimCommand.findSubLevel(player, subLevelId);
        if (subLevel != null) {
            return executeLoaded(player, name, subLevel, data, freezeManager);
        }
        return executeUnloaded(player, name, subLevelId, data, pendingFetchManager, freezeManager);
    }

    private static int executeLoaded(final ServerPlayer player, final String name,
                                     final ServerSubLevel subLevel, final ClaimData data,
                                     final FreezeManager freezeManager) {
        final ServerLevel level = subLevel.getLevel();
        final Pose3d pose = subLevel.logicalPose();
        final Vector3dc currentPos = pose.position();

        // Crew-presence check (no issuer exclusion: backup option only).
        if (!ProtectionHelper.isAdminBypass(player)) {
            final int radius = SableProtectConfig.ABSENCE_RADIUS.get();
            final UUID blocker = CrewPresence.findCrewWithinRadius(
                    level.getServer(), data,
                    new Vec3(currentPos.x(), currentPos.y(), currentPos.z()),
                    level.dimension(),
                    (long) radius * radius,
                    null);
            if (blocker != null) {
                final ServerPlayer blockerPlayer = level.getServer().getPlayerList().getPlayer(blocker);
                final String blockerName = blockerPlayer != null
                        ? blockerPlayer.getGameProfile().getName()
                        : blocker.toString().substring(0, 8);
                player.displayClientMessage(
                        Lang.tr("sableprotect.ground.crew_present", blockerName), false);
                return 0;
            }
        }

        final Vector3d destination = computeGroundDestination(level, currentPos.x(), currentPos.z());
        // Snap to upright (identity quaternion) — see class javadoc.
        final Quaterniond orientation = new Quaterniond();

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            player.displayClientMessage(Lang.tr("sableprotect.fetch.failed"), false);
            return 0;
        }
        final PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        pipeline.resetVelocity(subLevel);
        pipeline.teleport(subLevel, destination, orientation);

        final int durationSeconds = SableProtectConfig.FREEZE_DURATION_SECONDS.get();
        final long durationTicks = durationSeconds * 20L;
        final long currentTick = level.getServer().getTickCount();
        if (!freezeManager.freeze(subLevel, destination, orientation, durationTicks, currentTick)) {
            player.displayClientMessage(Lang.tr("sableprotect.fetch.freeze_unavailable"), false);
            return 0;
        }

        player.displayClientMessage(
                Lang.tr("sableprotect.ground.success", name,
                        Component.literal((int) destination.x + ", " + (int) destination.y + ", " + (int) destination.z)
                                .withStyle(ChatFormatting.AQUA),
                        durationSeconds),
                false);
        return 1;
    }

    private static int executeUnloaded(final ServerPlayer player, final String name,
                                       final UUID subLevelId, final ClaimData data,
                                       final PendingFetchManager pendingFetchManager,
                                       final FreezeManager freezeManager) {
        final Vec3 lastPos = data.getLastKnownPosition();
        final ResourceKey<Level> dimension = data.getLastKnownDimension();
        if (lastPos == null || dimension == null) {
            player.displayClientMessage(Lang.tr("sableprotect.fetch.unloaded_unavailable", name), false);
            return 0;
        }
        final ChunkPos plotChunk = new ChunkPos(
                ((int) Math.floor(lastPos.x)) >> 4,
                ((int) Math.floor(lastPos.z)) >> 4);

        final MinecraftServer server = player.getServer();
        final ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            player.displayClientMessage(Lang.tr("sableprotect.fetch.failed"), false);
            return 0;
        }

        // Crew-presence check against cached XZ (Y is irrelevant for absence semantics).
        if (!ProtectionHelper.isAdminBypass(player)) {
            final int radius = SableProtectConfig.ABSENCE_RADIUS.get();
            final UUID blocker = CrewPresence.findCrewWithinRadius(
                    server, data, lastPos, dimension, (long) radius * radius, null);
            if (blocker != null) {
                final ServerPlayer blockerPlayer = server.getPlayerList().getPlayer(blocker);
                final String blockerName = blockerPlayer != null
                        ? blockerPlayer.getGameProfile().getName()
                        : blocker.toString().substring(0, 8);
                player.displayClientMessage(
                        Lang.tr("sableprotect.ground.crew_present", blockerName), false);
                return 0;
            }
        }

        // Vertical-only target computed from cached XZ.
        final Vector3d destination = computeGroundDestination(level, lastPos.x, lastPos.z);

        final int durationSeconds = SableProtectConfig.FREEZE_DURATION_SECONDS.get();
        final long durationTicks = durationSeconds * 20L;
        final long currentTick = server.getTickCount();
        final long deadline = currentTick + PendingFetchManager.DEFAULT_TIMEOUT_TICKS;

        final PendingFetchManager.Entry entry = new PendingFetchManager.Entry(
                subLevelId, dimension, plotChunk, destination,
                /* snap upright on dispatch */ new Quaterniond(),
                (int) durationTicks, player.getUUID(), name,
                "sableprotect.ground.success", deadline);

        final boolean dispatched = dev.aerodev.sableprotect.freeze.PendingFetchDispatcher.forceLoadAndDispatch(
                player, level, subLevelId, plotChunk, entry, pendingFetchManager, freezeManager);
        if (!dispatched) {
            player.displayClientMessage(Lang.tr("sableprotect.ground.unloaded_loading", name), false);
        }
        return 1;
    }

    /** XZ stays put; Y is the surface heightmap plus an extra-generous buffer. */
    private static Vector3d computeGroundDestination(final Level level, final double x, final double z) {
        final BlockPos surface = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos((int) Math.floor(x), 0, (int) Math.floor(z)));
        return new Vector3d(x, surface.getY() + GROUND_HEIGHT_BUFFER, z);
    }
}
