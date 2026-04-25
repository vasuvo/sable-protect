package dev.aerodev.sableprotect.claim;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-wide persistent store of all known claims, keyed by sub-level UUID. Persists to
 * {@code <world>/data/sableprotect_claims.dat} via Minecraft's {@link SavedData} mechanism.
 *
 * <p>This is the canonical source of truth for claim data — survives sub-level unload,
 * dimension changes, and server restarts. Each {@link ServerSubLevel}'s {@code userDataTag}
 * is kept in sync as a mirror, but on conflict the storage wins (e.g., when a claim is
 * edited while its sub-level is unloaded, the next time the sub-level loads its tag is
 * updated from storage).
 */
public final class ClaimStorage extends SavedData {

    public static final String FILE_ID = "sableprotect_claims";

    private static final String CLAIMS_KEY = "claims";
    private static final String SUB_LEVEL_KEY = "subLevel";
    private static final String DATA_KEY = "data";

    private final Map<UUID, ClaimData> claims = new HashMap<>();

    public ClaimStorage() {}

    public static SavedData.Factory<ClaimStorage> factory() {
        return new SavedData.Factory<>(ClaimStorage::new, ClaimStorage::load);
    }

    public static ClaimStorage load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final ClaimStorage storage = new ClaimStorage();
        final ListTag list = tag.getList(CLAIMS_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag entry = list.getCompound(i);
            final UUID id = NbtUtils.loadUUID(entry.get(SUB_LEVEL_KEY));
            final ClaimData data = ClaimData.deserialize(entry.getCompound(DATA_KEY));
            storage.claims.put(id, data);
        }
        return storage;
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        final ListTag list = new ListTag();
        for (final Map.Entry<UUID, ClaimData> entry : claims.entrySet()) {
            final CompoundTag entryTag = new CompoundTag();
            entryTag.put(SUB_LEVEL_KEY, NbtUtils.createUUID(entry.getKey()));
            entryTag.put(DATA_KEY, entry.getValue().serialize());
            list.add(entryTag);
        }
        tag.put(CLAIMS_KEY, list);
        return tag;
    }

    public @Nullable ClaimData getClaim(final UUID subLevelId) {
        return claims.get(subLevelId);
    }

    public boolean hasClaim(final UUID subLevelId) {
        return claims.containsKey(subLevelId);
    }

    public void setClaim(final UUID subLevelId, final ClaimData data) {
        claims.put(subLevelId, data);
        setDirty();
    }

    public void removeClaim(final UUID subLevelId) {
        if (claims.remove(subLevelId) != null) {
            setDirty();
        }
    }

    public Set<Map.Entry<UUID, ClaimData>> entries() {
        return Collections.unmodifiableSet(claims.entrySet());
    }

    /** Mark dirty after an in-place mutation of a stored ClaimData (e.g., setName, addMember). */
    public void markDirty() {
        setDirty();
    }
}
