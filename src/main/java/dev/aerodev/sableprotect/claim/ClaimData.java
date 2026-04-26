package dev.aerodev.sableprotect.claim;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class ClaimData {

    private static final String ROOT_KEY = "sableprotect";
    private static final String OWNER_KEY = "owner";
    private static final String NAME_KEY = "name";
    private static final String MEMBERS_KEY = "members";
    private static final String UUID_KEY = "uuid";
    private static final String FLAGS_KEY = "flags";
    private static final String BLOCKS_KEY = "blocks";
    private static final String INTERACTIONS_KEY = "interactions";
    private static final String INVENTORIES_KEY = "inventories";
    private static final String LAST_POS_KEY = "lastPos";
    private static final String LAST_POS_X = "x";
    private static final String LAST_POS_Y = "y";
    private static final String LAST_POS_Z = "z";

    private UUID owner;
    private String name;
    private final Set<UUID> members;
    private boolean blocksProtected;
    private boolean interactionsProtected;
    private boolean inventoriesProtected;
    /** World-space position last seen for the sub-level. Null if never observed. */
    private @Nullable Vec3 lastKnownPosition;

    public ClaimData(final UUID owner, final String name) {
        this.owner = owner;
        this.name = name;
        this.members = new HashSet<>();
        this.blocksProtected = true;
        this.interactionsProtected = true;
        this.inventoriesProtected = true;
    }

    private ClaimData(final UUID owner, final String name, final Set<UUID> members,
                      final boolean blocksProtected, final boolean interactionsProtected,
                      final boolean inventoriesProtected) {
        this.owner = owner;
        this.name = name;
        this.members = members;
        this.blocksProtected = blocksProtected;
        this.interactionsProtected = interactionsProtected;
        this.inventoriesProtected = inventoriesProtected;
    }

    public UUID getOwner() {
        return owner;
    }

    public void setOwner(final UUID owner) {
        this.owner = owner;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public Set<UUID> getMembers() {
        return members;
    }

    public boolean isBlocksProtected() {
        return blocksProtected;
    }

    public void setBlocksProtected(final boolean blocksProtected) {
        this.blocksProtected = blocksProtected;
    }

    public boolean isInteractionsProtected() {
        return interactionsProtected;
    }

    public void setInteractionsProtected(final boolean interactionsProtected) {
        this.interactionsProtected = interactionsProtected;
    }

    public boolean isInventoriesProtected() {
        return inventoriesProtected;
    }

    public void setInventoriesProtected(final boolean inventoriesProtected) {
        this.inventoriesProtected = inventoriesProtected;
    }

    public ClaimRole getRole(final UUID playerUuid) {
        if (playerUuid.equals(owner)) {
            return ClaimRole.OWNER;
        }
        if (members.contains(playerUuid)) {
            return ClaimRole.MEMBER;
        }
        return ClaimRole.DEFAULT;
    }

    public @Nullable Vec3 getLastKnownPosition() {
        return lastKnownPosition;
    }

    public void setLastKnownPosition(@Nullable final Vec3 position) {
        this.lastKnownPosition = position;
    }

    public CompoundTag serialize() {
        final CompoundTag root = new CompoundTag();
        root.putUUID(OWNER_KEY, owner);
        root.putString(NAME_KEY, name);

        final ListTag memberList = new ListTag();
        for (final UUID member : members) {
            final CompoundTag entry = new CompoundTag();
            entry.putUUID(UUID_KEY, member);
            memberList.add(entry);
        }
        root.put(MEMBERS_KEY, memberList);

        final CompoundTag flags = new CompoundTag();
        flags.putBoolean(BLOCKS_KEY, blocksProtected);
        flags.putBoolean(INTERACTIONS_KEY, interactionsProtected);
        flags.putBoolean(INVENTORIES_KEY, inventoriesProtected);
        root.put(FLAGS_KEY, flags);

        if (lastKnownPosition != null) {
            final CompoundTag pos = new CompoundTag();
            pos.putDouble(LAST_POS_X, lastKnownPosition.x);
            pos.putDouble(LAST_POS_Y, lastKnownPosition.y);
            pos.putDouble(LAST_POS_Z, lastKnownPosition.z);
            root.put(LAST_POS_KEY, pos);
        }

        return root;
    }

    public static ClaimData deserialize(final CompoundTag root) {
        final UUID owner = root.getUUID(OWNER_KEY);
        final String name = root.getString(NAME_KEY);

        final Set<UUID> members = new HashSet<>();
        final ListTag memberList = root.getList(MEMBERS_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < memberList.size(); i++) {
            members.add(memberList.getCompound(i).getUUID(UUID_KEY));
        }

        final CompoundTag flags = root.getCompound(FLAGS_KEY);
        final boolean blocksProtected = !flags.contains(BLOCKS_KEY) || flags.getBoolean(BLOCKS_KEY);
        final boolean interactionsProtected = !flags.contains(INTERACTIONS_KEY) || flags.getBoolean(INTERACTIONS_KEY);
        final boolean inventoriesProtected = !flags.contains(INVENTORIES_KEY) || flags.getBoolean(INVENTORIES_KEY);

        final ClaimData data = new ClaimData(owner, name, members,
                blocksProtected, interactionsProtected, inventoriesProtected);
        if (root.contains(LAST_POS_KEY, Tag.TAG_COMPOUND)) {
            final CompoundTag pos = root.getCompound(LAST_POS_KEY);
            data.lastKnownPosition = new Vec3(
                    pos.getDouble(LAST_POS_X),
                    pos.getDouble(LAST_POS_Y),
                    pos.getDouble(LAST_POS_Z));
        }
        return data;
    }

    public static @Nullable ClaimData read(final ServerSubLevel subLevel) {
        final CompoundTag tag = subLevel.getUserDataTag();
        if (tag == null || !tag.contains(ROOT_KEY, Tag.TAG_COMPOUND)) {
            return null;
        }
        return deserialize(tag.getCompound(ROOT_KEY));
    }

    public static void write(final ServerSubLevel subLevel, final ClaimData data) {
        CompoundTag tag = subLevel.getUserDataTag();
        if (tag == null) {
            tag = new CompoundTag();
        }
        tag.put(ROOT_KEY, data.serialize());
        subLevel.setUserDataTag(tag);
    }

    public static void clear(final ServerSubLevel subLevel) {
        final CompoundTag tag = subLevel.getUserDataTag();
        if (tag != null) {
            tag.remove(ROOT_KEY);
            subLevel.setUserDataTag(tag);
        }
    }

    public ClaimData copy() {
        final ClaimData c = new ClaimData(owner, name, new HashSet<>(members),
                blocksProtected, interactionsProtected, inventoriesProtected);
        c.lastKnownPosition = lastKnownPosition;
        return c;
    }
}
