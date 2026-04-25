package dev.aerodev.sableprotect.lifecycle;

import dev.aerodev.sableprotect.SableProtectMod;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class ClaimObserver implements SubLevelObserver {

    /** Mass threshold below which split fragments do not inherit parent claims. */
    private static final double MIN_INHERIT_MASS = 4.0;

    private final ClaimRegistry registry;
    private final SubLevelContainer container;
    /**
     * Tracks sub-levels that were added before their userDataTag was populated.
     * These are re-checked on tick until they can be indexed or are confirmed unclaimed.
     */
    private final Set<UUID> pendingIndex = new HashSet<>();
    private int indexedOnAdd = 0;
    private int deferredTotal = 0;
    private boolean loggedSummary = false;

    public ClaimObserver(final ClaimRegistry registry, final SubLevelContainer container) {
        this.registry = registry;
        this.container = container;
    }

    @Override
    public void onSubLevelAdded(final SubLevel subLevel) {
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return;

        final ClaimData existing = ClaimData.read(serverSubLevel);
        if (existing != null) {
            registry.index(serverSubLevel);
            indexedOnAdd++;
            return;
        }

        if (tryInheritFromSplitParent(serverSubLevel)) {
            return;
        }

        // No claim and no inheritance — defer in case userDataTag is populated later (load path).
        pendingIndex.add(subLevel.getUniqueId());
    }

    /**
     * If the given sub-level was created by a split from a claimed parent, copy the parent's
     * claim onto it (with a unique suffixed name). Fragments below {@link #MIN_INHERIT_MASS}
     * are intentionally left unclaimed to prevent claimed debris.
     *
     * @return true if inheritance was applied (caller should not also defer indexing)
     */
    private boolean tryInheritFromSplitParent(final ServerSubLevel fragment) {
        final UUID parentId = fragment.getSplitFromSubLevel();
        if (parentId == null) return false;

        final SubLevel parent = container.getSubLevel(parentId);
        if (!(parent instanceof ServerSubLevel parentServer)) return false;

        final ClaimData parentData = ClaimData.read(parentServer);
        if (parentData == null) return false;

        final double mass = fragment.getMassTracker().getMass();
        if (mass < MIN_INHERIT_MASS) {
            SableProtectMod.LOGGER.debug(
                    "[sable-protect] Skipping claim inheritance for fragment {} (mass {} < {})",
                    fragment.getUniqueId(), mass, MIN_INHERIT_MASS);
            return true; // handled — do not defer
        }

        final ClaimData inherited = parentData.copy();
        final String newName = registry.generateSuffixedName(parentData.getName());
        inherited.setName(newName);
        ClaimData.write(fragment, inherited);
        registry.index(fragment);

        SableProtectMod.LOGGER.info(
                "[sable-protect] Inherited claim '{}' from parent {} onto fragment {} as '{}' (mass {})",
                parentData.getName(), parentId, fragment.getUniqueId(), newName, mass);
        return true;
    }

    @Override
    public void onSubLevelRemoved(final SubLevel subLevel, final SubLevelRemovalReason reason) {
        pendingIndex.remove(subLevel.getUniqueId());
        registry.remove(subLevel.getUniqueId());
    }

    @Override
    public void tick(final SubLevelContainer subLevels) {
        if (!pendingIndex.isEmpty()) {
            final var iterator = pendingIndex.iterator();
            while (iterator.hasNext()) {
                final UUID id = iterator.next();
                final SubLevel subLevel = subLevels.getSubLevel(id);
                if (subLevel == null) {
                    iterator.remove();
                    continue;
                }
                if (subLevel instanceof ServerSubLevel serverSubLevel) {
                    final ClaimData data = ClaimData.read(serverSubLevel);
                    if (data != null) {
                        registry.index(serverSubLevel);
                        deferredTotal++;
                        iterator.remove();
                    } else if (serverSubLevel.getUserDataTag() != null) {
                        iterator.remove();
                    }
                } else {
                    iterator.remove();
                }
            }
        }

        if (!loggedSummary && pendingIndex.isEmpty() && (indexedOnAdd > 0 || deferredTotal > 0)) {
            loggedSummary = true;
            SableProtectMod.LOGGER.info("[sable-protect] Claim indexing complete: {} immediate, {} deferred",
                    indexedOnAdd, deferredTotal);
        }
    }
}
