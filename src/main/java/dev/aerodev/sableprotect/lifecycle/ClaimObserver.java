package dev.aerodev.sableprotect.lifecycle;

import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;

public class ClaimObserver implements SubLevelObserver {

    private final ClaimRegistry registry;

    public ClaimObserver(final ClaimRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onSubLevelAdded(final SubLevel subLevel) {
        if (subLevel instanceof ServerSubLevel serverSubLevel) {
            registry.index(serverSubLevel);
        }
    }

    @Override
    public void onSubLevelRemoved(final SubLevel subLevel, final SubLevelRemovalReason reason) {
        registry.remove(subLevel.getUniqueId());
    }

    @Override
    public void tick(final SubLevelContainer subLevels) {
        // No per-tick work needed in Phase 1
    }
}
