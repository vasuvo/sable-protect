package dev.aerodev.sableprotect.lifecycle;

import dev.aerodev.sableprotect.SableProtectMod;
import dev.aerodev.sableprotect.audit.AuditLog;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.command.FetchCommand;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.freeze.FreezeManager;
import dev.aerodev.sableprotect.freeze.PendingFetchManager;
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
    private final PendingFetchManager pendingFetchManager;

    private final Map<UUID, PendingEntry> pending = new HashMap<>();

    public ClaimObserver(final ClaimRegistry registry, final SubLevelContainer container,
                         final FreezeManager freezeManager,
                         final PendingFetchManager pendingFetchManager) {
        this.registry = registry;
        this.container = container;
        this.freezeManager = freezeManager;
        this.pendingFetchManager = pendingFetchManager;
    }

    @Override
    public void onSubLevelAdded(final SubLevel subLevel) {
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return;
        final UUID id = serverSubLevel.getUniqueId();

        // Log every server sub-level addition while there's any pending fetch — useful to
        // verify Sable is actually deserializing the target sub-level when we force-load
        // its chunk.
        if (pendingFetchManager.hasAny()) {
            SableProtectMod.LOGGER.info(
                    "[sable-protect][debug] onSubLevelAdded: id={} pos={} (pending fetches active)",
                    id, serverSubLevel.logicalPose().position());
        }

        // Reconcile storage <-> userDataTag and update indexes. This handles three cases:
        //   * tag has a claim, storage doesn't  -> legacy migration into storage
        //   * storage has a claim, tag doesn't  -> claim was edited while unloaded, sync to tag
        //   * both agree                        -> idempotent re-index
        registry.index(serverSubLevel);
        final ClaimData existing = registry.getClaim(id);
        if (existing != null) {
            // Refresh the cached position to the current pose so unloaded-info readouts
            // are accurate at least up to the most recent load.
            cacheCurrentPosition(serverSubLevel, existing, id);

            // If a /sp fetch was queued against this sub-level while it was unloaded, the
            // sub-level has now come back online via our force-loaded chunk — execute the
            // teleport + freeze using the queued params.
            final PendingFetchManager.Entry pending = pendingFetchManager.consume(id);
            if (pending != null) {
                SableProtectMod.LOGGER.info(
                        "[sable-protect][debug]   matched pending fetch entry; dispatching now");
                FetchCommand.executePendingFetch(serverSubLevel, pending, freezeManager);
            }
            return;
        }

        // No existing claim — check whether this sub-level is the new fragment of an
        // ongoing split (pushed by our SplitListener moments before allocation).
        final SplitInheritanceQueue.Entry queued = SplitInheritanceQueue.poll(serverSubLevel.getLevel());
        if (queued == null || !queued.hasClaim()) return;

        // Defer until mass is ready; the parent claim is captured here so we don't depend on
        // Sable's transient splitFromSubLevel field, which gets cleared during the same tick.
        pending.put(id, new PendingEntry(queued.parentClaim()));
    }

    @Override
    public void onSubLevelRemoved(final SubLevel subLevel, final SubLevelRemovalReason reason) {
        pending.remove(subLevel.getUniqueId());
        // UNLOADED means the chunk was unloaded but the sub-level still exists on disk and
        // could be reloaded. We MUST keep the claim tracked across unloads so /sp myclaims,
        // /sp info, and /sp edit work for unloaded claims. Only REMOVED — the sub-level was
        // genuinely destroyed (disassembled, merged, etc.) — should drop the claim.
        if (reason == SubLevelRemovalReason.REMOVED) {
            // Audit before removeClaim wipes the registry's name/uuid mapping.
            final ClaimData claim = registry.getClaim(subLevel.getUniqueId());
            if (claim != null && subLevel instanceof ServerSubLevel ssl) {
                AuditLog.logDelete(ssl.getLevel().getServer(),
                        claim.getName(), subLevel.getUniqueId(), null, "destroyed");
            }
            registry.removeClaim(subLevel.getUniqueId());
        } else if (reason == SubLevelRemovalReason.UNLOADED
                && subLevel instanceof ServerSubLevel serverSubLevel) {
            // Snapshot the final pose so /sp info can show a correct location while the
            // sub-level is unloaded.
            final ClaimData data = registry.getClaim(subLevel.getUniqueId());
            if (data != null) {
                cacheCurrentPosition(serverSubLevel, data, subLevel.getUniqueId());
            }
        }
        freezeManager.cancel(subLevel.getUniqueId());
    }

    private void cacheCurrentPosition(final ServerSubLevel subLevel, final ClaimData data, final UUID id) {
        final var pos = subLevel.logicalPose().position();
        data.setLastKnownPosition(new net.minecraft.world.phys.Vec3(pos.x(), pos.y(), pos.z()));
        // The world chunk for force-loading is derived from the position (Sable's holding-chunk
        // map is keyed by world chunk, not by plot grid index), so we only need to record the
        // position + dimension here.
        data.setLastKnownDimension(subLevel.getLevel().dimension());
        registry.touchClaim(id);
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
        // Fragment is at its own pose / dimension — overwrite the inherited cache.
        final var pos = fragment.logicalPose().position();
        inherited.setLastKnownPosition(new net.minecraft.world.phys.Vec3(pos.x(), pos.y(), pos.z()));
        inherited.setLastKnownDimension(fragment.getLevel().dimension());
        registry.putClaim(fragment.getUniqueId(), inherited);
        ClaimData.write(fragment, inherited);

        AuditLog.logCreate(fragment.getLevel().getServer(),
                newName, fragment.getUniqueId(), null, "splitinheritance");

        SableProtectMod.LOGGER.info(
                "[sable-protect] Inherited claim '{}' from '{}' onto fragment {} (mass {})",
                newName, parentClaim.getName(), fragment.getUniqueId(), mass);
    }
}
