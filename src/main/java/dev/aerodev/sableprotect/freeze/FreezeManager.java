package dev.aerodev.sableprotect.freeze;

import dev.aerodev.sableprotect.SableProtectMod;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.util.Lang;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks active physics freezes applied via {@code /sp fetch}. Each tick the manager pins the
 * sub-level back to its anchored pose by zeroing velocity and re-teleporting; this avoids the
 * fragility of world-anchor physics constraints.
 */
public final class FreezeManager {

    private static final class FreezeState {
        final WeakReference<ServerSubLevel> subLevelRef;
        final Vector3d anchorPos;
        final Quaterniond anchorOrientation;
        final long expiryTick;
        final String displayName;
        final Set<UUID> notifyPlayers;
        /** Plot chunk that this freeze is keeping force-loaded (from an unloaded fetch). Null if not. */
        final @Nullable ChunkPos heldChunk;
        final @Nullable ResourceKey<Level> heldChunkDimension;

        FreezeState(final ServerSubLevel subLevel, final Vector3dc pos, final Quaterniondc orientation,
                    final long expiryTick, final String displayName, final Set<UUID> notifyPlayers,
                    final @Nullable ChunkPos heldChunk,
                    final @Nullable ResourceKey<Level> heldChunkDimension) {
            this.subLevelRef = new WeakReference<>(subLevel);
            this.anchorPos = new Vector3d(pos);
            this.anchorOrientation = new Quaterniond(orientation);
            this.expiryTick = expiryTick;
            this.displayName = displayName;
            this.notifyPlayers = notifyPlayers;
            this.heldChunk = heldChunk;
            this.heldChunkDimension = heldChunkDimension;
        }
    }

    private final Map<UUID, FreezeState> active = new HashMap<>();

    /** Already frozen? */
    public boolean isFrozen(final UUID subLevelUuid) {
        return active.containsKey(subLevelUuid);
    }

    /**
     * Pins the given sub-level at its current pose for {@code durationTicks} ticks. The pose
     * passed in is treated as the anchor; the freeze is enforced each tick via teleport.
     * Returns false if already frozen.
     */
    public boolean freeze(final ServerSubLevel subLevel, final Vector3dc anchorPos,
                          final Quaterniondc anchorOrientation, final long durationTicks,
                          final long currentTick) {
        return freeze(subLevel, anchorPos, anchorOrientation, durationTicks, currentTick, null, null);
    }

    /**
     * Same as {@link #freeze(ServerSubLevel, Vector3dc, Quaterniondc, long, long)} but also
     * holds {@code heldChunk} force-loaded for the freeze's lifetime. Used by the unloaded
     * fetch path so the ship stays available for the player to board until the freeze ends.
     */
    public boolean freeze(final ServerSubLevel subLevel, final Vector3dc anchorPos,
                          final Quaterniondc anchorOrientation, final long durationTicks,
                          final long currentTick,
                          final @Nullable ChunkPos heldChunk,
                          final @Nullable ResourceKey<Level> heldChunkDimension) {
        if (active.containsKey(subLevel.getUniqueId())) return false;

        final ClaimData data = ClaimData.read(subLevel);
        final String displayName = data != null ? data.getName() : subLevel.getUniqueId().toString();
        final Set<UUID> notify = new HashSet<>();
        if (data != null) {
            notify.add(data.getOwner());
            notify.addAll(data.getMembers());
        }

        active.put(subLevel.getUniqueId(),
                new FreezeState(subLevel, anchorPos, anchorOrientation,
                        currentTick + durationTicks, displayName, notify,
                        heldChunk, heldChunkDimension));
        return true;
    }

    /**
     * Called from a server tick handler. Re-pins each frozen sub-level and removes any whose
     * expiry has passed (notifying the owner / members).
     */
    public void tick(final MinecraftServer server, final long currentTick) {
        if (active.isEmpty()) return;
        final Iterator<Map.Entry<UUID, FreezeState>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<UUID, FreezeState> entry = it.next();
            final FreezeState state = entry.getValue();
            final ServerSubLevel subLevel = state.subLevelRef.get();

            // Sub-level was garbage collected or unloaded — drop the freeze silently.
            if (subLevel == null || subLevel.isRemoved()) {
                releaseHeldChunk(server, state);
                it.remove();
                continue;
            }

            if (currentTick >= state.expiryTick) {
                releaseHeldChunk(server, state);
                it.remove();
                notifyExpired(server, state);
                continue;
            }

            pinPose(subLevel, state);
        }
    }

    private static void pinPose(final ServerSubLevel subLevel, final FreezeState state) {
        final ServerLevel level = subLevel.getLevel();
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        final PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        try {
            pipeline.resetVelocity(subLevel);
            pipeline.teleport(subLevel, state.anchorPos, state.anchorOrientation);
        } catch (final Exception e) {
            SableProtectMod.LOGGER.warn("[sable-protect] Failed to maintain freeze on sub-level {}",
                    subLevel.getUniqueId(), e);
        }
    }

    /** Drop a freeze without notification, e.g. when the sub-level is being removed. */
    public void cancel(final UUID subLevelUuid) {
        // Note: caller doesn't have a server handle here, so we can't release a held chunk
        // synchronously. The chunk-force will be cleaned up on server stop via cancelAll.
        active.remove(subLevelUuid);
    }

    /** Drop all active freezes silently, e.g. on server shutdown. */
    public void cancelAll(final MinecraftServer server) {
        for (final FreezeState state : active.values()) releaseHeldChunk(server, state);
        active.clear();
    }

    /** @deprecated use {@link #cancelAll(MinecraftServer)} so held chunks can be released. */
    @Deprecated
    public void cancelAll() {
        active.clear();
    }

    private static void releaseHeldChunk(final MinecraftServer server, final FreezeState state) {
        if (state.heldChunk == null || state.heldChunkDimension == null) return;
        final ServerLevel level = server.getLevel(state.heldChunkDimension);
        if (level != null) {
            try {
                level.setChunkForced(state.heldChunk.x, state.heldChunk.z, false);
            } catch (final Throwable t) {
                SableProtectMod.LOGGER.warn("[sable-protect] Failed to release held chunk {} in {}",
                        state.heldChunk, state.heldChunkDimension.location());
            }
        }
    }

    private static void notifyExpired(final MinecraftServer server, final FreezeState state) {
        for (final UUID playerUuid : state.notifyPlayers) {
            final ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player != null) {
                player.displayClientMessage(
                        Lang.tr("sableprotect.fetch.freeze_expired", state.displayName), false);
            }
        }
    }
}
