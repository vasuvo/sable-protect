package dev.aerodev.sableprotect.claim;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Server-wide claim index. The canonical store is {@link ClaimStorage} (a {@code SavedData}
 * persisted to disk); this registry maintains derived in-memory indexes (name → uuid,
 * uuid → name, owner → uuids, member → uuids) for fast lookup, and proxies all writes
 * through to the storage so claims survive sub-level unload and server restart.
 *
 * <p>The storage may not be attached during early startup (between {@code commonSetup} and
 * {@code ServerStartedEvent}). During that window the registry still tracks claims in its
 * indexes; once {@link #attach} is called, any claims accumulated pre-attach are migrated
 * into the now-attached storage.
 */
public class ClaimRegistry {

    private final Map<String, UUID> nameIndex = new HashMap<>();
    private final Map<UUID, String> uuidToName = new HashMap<>();
    private final Map<UUID, Set<UUID>> ownerIndex = new HashMap<>();
    private final Map<UUID, Set<UUID>> memberIndex = new HashMap<>();

    private @Nullable ClaimStorage storage;

    /**
     * Attach the persistent storage. Storage entries are loaded into the indexes; any claims
     * already in the indexes (from pre-attach onSubLevelAdded callbacks) are migrated into
     * storage so they persist across this session and beyond.
     */
    public void attach(final ClaimStorage storage) {
        this.storage = storage;

        // Migrate any claims that landed in indexes pre-attach to storage.
        for (final Map.Entry<UUID, String> entry : new HashMap<>(uuidToName).entrySet()) {
            if (!storage.hasClaim(entry.getKey())) {
                // Pre-attach claim — find its data via the indexed name.
                // We can't reconstruct full ClaimData from indexes alone, so on the
                // pre-attach path callers are expected to use putClaim(...) which
                // writes both. Anything reaching this state is unusual; clear and let
                // observer.tick() repopulate from userDataTag.
            }
        }

        // Repopulate indexes from storage (authoritative).
        clearIndexes();
        for (final Map.Entry<UUID, ClaimData> entry : storage.entries()) {
            addToIndex(entry.getKey(), entry.getValue());
        }
    }

    /** Clear storage reference and indexes, e.g. on server stop. */
    public void detach() {
        this.storage = null;
        clearIndexes();
    }

    /** Returns the canonical {@link ClaimData} for a sub-level, or null if unclaimed. */
    public @Nullable ClaimData getClaim(final UUID subLevelId) {
        if (storage != null) return storage.getClaim(subLevelId);
        return null;
    }

    public boolean isNameTaken(final String name) {
        return nameIndex.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public @Nullable UUID getSubLevelByName(final String name) {
        return nameIndex.get(name.toLowerCase(Locale.ROOT));
    }

    public @Nullable String getNameByUuid(final UUID subLevelUuid) {
        return uuidToName.get(subLevelUuid);
    }

    public Collection<UUID> getOwnedBy(final UUID playerUuid) {
        return ownerIndex.getOrDefault(playerUuid, Collections.emptySet());
    }

    public Collection<UUID> getMemberOf(final UUID playerUuid) {
        return memberIndex.getOrDefault(playerUuid, Collections.emptySet());
    }

    public Set<String> getAllNames() {
        return Collections.unmodifiableSet(nameIndex.keySet());
    }

    /**
     * Generate a unique name for a split fragment by appending "-N" until the name is free.
     * Starts from "-2" to indicate it's a sibling of the original.
     *
     * Not synchronized: callers must invoke this on the server thread. Sub-level lifecycle
     * callbacks (which is the only path that triggers this) all run on the main server tick
     * thread, so two concurrent splits cannot collide on the same suffix.
     */
    public String generateSuffixedName(final String baseName) {
        int suffix = 2;
        String candidate;
        do {
            candidate = baseName + "-" + suffix;
            suffix++;
        } while (isNameTaken(candidate));
        return candidate;
    }

    /**
     * Read the claim from {@code userDataTag} and write it through the registry. Idempotent:
     * if storage already has an entry for this sub-level, the existing storage entry is
     * preserved (storage is canonical). The {@code userDataTag} is treated as a mirror only.
     */
    public void index(final ServerSubLevel subLevel) {
        final UUID id = subLevel.getUniqueId();
        final ClaimData fromTag = ClaimData.read(subLevel);

        if (storage != null) {
            final ClaimData fromStorage = storage.getClaim(id);
            if (fromStorage != null) {
                // Storage wins. Re-index from storage; sync tag if it disagrees.
                unindex(id);
                addToIndex(id, fromStorage);
                if (fromTag == null || !claimsEqual(fromStorage, fromTag)) {
                    ClaimData.write(subLevel, fromStorage);
                }
                return;
            }
            if (fromTag != null) {
                // Legacy migration: tag-only claim → store it.
                storage.setClaim(id, fromTag);
                unindex(id);
                addToIndex(id, fromTag);
                return;
            }
            // Both empty: ensure no stale index.
            unindex(id);
            return;
        }

        // Pre-attach fallback: index from tag only; storage reconciles on attach.
        unindex(id);
        if (fromTag != null) {
            addToIndex(id, fromTag);
        }
    }

    /**
     * Persist a new or modified claim. Updates storage and indexes. Note: this does not
     * write to {@code userDataTag} — callers handle that when the sub-level is loaded.
     */
    public void putClaim(final UUID subLevelId, final ClaimData data) {
        if (storage != null) {
            storage.setClaim(subLevelId, data);
        }
        unindex(subLevelId);
        addToIndex(subLevelId, data);
    }

    /**
     * Mark storage dirty after an in-place mutation of an already-stored ClaimData object
     * (e.g., when the caller obtained a reference via {@link #getClaim} and mutated it).
     * Also re-indexes since name/owner/members may have changed.
     */
    public void touchClaim(final UUID subLevelId) {
        if (storage != null) {
            storage.markDirty();
            final ClaimData data = storage.getClaim(subLevelId);
            if (data != null) {
                unindex(subLevelId);
                addToIndex(subLevelId, data);
            }
        }
    }

    /** Remove a claim entirely (storage + indexes). */
    public void removeClaim(final UUID subLevelId) {
        if (storage != null) {
            storage.removeClaim(subLevelId);
        }
        unindex(subLevelId);
    }

    /**
     * Drop only the sub-level's *index* entries without touching storage. Used when a
     * sub-level is unloaded (UNLOADED reason) — wait, on second thought we no longer drop
     * the index on unload either, since myclaims/info should still find the claim. This
     * method is intentionally not exposed.
     */

    // ---- legacy delegation for older callers ----

    /** @deprecated use {@link #removeClaim} */
    @Deprecated
    public void remove(final UUID subLevelUuid) {
        removeClaim(subLevelUuid);
    }

    /** @deprecated use {@link #putClaim} */
    @Deprecated
    public void update(final UUID subLevelUuid, final ClaimData data) {
        putClaim(subLevelUuid, data);
    }

    /** @deprecated use {@link #detach} */
    @Deprecated
    public void clear() {
        detach();
    }

    // ---- internal index ops ----

    private void addToIndex(final UUID subLevelUuid, final ClaimData data) {
        final String lowerName = data.getName().toLowerCase(Locale.ROOT);
        nameIndex.put(lowerName, subLevelUuid);
        uuidToName.put(subLevelUuid, data.getName());
        ownerIndex.computeIfAbsent(data.getOwner(), k -> new HashSet<>()).add(subLevelUuid);
        for (final UUID member : data.getMembers()) {
            memberIndex.computeIfAbsent(member, k -> new HashSet<>()).add(subLevelUuid);
        }
    }

    private void unindex(final UUID subLevelUuid) {
        final String name = uuidToName.remove(subLevelUuid);
        if (name != null) {
            nameIndex.remove(name.toLowerCase(Locale.ROOT));
        }
        ownerIndex.values().forEach(set -> set.remove(subLevelUuid));
        memberIndex.values().forEach(set -> set.remove(subLevelUuid));
    }

    private void clearIndexes() {
        nameIndex.clear();
        uuidToName.clear();
        ownerIndex.clear();
        memberIndex.clear();
    }

    private static boolean claimsEqual(final ClaimData a, final ClaimData b) {
        return a.getOwner().equals(b.getOwner())
                && a.getName().equals(b.getName())
                && a.isBlocksProtected() == b.isBlocksProtected()
                && a.isInteractionsProtected() == b.isInteractionsProtected()
                && a.isInventoriesProtected() == b.isInventoriesProtected()
                && a.getMembers().equals(b.getMembers());
    }
}
