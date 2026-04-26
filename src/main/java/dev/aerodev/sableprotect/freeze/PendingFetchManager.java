package dev.aerodev.sableprotect.freeze;

import dev.aerodev.sableprotect.SableProtectMod;
import dev.aerodev.sableprotect.util.Lang;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks {@code /sp fetch} requests against unloaded sub-levels. The flow is:
 * <ol>
 *   <li>{@link #register} force-loads the sub-level's plot chunk and stores the fetch params.</li>
 *   <li>When the sub-level comes back online, {@code ClaimObserver.onSubLevelAdded} calls
 *       {@link #consume} to retrieve the entry; the caller then runs the actual teleport
 *       and freeze using those params.</li>
 *   <li>If the sub-level fails to load within {@link #DEFAULT_TIMEOUT_TICKS}, {@link #tick}
 *       releases the chunk force-load and notifies the requester of failure.</li>
 * </ol>
 *
 * <p>The chunk force-load is deliberately <em>not</em> released by this manager when the
 * fetch succeeds — instead the dispatched {@code FreezeManager} entry inherits the chunk
 * and releases it when its freeze expires, ensuring the ship stays loaded for the
 * player to board.
 */
public final class PendingFetchManager {

    /** Max ticks to wait for the sub-level to load after force-loading its plot chunk. */
    public static final long DEFAULT_TIMEOUT_TICKS = 300; // 15 seconds

    public record Entry(
            UUID subLevelId,
            ResourceKey<Level> dimension,
            ChunkPos plotChunk,
            Vector3d destination,
            /** If non-null, force this orientation on dispatch; else use the live post-load orientation. */
            @Nullable Quaterniondc orientationOverride,
            int durationTicks,
            UUID requester,
            String displayName,
            String successLangKey,
            long deadlineTick
    ) {}

    private final Map<UUID, Entry> pending = new HashMap<>();

    public boolean isPending(final UUID subLevelId) {
        return pending.containsKey(subLevelId);
    }

    public boolean hasAny() {
        return !pending.isEmpty();
    }

    public void register(final Entry entry) {
        pending.put(entry.subLevelId(), entry);
    }

    /** Consume the pending entry for {@code subLevelId} (call when the sub-level comes back). */
    public Entry consume(final UUID subLevelId) {
        return pending.remove(subLevelId);
    }

    public void tick(final MinecraftServer server, final long currentTick) {
        if (pending.isEmpty()) return;
        final Iterator<Map.Entry<UUID, Entry>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            final Entry entry = it.next().getValue();
            if (currentTick < entry.deadlineTick) continue;

            // Sub-level didn't load in time — log diagnostics, release the chunk, notify failure.
            PendingFetchDispatcher.logTimeout(server.getLevel(entry.dimension), entry);
            releaseChunk(server, entry);
            final ServerPlayer requester = server.getPlayerList().getPlayer(entry.requester);
            if (requester != null) {
                requester.displayClientMessage(
                        Lang.tr("sableprotect.fetch.unloaded_timeout", entry.displayName), false);
            }
            it.remove();
        }
    }

    /** Drop pending entries silently, e.g. on server stop. */
    public void cancelAll(final MinecraftServer server) {
        for (final Entry entry : pending.values()) releaseChunk(server, entry);
        pending.clear();
    }

    private static void releaseChunk(final MinecraftServer server, final Entry entry) {
        final ServerLevel level = server.getLevel(entry.dimension);
        if (level != null) {
            try {
                level.setChunkForced(entry.plotChunk.x, entry.plotChunk.z, false);
            } catch (final Throwable t) {
                SableProtectMod.LOGGER.warn("[sable-protect] Failed to release forced chunk {} in {}",
                        entry.plotChunk, entry.dimension.location());
            }
        }
    }
}
