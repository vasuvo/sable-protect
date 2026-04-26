package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.freeze.FreezeManager;
import dev.aerodev.sableprotect.freeze.PendingFetchManager;
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
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

public final class FetchCommand {

    private FetchCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register(final ClaimRegistry registry,
                                                                      final FreezeManager freezeManager,
                                                                      final PendingFetchManager pendingFetchManager) {
        return Commands.literal("fetch")
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
            player.displayClientMessage(Lang.tr("sableprotect.fetch.not_authorized"), false);
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
            return executeLoaded(player, name, subLevel, freezeManager);
        }
        return executeUnloaded(player, name, subLevelId, data, pendingFetchManager, freezeManager);
    }

    /** Standard fetch: ship is loaded; teleport directly. */
    private static int executeLoaded(final ServerPlayer player, final String name,
                                     final ServerSubLevel subLevel, final FreezeManager freezeManager) {
        final ServerLevel level = subLevel.getLevel();
        final WorldBorder border = level.getWorldBorder();
        final Pose3d pose = subLevel.logicalPose();
        final Vector3dc currentPos = pose.position();

        if (isWithinBorder(border, currentPos.x(), currentPos.z())) {
            player.displayClientMessage(Lang.tr("sableprotect.fetch.inside_border"), false);
            return 0;
        }

        final Vector3d destination = computeDestination(level, border, currentPos.x(), currentPos.z());
        final Quaterniondc orientation = pose.orientation();

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

        final boolean frozen = freezeManager.freeze(subLevel, destination, orientation, durationTicks, currentTick);
        if (!frozen) {
            player.displayClientMessage(Lang.tr("sableprotect.fetch.freeze_unavailable"), false);
            return 0;
        }

        player.displayClientMessage(
                Lang.tr("sableprotect.fetch.success", name,
                        Component.literal((int) destination.x + ", " + (int) destination.y + ", " + (int) destination.z)
                                .withStyle(ChatFormatting.AQUA),
                        durationSeconds),
                false);
        return 1;
    }

    /** Unloaded fetch: force-load the plot chunk, register a pending fetch, and dispatch on load. */
    private static int executeUnloaded(final ServerPlayer player, final String name,
                                       final UUID subLevelId, final ClaimData data,
                                       final PendingFetchManager pendingFetchManager,
                                       final FreezeManager freezeManager) {
        final Vec3 lastPos = data.getLastKnownPosition();
        final ChunkPos plotChunk = data.getLastKnownPlotChunk();
        final ResourceKey<Level> dimension = data.getLastKnownDimension();
        if (lastPos == null || plotChunk == null || dimension == null) {
            // Pre-Phase-11 claim or one that's never been observed — can't force-load.
            player.displayClientMessage(Lang.tr("sableprotect.fetch.unloaded_unavailable", name), false);
            return 0;
        }

        final MinecraftServer server = player.getServer();
        final ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            player.displayClientMessage(Lang.tr("sableprotect.fetch.failed"), false);
            return 0;
        }

        final WorldBorder border = level.getWorldBorder();
        if (isWithinBorder(border, lastPos.x, lastPos.z)) {
            player.displayClientMessage(Lang.tr("sableprotect.fetch.inside_border"), false);
            return 0;
        }

        // Compute the destination from the cached position (the same logic would run after load).
        final Vector3d destination = computeDestination(level, border, lastPos.x, lastPos.z);

        final int durationSeconds = SableProtectConfig.FREEZE_DURATION_SECONDS.get();
        final long durationTicks = durationSeconds * 20L;
        final long currentTick = server.getTickCount();
        final long deadline = currentTick + PendingFetchManager.DEFAULT_TIMEOUT_TICKS;

        final PendingFetchManager.Entry entry = new PendingFetchManager.Entry(
                subLevelId, dimension, plotChunk, destination, /* orientation override */ null,
                (int) durationTicks, player.getUUID(), name,
                "sableprotect.fetch.success", deadline);

        // Force-load + try-sync-dispatch via the shared helper. If sync dispatch succeeds, the
        // success message is sent inside executePendingFetch; otherwise we tell the player to
        // wait for the async load.
        final boolean dispatched = dev.aerodev.sableprotect.freeze.PendingFetchDispatcher.forceLoadAndDispatch(
                player, level, subLevelId, plotChunk, entry, pendingFetchManager, freezeManager);
        if (!dispatched) {
            player.displayClientMessage(Lang.tr("sableprotect.fetch.unloaded_loading", name), false);
        }
        return 1;
    }

    /**
     * Called from {@code ClaimObserver.onSubLevelAdded} when a force-loaded sub-level comes back
     * online with a queued pending fetch. Runs the teleport + freeze using the pre-computed
     * destination, and hands the held chunk to the freeze so it stays loaded for the duration.
     */
    public static void executePendingFetch(final ServerSubLevel subLevel,
                                           final PendingFetchManager.Entry entry,
                                           final FreezeManager freezeManager) {
        final ServerLevel level = subLevel.getLevel();
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            // No way to teleport — release chunk and notify failure.
            try {
                level.setChunkForced(entry.plotChunk().x, entry.plotChunk().z, false);
            } catch (final Throwable ignored) {}
            notifyRequester(level.getServer(), entry, "sableprotect.fetch.failed", null);
            return;
        }

        final PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        // Use override if provided (e.g. /sp ground snaps to upright); else live orientation.
        final Quaterniondc orientation = entry.orientationOverride() != null
                ? new Quaterniond(entry.orientationOverride())
                : new Quaterniond(subLevel.logicalPose().orientation());
        pipeline.resetVelocity(subLevel);
        pipeline.teleport(subLevel, entry.destination(), orientation);

        final long currentTick = level.getServer().getTickCount();
        final boolean frozen = freezeManager.freeze(subLevel, entry.destination(), orientation,
                entry.durationTicks(), currentTick, entry.plotChunk(), entry.dimension());
        if (!frozen) {
            try {
                level.setChunkForced(entry.plotChunk().x, entry.plotChunk().z, false);
            } catch (final Throwable ignored) {}
            notifyRequester(level.getServer(), entry, "sableprotect.fetch.freeze_unavailable", null);
            return;
        }

        // Success — notify requester. The freeze owns the held chunk now and will release it
        // when it expires.
        final int durationSeconds = (int) (entry.durationTicks() / 20L);
        notifyRequester(level.getServer(), entry, entry.successLangKey(),
                new Object[] {
                        entry.displayName(),
                        Component.literal((int) entry.destination().x + ", " + (int) entry.destination().y + ", " + (int) entry.destination().z)
                                .withStyle(ChatFormatting.AQUA),
                        durationSeconds
                });
    }

    private static void notifyRequester(final MinecraftServer server,
                                        final PendingFetchManager.Entry entry,
                                        final String langKey, final Object @org.jetbrains.annotations.Nullable [] args) {
        final ServerPlayer requester = server.getPlayerList().getPlayer(entry.requester());
        if (requester == null) return;
        if (args == null) {
            requester.displayClientMessage(Lang.tr(langKey), false);
        } else {
            requester.displayClientMessage(Lang.tr(langKey, args), false);
        }
    }

    private static Vector3d computeDestination(final ServerLevel level, final WorldBorder border,
                                               final double sourceX, final double sourceZ) {
        final int inset = SableProtectConfig.FETCH_BORDER_INSET.get();
        final double targetX = clampInside(sourceX, border.getMinX(), border.getMaxX(), inset);
        final double targetZ = clampInside(sourceZ, border.getMinZ(), border.getMaxZ(), inset);
        final int targetY = findSafeY(level, (int) Math.floor(targetX), (int) Math.floor(targetZ));
        return new Vector3d(targetX, targetY, targetZ);
    }

    private static boolean isWithinBorder(final WorldBorder border, final double x, final double z) {
        return x >= border.getMinX() && x <= border.getMaxX()
                && z >= border.getMinZ() && z <= border.getMaxZ();
    }

    private static double clampInside(final double value, final double min, final double max, final int inset) {
        final double interiorMin = min + inset;
        final double interiorMax = max - inset;
        if (interiorMin >= interiorMax) {
            return (min + max) / 2.0;
        }
        if (value < interiorMin) return interiorMin;
        if (value > interiorMax) return interiorMax;
        return value;
    }

    private static int findSafeY(final Level level, final int x, final int z) {
        final BlockPos surface = level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(x, 0, z));
        return surface.getY() + 5;
    }
}
