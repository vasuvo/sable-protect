package dev.aerodev.sableprotect.freeze;

import dev.aerodev.sableprotect.SableProtectMod;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

        FreezeState(final ServerSubLevel subLevel, final Vector3dc pos, final Quaterniondc orientation,
                    final long expiryTick, final String displayName, final Set<UUID> notifyPlayers) {
            this.subLevelRef = new WeakReference<>(subLevel);
            this.anchorPos = new Vector3d(pos);
            this.anchorOrientation = new Quaterniond(orientation);
            this.expiryTick = expiryTick;
            this.displayName = displayName;
            this.notifyPlayers = notifyPlayers;
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
                        currentTick + durationTicks, displayName, notify));
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
                it.remove();
                continue;
            }

            if (currentTick >= state.expiryTick) {
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
        active.remove(subLevelUuid);
    }

    /** Drop all active freezes silently, e.g. on server shutdown. */
    public void cancelAll() {
        active.clear();
    }

    private static void notifyExpired(final MinecraftServer server, final FreezeState state) {
        for (final UUID playerUuid : state.notifyPlayers) {
            final ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player != null) {
                player.displayClientMessage(
                        Component.translatable("sableprotect.fetch.freeze_expired", state.displayName), false);
            }
        }
    }
}
