package dev.aerodev.sableprotect.lifecycle;

import dev.aerodev.sableprotect.SableProtectMod;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.freeze.FreezeManager;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class ClaimObserver implements SubLevelObserver {

    /**
     * Maximum number of ticks to wait for a freshly-added split fragment to have its mass
     * computed. After this, we apply (or skip) inheritance based on whatever mass is available.
     */
    private static final int MAX_PENDING_TICKS = 5;

    private static final class PendingEntry {
        final @Nullable ClaimData parentClaim;
        int ticksWaited;
        PendingEntry(@Nullable final ClaimData parentClaim) {
            this.parentClaim = parentClaim;
            this.ticksWaited = 0;
        }
    }

    private final ClaimRegistry registry;
    private final SubLevelContainer container;
    private final FreezeManager freezeManager;

    private final Map<UUID, PendingEntry> pending = new HashMap<>();

    public ClaimObserver(final ClaimRegistry registry, final SubLevelContainer container,
                         final FreezeManager freezeManager) {
        this.registry = registry;
        this.container = container;
        this.freezeManager = freezeManager;
    }

    @Override
    public void onSubLevelAdded(final SubLevel subLevel) {
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return;

        // Load path: if the userDataTag already carries claim data, index immediately.
        final ClaimData existing = ClaimData.read(serverSubLevel);
        if (existing != null) {
            registry.index(serverSubLevel);
            return;
        }

        // Pair this addition with the most recent SplitInheritanceQueue entry for this level
        // (pushed by our SplitListener moments earlier). If null, this isn't a split fragment.
        final SplitInheritanceQueue.Entry queued = SplitInheritanceQueue.poll(serverSubLevel.getLevel());
        if (queued == null || !queued.hasClaim()) return;

        // Defer until mass is ready; the parent claim is captured here so we don't depend on
        // Sable's transient splitFromSubLevel field, which gets cleared during the same tick.
        pending.put(serverSubLevel.getUniqueId(), new PendingEntry(queued.parentClaim()));
    }

    @Override
    public void onSubLevelRemoved(final SubLevel subLevel, final SubLevelRemovalReason reason) {
        pending.remove(subLevel.getUniqueId());
        registry.remove(subLevel.getUniqueId());
        freezeManager.cancel(subLevel.getUniqueId());
    }

    @Override
    public void tick(final SubLevelContainer subLevels) {
        if (pending.isEmpty()) return;

        final int minMass = SableProtectConfig.MINIMUM_CLAIM_MASS.get();
        final Iterator<Map.Entry<UUID, PendingEntry>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<UUID, PendingEntry> entry = it.next();
            final PendingEntry pendingEntry = entry.getValue();
            final SubLevel subLevel = subLevels.getSubLevel(entry.getKey());
            if (!(subLevel instanceof ServerSubLevel serverSubLevel)) {
                it.remove();
                continue;
            }

            final double mass = serverSubLevel.getMassTracker().getMass();
            pendingEntry.ticksWaited++;

            if (mass <= 0.0 && pendingEntry.ticksWaited < MAX_PENDING_TICKS) {
                // Mass not yet computed; wait one more tick.
                continue;
            }

            applyInheritance(serverSubLevel, pendingEntry.parentClaim, mass, minMass);
            it.remove();
        }
    }

    private void applyInheritance(final ServerSubLevel fragment, final ClaimData parentClaim,
                                  final double mass, final int minMass) {
        if (mass < minMass) {
            // Below threshold — leave unclaimed to avoid claimed debris.
            return;
        }

        final ClaimData inherited = parentClaim.copy();
        final String newName = registry.generateSuffixedName(parentClaim.getName());
        inherited.setName(newName);
        ClaimData.write(fragment, inherited);
        registry.index(fragment);

        SableProtectMod.LOGGER.info(
                "[sable-protect] Inherited claim '{}' from '{}' onto fragment {} (mass {})",
                newName, parentClaim.getName(), fragment.getUniqueId(), mass);
    }
}
