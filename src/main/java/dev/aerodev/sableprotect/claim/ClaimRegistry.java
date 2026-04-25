package dev.aerodev.sableprotect.claim;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class ClaimRegistry {

    private final Map<String, UUID> nameIndex = new HashMap<>();
    private final Map<UUID, String> uuidToName = new HashMap<>();
    private final Map<UUID, Set<UUID>> ownerIndex = new HashMap<>();
    private final Map<UUID, Set<UUID>> memberIndex = new HashMap<>();

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

    public void index(final ServerSubLevel subLevel) {
        final ClaimData data = ClaimData.read(subLevel);
        if (data != null) {
            addToIndex(subLevel.getUniqueId(), data);
        }
    }

    public void remove(final UUID subLevelUuid) {
        final String name = uuidToName.remove(subLevelUuid);
        if (name != null) {
            nameIndex.remove(name.toLowerCase(Locale.ROOT));
        }
        // Clean up owner and member indices
        ownerIndex.values().forEach(set -> set.remove(subLevelUuid));
        memberIndex.values().forEach(set -> set.remove(subLevelUuid));
    }

    public void update(final UUID subLevelUuid, final ClaimData data) {
        remove(subLevelUuid);
        addToIndex(subLevelUuid, data);
    }

    private void addToIndex(final UUID subLevelUuid, final ClaimData data) {
        final String lowerName = data.getName().toLowerCase(Locale.ROOT);
        nameIndex.put(lowerName, subLevelUuid);
        uuidToName.put(subLevelUuid, data.getName());
        ownerIndex.computeIfAbsent(data.getOwner(), k -> new HashSet<>()).add(subLevelUuid);
        for (final UUID member : data.getMembers()) {
            memberIndex.computeIfAbsent(member, k -> new HashSet<>()).add(subLevelUuid);
        }
    }

    public void clear() {
        nameIndex.clear();
        uuidToName.clear();
        ownerIndex.clear();
        memberIndex.clear();
    }
}
