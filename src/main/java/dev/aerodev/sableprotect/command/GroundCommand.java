package dev.aerodev.sableprotect.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.aerodev.sableprotect.SableProtectMod;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.claim.ClaimRole;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.freeze.FreezeManager;
import dev.aerodev.sableprotect.freeze.PendingFetchManager;
import dev.aerodev.sableprotect.util.Lang;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
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
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nullable;
import java.util.UUID;

public final class GroundCommand {

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

    public static int groundTrys = 20;

    private static int execute(final ServerPlayer player, final String name,
                               final ClaimRegistry registry, final FreezeManager freezeManager,
                               final PendingFetchManager pendingFetchManager) {
        final UUID subLevelId = registry.getSubLevelByName(name);
        if (subLevelId == null) { // Checks if sub-level exists
            player.displayClientMessage(Lang.tr("sableprotect.not_found", name), false);
            return 0;
        }

        final ClaimData data = registry.getClaim(subLevelId);
        if (data == null) { // Checks if sub-level has claim data
            player.displayClientMessage(Lang.tr("sableprotect.not_found", name), false);
            return 0;
        }

        if (data.getRole(player.getUUID()) == ClaimRole.DEFAULT) { //Checks if player is allowed to ground sub-level
            player.displayClientMessage(Lang.tr("sableprotect.ground.not_authorized"), false);
            return 0;
        }

        if (freezeManager.isFrozen(subLevelId)) { //Checks if sub-level is frozen
            player.displayClientMessage(Lang.tr("sableprotect.fetch.already_frozen", name), false);
            return 0;
        }

        final ServerSubLevel subLevel = UnclaimCommand.findSubLevel(player, subLevelId);
        if (subLevel != null) {
            return groundSublevel(player, name, subLevel, freezeManager, null, null);
        }
        //Continues if sub-level is unloaded
        //Loads Chunk that sub-level is in, then continues as normal, will continue working soon
        SableProtectMod.LOGGER.info("[sable-protect][debug]   Chunk is unloaded, taking unloaded path");
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

        try {
            //Force Loads where the sub-level was last recorded
            final boolean wasNotForced = level.setChunkForced(plotChunk.x, plotChunk.z, true);
            SableProtectMod.LOGGER.info(
                    "[sable-protect][debug]   setChunkForced(+true) returned {} (true = newly forced, false = already forced or refused)",
                    wasNotForced);

            //Then, attempts synchronous chunk load to stop the code until chunk is at FULL
            final ChunkAccess chunk = level.getChunkSource().getChunk(plotChunk.x, plotChunk.z, ChunkStatus.FULL, true);
            SableProtectMod.LOGGER.info(
                    "[sable-protect][debug]   sync chunk load returned: {}",
                    chunk == null ? "null" : chunk.getClass().getSimpleName() + " @ " + chunk.getPos());
            //Checks if the sub-level is now in the container.
            final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                SableProtectMod.LOGGER.warn("[sable-protect][debug]   no SubLevelContainer for {}", level.dimension().location());
                level.setChunkForced(plotChunk.x, plotChunk.z, false);
                player.displayClientMessage(Lang.tr("sableprotect.fetch.failed"), false);
                return 0;
            }
            SableProtectMod.LOGGER.info("[sable-protect][debug]   Found SubLevelContainer");
            //If it is found in the container, continues as normal, except with the new arguements of plotChunk and dimension, so FreezeManager can unload the chunk after freezing
            final SubLevel found = container.getSubLevel(subLevelId);
            if (found instanceof ServerSubLevel ssl && !ssl.isRemoved()) {
                final PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
                pipeline.resetVelocity(subLevel);
                SableProtectMod.LOGGER.info("[sable-protect][debug]   Sub-level loaded synchronously; grounding now.");
                if (groundSublevel(player, name, ssl, freezeManager, plotChunk, dimension) == 0) {
                    level.setChunkForced(plotChunk.x, plotChunk.z, false);
                    return 0;
                }
            }
            //Continues if Sub-Level is not in container yet
            SableProtectMod.LOGGER.info("[sable-protect][debug]   Sub-level was not loaded correctly");
            final long currentTick = server.getTickCount();
            final int durationSeconds = SableProtectConfig.FREEZE_DURATION_SECONDS.get();
            final long durationTicks = durationSeconds * currentTick;
            final long deadline = currentTick + PendingFetchManager.DEFAULT_TIMEOUT_TICKS;

            final PendingFetchManager.Entry entry = new PendingFetchManager.Entry(
                    subLevelId, dimension, plotChunk, new Vector3d(lastPos.x, lastPos.y, lastPos.z),
                    /* snap upright on dispatch */ null,
                    (int) durationTicks, player.getUUID(), name,
                    "sableprotect.ground.success", deadline);
            pendingFetchManager.register(entry);
            SableProtectMod.LOGGER.info(
                    "[sable-protect][debug]   Sub-level not yet in container — registered pending fetch (deadline tick {}, ~{} ticks from now)",
                    entry.deadlineTick(),
                    entry.deadlineTick() - level.getServer().getTickCount());

            player.displayClientMessage(Lang.tr("sableprotect.ground.unloaded_loading", name), false);

            return 1;

        } catch (final Throwable t) {
            SableProtectMod.LOGGER.warn(
                    "[sable-protect][debug]   chunk load threw {}: {}",
                    t.getClass().getSimpleName(), t.getMessage());
            level.setChunkForced(plotChunk.x, plotChunk.z, false);
        }

        return 0;
    }

    public static int groundSublevel(final ServerPlayer player, final String name,
                                      final ServerSubLevel subLevel,
                                      final FreezeManager freezeManager,
                                      final @Nullable ChunkPos heldChunk,
                                      final @Nullable ResourceKey<Level> heldChunkDimension) {
        SableProtectMod.LOGGER.info("[sable-protect][debug]   groundSublevel was called");
        final ServerLevel level = subLevel.getLevel();
        final Pose3d pose = subLevel.logicalPose();
        final Vector3dc currentPos = pose.position();
        final MinecraftServer server = level.getServer();

        //Checks if a player is aboard the sub-level before grounding
        ServerPlayer playerAboard = findPlayerAboard(server, subLevel);
        if (playerAboard != null) {
            player.displayClientMessage(Lang.tr("sableprotect.ground.crew_present", playerAboard.getGameProfile().getName()), false);
            SableProtectMod.LOGGER.info("[sable-protect][debug]   Crew Present");
            return 0;
        }

        final Vector3d destination = computeGroundDestination(level, currentPos.x(), currentPos.z());

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            SableProtectMod.LOGGER.info("[sable-protect][debug]   Container fetch failed");
            player.displayClientMessage(Lang.tr("sableprotect.fetch.failed"), false);
            return 0;
        }

        final PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        pipeline.resetVelocity(subLevel);
        final int durationSeconds = SableProtectConfig.FREEZE_DURATION_SECONDS.get();
        final long durationTicks = (long) (durationSeconds * server.tickRateManager().tickrate());

        //Starts the animation, then freezes the sub-level

        SubLevelAssemblyHelper.animateTo(subLevel, BlockPos.containing(destination.x, destination.y, destination.z), callback -> {
            SableProtectMod.LOGGER.info("[sable-protect][debug]   Callback Called");
            if (callback.isEmpty()) {
                releaseGroundChunk(server, heldChunk, heldChunkDimension);
                player.displayClientMessage(Lang.tr("sableprotect.ground.failed"), false);
                return;
            }
            final long currentTick = level.getServer().getTickCount();
            Pose3d newpose = subLevel.logicalPose();

            //Needed so if the chunk is force loaded, it can be unloaded after freeze is done
            boolean freeze;
            if (heldChunk != null) {
                freeze = freezeManager.freeze(subLevel, new Vector3d(newpose.position().x, newpose.position().y, newpose.position().z), new Quaterniond(newpose.orientation()), durationTicks, currentTick, heldChunk, heldChunkDimension);
            } else {
                freeze = freezeManager.freeze(subLevel, new Vector3d(newpose.position().x, newpose.position().y, newpose.position().z), new Quaterniond(newpose.orientation()), durationTicks, currentTick);
            }

            if (!freeze) {
                releaseGroundChunk(server, heldChunk, heldChunkDimension);
                player.displayClientMessage(Lang.tr("sableprotect.fetch.freeze_unavailable"), false);
                return;
            }
            player.displayClientMessage(
                    Lang.tr("sableprotect.ground.success", name,
                            Component.literal((int) destination.x + ", " + (int) destination.y + ", " + (int) destination.z)
                                    .withStyle(ChatFormatting.AQUA),
                            durationSeconds),
                    false);
        });

        return 1;

    }

    private static void releaseGroundChunk(final MinecraftServer server, final ChunkPos heldChunk,
                                           final ResourceKey<Level> heldChunkDimension) {
        if (heldChunk == null || heldChunkDimension == null) return;
        final ServerLevel heldLevel = server.getLevel(heldChunkDimension);
        if (heldLevel != null) heldLevel.setChunkForced(heldChunk.x, heldChunk.z, false);
    }

    private static ServerPlayer findPlayerAboard(MinecraftServer server, ServerSubLevel target) {
        UUID targetId = target.getUniqueId();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            SubLevel related = Sable.HELPER.getTrackingSubLevel(player);

            if (related == null) {
                related = Sable.HELPER.getContaining(player);
            }

            if (related != null && related.getUniqueId().equals((targetId))) {
                return player;
            }
        }
        return null;
    }

    private static Vector3d computeGroundDestination(final Level level, final double x, final double z) {
        // Force-load the chunk so the heightmap query gives a real surface instead of
        // bottom-of-world for unloaded chunks (see vanilla's {@code ServerLevel#getHeight}).
        if (level instanceof net.minecraft.server.level.ServerLevel server) {
            try {
                server.getChunkSource().getChunk(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4,
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            } catch (final Throwable ignored) {}
        }
        final BlockPos surface = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos((int) Math.floor(x), 0, (int) Math.floor(z)));
        return new Vector3d(x, surface.getY(), z);
    }

}
