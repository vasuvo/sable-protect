package dev.aerodev.sableprotect.freeze;

import dev.aerodev.sableprotect.SableProtectMod;
import dev.aerodev.sableprotect.command.FetchCommand;
import dev.aerodev.sableprotect.util.Lang;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Force-load + dispatch helper shared by {@code /sp fetch} and {@code /sp ground} when
 * acting on an unloaded sub-level. The flow:
 * <ol>
 *   <li>Force-load the plot chunk via {@code setChunkForced(true)} so it stays loaded
 *       across the freeze duration.</li>
 *   <li>Try a <em>synchronous</em> chunk load via
 *       {@code level.getChunkSource().getChunk(x, z, FULL, true)}; if Sable's chunk
 *       handler runs the sub-level deserialization in-line, the sub-level appears in
 *       the container immediately and we dispatch without the async wait.</li>
 *   <li>Otherwise register a pending entry — {@code ClaimObserver.onSubLevelAdded}
 *       will pick it up when the sub-level finally materializes, or
 *       {@link PendingFetchManager#tick} will time it out.</li>
 * </ol>
 *
 * <p>All steps log at INFO so the unloaded-fetch path is debuggable from a server log
 * without needing to reproduce the issue under a debugger.
 */
public final class PendingFetchDispatcher {

    private PendingFetchDispatcher() {}

    /** Returns true if the entry was dispatched synchronously, false if it's now pending. */
    public static boolean forceLoadAndDispatch(
            final ServerPlayer requester, final ServerLevel level, final UUID subLevelId,
            final ChunkPos plotChunk, final PendingFetchManager.Entry entry,
            final PendingFetchManager pendingFetchManager, final FreezeManager freezeManager) {

        SableProtectMod.LOGGER.info(
                "[sable-protect][debug] Pending fetch: name='{}' uuid={} dim={} plotChunk=[{},{}] dest={} requester={}",
                entry.displayName(), subLevelId, level.dimension().location(),
                plotChunk.x, plotChunk.z, entry.destination(), requester.getGameProfile().getName());

        // Step 1: add a force-load ticket so the chunk stays loaded.
        final boolean wasNotForced = level.setChunkForced(plotChunk.x, plotChunk.z, true);
        SableProtectMod.LOGGER.info(
                "[sable-protect][debug]   setChunkForced(+true) returned {} (true = newly forced, false = already forced or refused)",
                wasNotForced);

        // Step 2: attempt synchronous chunk load. This blocks until the chunk is at FULL
        // status; Sable's chunk-load handler should run in-line and add the sub-level.
        try {
            final ChunkAccess chunk = level.getChunkSource().getChunk(plotChunk.x, plotChunk.z, ChunkStatus.FULL, true);
            SableProtectMod.LOGGER.info(
                    "[sable-protect][debug]   sync chunk load returned: {}",
                    chunk == null ? "null" : chunk.getClass().getSimpleName() + " @ " + chunk.getPos());
        } catch (final Throwable t) {
            SableProtectMod.LOGGER.warn(
                    "[sable-protect][debug]   sync chunk load threw {}: {}",
                    t.getClass().getSimpleName(), t.getMessage());
        }

        // Step 3: check if the sub-level is now in the container.
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            SableProtectMod.LOGGER.warn("[sable-protect][debug]   no SubLevelContainer for {}", level.dimension().location());
            level.setChunkForced(plotChunk.x, plotChunk.z, false);
            requester.displayClientMessage(Lang.tr("sableprotect.fetch.failed"), false);
            return false;
        }

        final SubLevel found = container.getSubLevel(subLevelId);
        SableProtectMod.LOGGER.info(
                "[sable-protect][debug]   container.getSubLevel({}) → {}",
                subLevelId,
                found == null ? "null (sub-level not present)"
                        : found.getClass().getSimpleName() + " removed=" + found.isRemoved());

        if (found instanceof ServerSubLevel ssl && !ssl.isRemoved()) {
            // Sync dispatch — skip the pending registration entirely.
            SableProtectMod.LOGGER.info("[sable-protect][debug]   Sub-level loaded synchronously; dispatching now.");
            FetchCommand.executePendingFetch(ssl, entry, freezeManager);
            return true;
        }

        // Async fallback: register pending and let onSubLevelAdded dispatch when it shows up.
        pendingFetchManager.register(entry);
        SableProtectMod.LOGGER.info(
                "[sable-protect][debug]   Sub-level not yet in container — registered pending fetch (deadline tick {}, ~{} ticks from now)",
                entry.deadlineTick(),
                entry.deadlineTick() - level.getServer().getTickCount());
        return false;
    }

    /** Logged when a pending entry times out without the sub-level appearing. */
    public static void logTimeout(final ServerLevel level, final PendingFetchManager.Entry entry) {
        if (level == null) return;
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        SableProtectMod.LOGGER.warn(
                "[sable-protect][debug] Pending fetch TIMEOUT: name='{}' uuid={} dim={} plotChunk=[{},{}]",
                entry.displayName(), entry.subLevelId(), level.dimension().location(),
                entry.plotChunk().x, entry.plotChunk().z);
        if (container == null) {
            SableProtectMod.LOGGER.warn("[sable-protect][debug]   no SubLevelContainer to inspect");
            return;
        }
        final SubLevel still = container.getSubLevel(entry.subLevelId());
        SableProtectMod.LOGGER.warn(
                "[sable-protect][debug]   container.getSubLevel(uuid) at timeout → {}",
                still == null ? "null" : still.getClass().getSimpleName());
        SableProtectMod.LOGGER.warn(
                "[sable-protect][debug]   container.getLoadedCount() at timeout = {}", container.getLoadedCount());
    }
}
