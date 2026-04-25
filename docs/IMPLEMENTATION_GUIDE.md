# sable-protect Implementation Guide

This document covers the Sable internals and integration patterns relevant to building an airship claim and protection system. It is based on research into Sable, sable-companion, Simulated-Project, and (with caveats) ftbchunksaerospace.

---

## Table of Contents

1. [Sub-Level Fundamentals](#1-sub-level-fundamentals)
2. [Identifying and Tracking Sub-Levels](#2-identifying-and-tracking-sub-levels)
3. [Storing Claim Data](#3-storing-claim-data)
4. [Events and Hooks for Protection](#4-events-and-hooks-for-protection)
5. [Blocking Player Actions](#5-blocking-player-actions)
6. [Explosions](#6-explosions)
7. [Assembly and Disassembly](#7-assembly-and-disassembly)
8. [Splitting and Merging](#8-splitting-and-merging)
9. [Additional Scenarios](#9-additional-scenarios)
10. [ftbchunksaerospace as Reference](#10-ftbchunksaerospace-as-reference)
11. [Open Design Questions](#11-open-design-questions)

---

## 1. Sub-Level Fundamentals

A Sable "sub-level" is an independently positioned and oriented set of Minecraft chunks that floats within a parent `Level`. Blocks inside a sub-level are stored in a **plot** — a region of the chunk grid reserved for that structure. The sub-level has a **pose** (position + quaternion orientation + scale) that maps between local (plot) coordinates and world coordinates.

**Key classes:**

| Class | Location | Role |
|---|---|---|
| `SubLevel` | `sable/common/.../sublevel/SubLevel.java` | Abstract base — UUID, name, pose, plot |
| `ServerSubLevel` | `sable/common/.../sublevel/ServerSubLevel.java` | Server-side — physics, tracking players, userDataTag |
| `SubLevelContainer` | `sable/api/sublevel/SubLevelContainer.java` | Manages all sub-levels in a `Level` |
| `LevelPlot` / `ServerLevelPlot` | `sable/common/.../sublevel/plot/` | Chunk-level storage for a single sub-level |
| `SubLevelAssemblyHelper` | `sable/api/SubLevelAssemblyHelper.java` | Assembles/disassembles blocks into/from sub-levels |

**Important properties of a `ServerSubLevel`:**
- `UUID getUniqueId()` — persistent across saves and reloads; the primary stable identifier
- `String getName()` / `setName(String)` — display name (nullable)
- `Pose3d logicalPose()` — current position/orientation in the world
- `CompoundTag getUserDataTag()` / `setUserDataTag(CompoundTag)` — **custom mod data, persisted automatically**
- `UUID getSplitFromSubLevel()` — UUID of the sub-level this was split from (if any)
- `Set<UUID> getTrackingPlayers()` — players currently seeing/interacting with this sub-level
- `MassTracker getMassTracker()` — mass, center of mass

---

## 2. Identifying and Tracking Sub-Levels

### Looking up which sub-level a block is in

The primary API for this is `Sable.HELPER` (direct Sable dependency) or `SableCompanion.INSTANCE` (lightweight shim):

```java
// Direct Sable API (compile-only, requires Sable at runtime)
SubLevel subLevel = Sable.HELPER.getContaining(level, blockPos);

// Sable Companion API (safe no-op fallback when Sable absent)
SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, blockPos);
```

Both return `null` if the position is not inside any sub-level.

**Other query methods on `SableCompanion.INSTANCE`:**
- `getContaining(Entity entity)` / `getContaining(BlockEntity blockEntity)`
- `getTrackingSubLevel(Entity entity)` — the sub-level an entity is "riding" on
- `getTrackingOrVehicleSubLevel(Entity entity)` — tracking or passenger
- `isInPlotGrid(level, pos)` — quick check if coordinates fall in the plot grid at all
- `getAllIntersecting(level, bounds)` — all sub-levels overlapping a bounding box

### Coordinate transforms

Sub-level blocks have **local** (plot) positions that differ from their **world** positions. To convert:

```java
// Local → World
Pose3dc pose = subLevel.logicalPose();
Vector3d worldPos = pose.transformPosition(
    new Vector3d(localPos.getX() + 0.5, localPos.getY() + 0.5, localPos.getZ() + 0.5),
    new Vector3d()
);

// World → Local
Vector3d localPos = pose.transformPositionInverse(worldVec, new Vector3d());
```

This is critical for protection: NeoForge block events fire with **local** (plot) positions, but claim checks may need the **world** position (e.g., to determine which overworld region the ship is flying over).

### Observing sub-level creation and destruction

Register a `SubLevelObserver` on the container:

```java
SableEventPlatform.INSTANCE.onSubLevelContainerReady((level, container) -> {
    container.addObserver(new SubLevelObserver() {
        @Override
        public void onSubLevelAdded(SubLevel subLevel) {
            // New sub-level appeared (created, loaded, or split)
        }

        @Override
        public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
            // Sub-level going away
            // reason: UNLOADED (dimension unload) or REMOVED (destroyed/disassembled)
        }

        @Override
        public void tick(SubLevelContainer subLevels) {
            // Per-tick, e.g., for periodic claim validation
        }
    });
});
```

---

## 3. Storing Claim Data

### userDataTag (recommended approach)

`ServerSubLevel` has a `CompoundTag userDataTag` field that is automatically serialized and deserialized with the sub-level. This is the intended extension point for mods to attach custom data.

```java
ServerSubLevel subLevel = ...;
CompoundTag tag = subLevel.getUserDataTag();
if (tag == null) tag = new CompoundTag();

// Store claim data
tag.putUUID("sableprotect:owner", ownerUUID);
tag.putLong("sableprotect:claimed_at", System.currentTimeMillis());

// Store allies as a list
ListTag allies = new ListTag();
for (UUID ally : allySet) {
    CompoundTag entry = new CompoundTag();
    entry.putUUID("uuid", ally);
    allies.add(entry);
}
tag.put("sableprotect:allies", allies);

subLevel.setUserDataTag(tag);
// Automatically saved — no extra persistence work needed
```

**Advantages:**
- Zero external storage (no sidecar files, no database)
- Travels with the sub-level through saves, dimension changes, etc.
- Survives server restarts

**Considerations:**
- Other mods may also write to `userDataTag` — namespace your keys (e.g., `sableprotect:`)
- The tag is `null` by default — always null-check
- `getUserDataTag()` returns the actual reference, not a copy — modifications are live

---

## 4. Events and Hooks for Protection

### NeoForge events for player actions

These are standard NeoForge events that fire for **all** block interactions, including those on sub-levels. The `BlockPos` in these events is the **local** (plot) position within the sub-level.

| Event | Fires when | Cancel method |
|---|---|---|
| `PlayerInteractEvent.RightClickBlock` | Player right-clicks a block | `setCanceled(true)` + `setCancellationResult(FAIL)` |
| `PlayerInteractEvent.LeftClickBlock` | Player starts mining a block | `setCanceled(true)` |
| `BlockEvent.BreakEvent` | Block is about to be broken | `setCanceled(true)` |
| `BlockEvent.EntityPlaceEvent` | Block is about to be placed | `setCanceled(true)` |
| `ExplosionEvent.Detonate` | Explosion is about to damage blocks | Remove blocks from `getAffectedBlocks()` |
| `PlayerInteractEvent.EntityInteract` | Player right-clicks an entity | `setCanceled(true)` |
| `AttackEntityEvent` | Player attacks an entity | `setCanceled(true)` |

### Pattern for checking protection

```java
@SubscribeEvent
public void onBreakBlock(BlockEvent.BreakEvent event) {
    if (!(event.getPlayer() instanceof ServerPlayer player)) return;

    // Get the sub-level this block is in (local/plot coords)
    SubLevel subLevel = Sable.HELPER.getContaining(player.level(), event.getPos());
    if (subLevel == null) return; // Not on a sub-level, not our problem

    // Check claim
    if (!isAllowed(player, (ServerSubLevel) subLevel)) {
        event.setCanceled(true);
        player.displayClientMessage(
            Component.translatable("sableprotect.denied"), true);
    }
}
```

### Sable-specific hooks

| Hook | Registration | Fires when |
|---|---|---|
| `SableSubLevelContainerReadyEvent` | `SableEventPlatform.INSTANCE.onSubLevelContainerReady(...)` | Sable finishes init for a level |
| `SubLevelObserver` | `container.addObserver(...)` | Sub-level added, removed, or tick |
| `SplitListener` | `SubLevelHeatMapManager.addSplitListener(...)` | Sub-level splits into pieces |
| `SablePrePhysicsTickEvent` | `SableEventPlatform.INSTANCE.onPhysicsTick(...)` | Before physics step |
| `SablePostPhysicsTickEvent` | `SableEventPlatform.INSTANCE.onPostPhysicsTick(...)` | After physics step |

---

## 5. Blocking Player Actions

### Block interaction (use, mine, break, place)

Subscribe to the NeoForge events listed above. For each event:

1. Get the `BlockPos` from the event (this is the **local/plot** position)
2. Call `Sable.HELPER.getContaining(level, blockPos)` to find the sub-level
3. If the block is in a sub-level, read the claim from `userDataTag`
4. If the player is not the owner or an ally, cancel the event

### Entity interaction

Players interacting with entities (item frames, armor stands, animals, etc.) on a sub-level:
- `PlayerInteractEvent.EntityInteract` — right-click
- `AttackEntityEvent` — left-click/attack

For these, determine which sub-level the **entity** is on:
```java
SubLevel subLevel = Sable.HELPER.getContaining(entity);
// or: SableCompanion.INSTANCE.getTrackingSubLevel(entity);
```

### Item use near sub-levels

Some actions (e.g., shooting projectiles, using ender pearls, throwing potions) aren't block events. Consider:
- `PlayerInteractEvent.RightClickItem` — for items used while looking at a sub-level
- Projectile impact events — to prevent arrow/trident damage to blocks on claimed sub-levels

---

## 6. Explosions

### How Sable handles explosions

Sable intercepts `Explosion.explode()` via `ExplosionMixin` to calculate blast damage against sub-level blocks and apply impulse forces to the sub-level's rigid body. This happens internally — there is no public event for it.

### Protecting against explosions

Subscribe to `ExplosionEvent.Detonate` and filter the affected block list:

```java
@SubscribeEvent
public void onExplosion(ExplosionEvent.Detonate event) {
    event.getAffectedBlocks().removeIf(blockPos -> {
        SubLevel subLevel = Sable.HELPER.getContaining(event.getLevel(), blockPos);
        if (subLevel == null) return false;

        // Remove this block from explosion if the sub-level is claimed
        // and the source entity is not allowed
        return isClaimed((ServerSubLevel) subLevel)
            && !isExplosionSourceAllowed(event.getExplosion(), (ServerSubLevel) subLevel);
    });
}
```

**Caveat:** This removes blocks from the damage list, but the physics impulse from the explosion may still push the sub-level. Preventing the force itself would require hooking into the physics pipeline, which is much more invasive.

---

## 7. Assembly and Disassembly

### How assembly works

Assembly is triggered by the **Physics Assembler** block (from Simulated-Project). It:
1. Discovers connected blocks via BFS flood-fill (`SimAssemblyContraption.searchMovedStructure()`)
2. Calls `SubLevelAssemblyHelper.assembleBlocks()` to move those blocks into a new sub-level
3. The new sub-level gets a fresh UUID and an empty `userDataTag`

**Relevance to claims:** A newly assembled sub-level is unclaimed. You could:
- Auto-claim it for the player who triggered assembly
- Listen for `onSubLevelAdded` in the observer and prompt the player

### How disassembly works

Disassembly is also triggered by the Physics Assembler. It:
1. Aligns the sub-level back to the grid using physics constraints
2. Calls `SimAssemblyHelper.disassembleSubLevel()` to move blocks back to the world
3. The sub-level is then removed (`SubLevelRemovalReason.REMOVED`)

**Preventing disassembly of claimed ships:**

There is no built-in Sable event for "about to disassemble." Simulated-Project's `PhysicsAssemblerBlockEntity` drives the process. Options:

1. **Block the Physics Assembler interaction** — if a player right-clicks a Physics Assembler that's on a claimed sub-level and they're not allowed, cancel the `RightClickBlock` event. This is the simplest approach.

2. **Mixin into SimAssemblyHelper.disassembleSubLevel()** — inject a check at the start. More reliable but couples to Simulated-Project internals.

3. **Listen for `onSubLevelRemoved` with reason `REMOVED`** — but this fires *after* the fact, so you can only log it, not prevent it.

**Recommendation:** Option 1 (block the assembler interaction) is cleanest. The Physics Assembler is the only standard way to disassemble, so protecting it covers the main case.

---

## 8. Splitting and Merging

### Splitting

When blocks are destroyed on a sub-level, the heatmap manager (`SubLevelHeatMapManager`) runs a flood-fill to detect disconnected regions. If the remaining blocks form multiple separate groups, the sub-level **splits**:

1. The largest group stays in the original sub-level (same UUID)
2. Each smaller group becomes a new sub-level via `SubLevelAssemblyHelper.assembleBlocks()`
3. The new sub-levels have `getSplitFromSubLevel()` set to the parent's UUID
4. `SplitListener.addBlocks()` fires for each new piece
5. `SubLevelObserver.onSubLevelAdded()` fires for each new sub-level

**Claim implications:**

When a claimed sub-level splits:
- The original UUID retains its claim data (it's the same sub-level)
- New fragments get fresh UUIDs with empty `userDataTag` — **claim data is NOT inherited**
- `getSplitFromSubLevel()` on the new fragments returns the parent UUID

**Recommended handling:**
```java
@Override
public void onSubLevelAdded(SubLevel subLevel) {
    if (subLevel instanceof ServerSubLevel server) {
        UUID parentId = server.getSplitFromSubLevel();
        if (parentId != null) {
            // This sub-level was split from a claimed parent
            // Inherit the parent's claim data
            SubLevel parent = container.getSubLevel(parentId);
            if (parent instanceof ServerSubLevel parentServer) {
                inheritClaim(parentServer, server);
            }
        }
    }
}
```

**Edge case — split fragment has zero mass:** Sable automatically destroys zero-mass fragments (calls `destroyAllBlocks()` + removes the sub-level). No claim action needed — the observer will fire `onSubLevelRemoved`.

### Merging

Merging is handled by the **Merging Glue** block (from Simulated-Project). When two sub-levels collide and their merging glue blocks make contact:

1. A `FixedConstraint` is added between the two sub-levels in the physics pipeline
2. Over ~10 ticks the positions/orientations lerp together
3. The **lighter** sub-level is disassembled into the **heavier** one
4. The lighter sub-level is removed (`onSubLevelRemoved` fires)
5. The heavier sub-level now contains all blocks from both

**Claim implications:**

- The surviving sub-level keeps its claim data
- The absorbed sub-level's claim data is lost (it's removed)
- If the two sub-levels had **different owners**, this creates a conflict

**Recommended handling options:**
1. **Block merging between differently-owned sub-levels** — would require a mixin into `MergingGlueBlockEntity` physics tick
2. **Auto-resolve to the heavier (surviving) sub-level's owner** — simplest, may surprise players
3. **Notify both owners and require manual resolution** — most correct but complex
4. **Prevent merging glue placement on claimed sub-levels entirely** — heavy-handed but safe

---

## 9. Additional Scenarios

### Redstone and mechanical interactions

Players may try to damage a claimed ship indirectly:
- Pistons pushing blocks off a sub-level
- TNT cannons from outside
- Dispensers placing/breaking blocks
- Hoppers extracting items from containers on the sub-level

Some of these will fire block events (and can be caught), others will not. Consider which indirect interactions you want to protect against.

### Player boarding/ejection

Should unclaimed players be allowed to **stand on** a claimed sub-level? Walk around on deck? This is tracked by Sable:
- `subLevel.getTrackingPlayers()` — set of player UUIDs currently on this sub-level
- `Sable.HELPER.getTrackingSubLevel(entity)` — which sub-level an entity is riding on

You could optionally:
- Eject unauthorized players from the sub-level
- Prevent unauthorized players from boarding
- Allow boarding but deny all block/entity interactions

### Container access

Chests, barrels, furnaces, etc. on claimed sub-levels should be protected. These fire as `RightClickBlock` events and are caught by the standard interaction handler. However, consider:
- Hopper minecarts or other automation reaching into containers
- Comparator reads from outside the sub-level (generally harmless)

### Projectile and entity damage

Arrows, fireballs, tridents, and other projectiles hitting blocks on a sub-level:
- Block damage from projectiles may or may not fire standard block events depending on the projectile type
- Entity damage to mobs/animals on a claimed sub-level

### Sub-level name as claim indicator

`ServerSubLevel.setName(String)` sets a display name that is synced to clients. This could be used to show claim status visually (e.g., prefix the ship name with the owner's name or a faction tag).

### Persistence edge cases

- **Server restart:** `userDataTag` persists — claims survive restarts
- **Dimension change:** Sub-levels don't change dimensions in normal gameplay, but if they did, the UUID and tag would persist
- **Chunk unloading:** Sub-level is removed with reason `UNLOADED`, then re-added when loaded again — same UUID, same tag. Claims persist.
- **World backup restore:** Claims are embedded in the sub-level data, so they are restored with the world. No external database to desync.

---

## 10. ftbchunksaerospace as Reference

ftbchunksaerospace (`reference/ftbchunksaerospace/`) is a working protection mod that integrates Sable with FTB Chunks. It demonstrates the general NeoForge event pattern, but has notable issues:

### What it does well
- Clean event subscription pattern for `RightClickBlock`, `LeftClickBlock`, `BreakEvent`, `EntityPlaceEvent`, `ExplosionEvent.Detonate`
- Correct use of `Sable.HELPER.getContaining()` to resolve sub-level membership
- Coordinate transform from local to world via `subLevel.logicalPose().transformPosition()`

### Known issues and gaps (treat its approaches with skepticism)
- **No fluid protection** — bucket-placed fluids may bypass `EntityPlaceEvent`
- **Incomplete explosion handling** — only checks team-level explosion toggle, not per-player authorization
- **Free zone logic checks physical Y before resolving world Y** — could mismatch when sub-levels are rotated
- **No entity interaction protection** — doesn't handle `EntityInteract` or `AttackEntityEvent`
- **No assembly/disassembly protection** — ships can be disassembled inside claims
- **No handling of sub-level splitting or merging**
- **TNT ignition check uses physical block pos** — could check the wrong block on a rotated sub-level
- **No hopper/automation protection**
- **Missing protection types** — only uses `EDIT_AND_INTERACT_BLOCK` and `EDIT_BLOCK`, omits `ATTACH_BLOCK`, `DAMAGE_ENTITY`, `SPAWN_ENTITY`

### The position resolution pattern (correct, reusable)

```java
// ftbchunksaerospace/SableFtbChunksCompat.java:209-215
static BlockPos resolveWorldBlockPos(SubLevel subLevel, BlockPos physicalBlockPos) {
    Vector3d worldCenter = subLevel.logicalPose().transformPosition(new Vector3d(
            physicalBlockPos.getX() + 0.5,
            physicalBlockPos.getY() + 0.5,
            physicalBlockPos.getZ() + 0.5));
    return BlockPos.containing(worldCenter.x(), worldCenter.y(), worldCenter.z());
}
```

This is the correct way to go from a plot-local `BlockPos` to a world `BlockPos`. The `+ 0.5` centers on the block before transforming. This pattern is sound and reusable.

---

## 11. Open Design Questions

These are decisions to make before implementation:

1. **Claim scope:** Does claiming protect the entire sub-level, or can individual regions/blocks be marked?
   - Whole sub-level is simpler and matches Sable's data model (`userDataTag` is per sub-level)

2. **Auto-claim on assembly:** Should the player who assembles a sub-level automatically become its owner?

3. **Ally system granularity:** Should allies have the same permissions as the owner, or should there be permission tiers (e.g., can interact but not break/place)?

4. **Split inheritance:** When a sub-level splits, should fragments automatically inherit the parent's claim? (Recommended: yes, via `getSplitFromSubLevel()`)

5. **Merge conflict resolution:** What happens when two differently-owned sub-levels merge?

6. **Boarding policy:** Can non-allies stand on a claimed sub-level? If so, what can they do?

7. **Offline owner protection:** Should claims persist and protect when the owner is offline? (Recommended: yes — `userDataTag` persists regardless)

8. **Claim limits:** Should there be a max number of sub-levels a player can claim?

9. **Abandonment:** Should unclaimed sub-levels after a split auto-expire? Should there be a command to unclaim?

10. **Admin override:** Should ops or a permission node allow bypassing claim protection?

---

## API Summary — Quick Reference

### Sable (direct, compile-only)

```java
// Get sub-level at position
SubLevel sub = Sable.HELPER.getContaining(level, blockPos);
SubLevel sub = Sable.HELPER.getContaining(entity);

// Get sub-level an entity is riding on
SubLevel sub = Sable.HELPER.getTrackingOrVehicleSubLevel(entity);

// Project position out of sub-level to world
Vec3 worldPos = Sable.HELPER.projectOutOfSubLevel(level, localPos);
```

### ServerSubLevel (server-side)

```java
UUID id = subLevel.getUniqueId();        // Persistent identity
CompoundTag tag = subLevel.getUserDataTag();  // Custom mod data
subLevel.setUserDataTag(tag);                 // Persists automatically
UUID parent = subLevel.getSplitFromSubLevel(); // Split parent (nullable)
String name = subLevel.getName();             // Display name
subLevel.setName("Claimed Ship");
Set<UUID> players = subLevel.getTrackingPlayers();
```

### Registration

```java
// Listen for container ready (mod init)
SableEventPlatform.INSTANCE.onSubLevelContainerReady((level, container) -> {
    container.addObserver(myObserver);
});

// Listen for splits
SubLevelHeatMapManager.addSplitListener((level, bounds, blocks) -> { ... });
```
