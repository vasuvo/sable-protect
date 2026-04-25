package dev.aerodev.sableprotect.lifecycle;

import dev.aerodev.sableprotect.claim.ClaimData;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Bridges Sable's {@code SplitListener} (fires before a new fragment exists) and the
 * {@code ClaimObserver.onSubLevelAdded} callback (fires for the new fragment but after Sable
 * has already begun clearing the parent linkage). The split listener pushes a snapshot of the
 * parent's claim — or {@link Entry#unclaimed()} if the parent is unclaimed — and the observer
 * polls it when the matching fragment is added.
 *
 * <p>Server-thread only. Sable's split flow is synchronous (SplitListener.addBlocks →
 * SubLevelAssemblyHelper.assembleBlocks → container.allocateNewSubLevel → onSubLevelAdded), so
 * a per-level FIFO with no synchronization is sufficient.
 *
 * <p>The queue always pushes for every split fragment (even unclaimed parents) so push/poll
 * stays 1:1, preventing a stale entry from one split being mis-matched to a later fragment.
 */
public final class SplitInheritanceQueue {

    /** A queued split fragment's expected parent claim. {@code parentClaim} is null if unclaimed. */
    public record Entry(@Nullable ClaimData parentClaim) {
        public static Entry unclaimed() { return new Entry(null); }
        public boolean hasClaim() { return parentClaim != null; }
    }

    private static final Map<Level, Deque<Entry>> QUEUES = new HashMap<>();

    private SplitInheritanceQueue() {}

    public static void push(final Level level, final Entry entry) {
        QUEUES.computeIfAbsent(level, k -> new ArrayDeque<>()).addLast(entry);
    }

    /** Returns the next entry for this level, or null if none was queued. */
    public static @Nullable Entry poll(final Level level) {
        final Deque<Entry> queue = QUEUES.get(level);
        if (queue == null || queue.isEmpty()) return null;
        return queue.pollFirst();
    }

    public static void clear() {
        QUEUES.clear();
    }
}
