# sable-protect Technical Specification

This document defines the technical architecture, data model, and phased implementation plan for sable-protect. It maps the design goals from `DESIGN.md` to concrete code structures, Sable API usage, and NeoForge integration points.

**Related documents:**
- [DESIGN.md](DESIGN.md) — feature requirements and UX
- [IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md) — Sable API reference and patterns

> **Maintenance:** When adding, changing, or removing features, update both this spec and DESIGN.md to reflect the current state. Command additions must appear in the command tree (section 5), package structure (section 1), the relevant phase deliverables (section 10), and the Commands section of DESIGN.md.

---

## Table of Contents

1. [Package Structure](#1-package-structure)
2. [Data Model](#2-data-model)
3. [Claim Registry](#3-claim-registry)
4. [Protection Event Handlers](#4-protection-event-handlers)
5. [Command Tree](#5-command-tree)
6. [Sub-Level Lifecycle Handling](#6-sub-level-lifecycle-handling)
7. [Physics Freeze (Fetch)](#7-physics-freeze-fetch)
8. [Chat Info Window](#8-chat-info-window)
9. [Configuration](#9-configuration)
10. [Implementation Phases](#10-implementation-phases)

---

## 1. Package Structure

```
dev.aerodev.sableprotect/
├── SableProtectMod.java            # @Mod entry point, event bus registration
├── claim/
│   ├── ClaimData.java              # Per-sub-level claim state (serialized to userDataTag)
│   ├── ClaimRegistry.java          # Server-wide name→UUID index, lifecycle management
│   └── ClaimRole.java              # Enum: OWNER, MEMBER, DEFAULT
├── protection/
│   ├── BlockProtectionHandler.java # Block place/break + explosion events
│   ├── InteractionProtectionHandler.java # RightClickBlock, entity interact events
│   ├── InventoryProtectionHandler.java   # Container-specific interaction filtering
│   └── DisassemblyProtectionHandler.java # Physics assembler + merging glue
├── command/
│   ├── SpCommand.java              # Root /sp command registration
│   ├── ClaimCommand.java           # /sp claim
│   ├── ClaimUuidCommand.java       # /sp claimuuid (OP-only, claim by UUID)
│   ├── InfoCommand.java            # /sp info, builds chat component
│   ├── EditCommand.java            # /sp edit (toggles, rename, changeowner, members)
│   ├── UnclaimCommand.java         # /sp unclaim + confirmation
│   ├── LocateCommand.java          # /sp locate
│   ├── FetchCommand.java           # /sp fetch + physics freeze
│   ├── MyClaimsCommand.java        # /sp myclaims
│   └── DebugCommand.java           # /sp debug (OP-only, toggle debug output)
├── lifecycle/
│   └── ClaimObserver.java          # SubLevelObserver for add/remove tracking
└── util/
    ├── SubLevelLookup.java         # Physics-based spatial lookup for targeting sub-levels
    └── DebugHelper.java            # Per-player debug toggle state
```

---

## 2. Data Model

### ClaimData (stored in `ServerSubLevel.userDataTag`)

All claim state is stored directly in Sable's `userDataTag` CompoundTag on each sub-level. This persists automatically with the sub-level — no external database or sidecar files.

**NBT structure under the `sableprotect` namespace:**

```
sableprotect:
  owner: UUID            # The claiming player's UUID
  name: String           # Globally unique display name
  members: ListTag       # List of member UUIDs
    - { uuid: UUID }
  flags:
    blocks: boolean      # true = protected (default: true)
    interactions: boolean # true = protected (default: true)
    inventories: boolean # true = protected (default: true)
```

**Java representation:**

```java
public class ClaimData {
    private UUID owner;
    private String name;
    private Set<UUID> members;
    private boolean blocksProtected;
    private boolean interactionsProtected;
    private boolean inventoriesProtected;

    // Serialization
    public CompoundTag serialize();
    public static ClaimData deserialize(CompoundTag tag);

    // Read from / write to a ServerSubLevel's userDataTag
    public static @Nullable ClaimData read(ServerSubLevel subLevel);
    public static void write(ServerSubLevel subLevel, ClaimData data);
    public static void clear(ServerSubLevel subLevel);

    // Role resolution
    public ClaimRole getRole(UUID playerUuid);
}
```

**Key behaviors:**
- `read()` returns `null` for unclaimed sub-levels (no `sableprotect` compound in tag)
- `write()` creates the `sableprotect` compound if absent, or overwrites it
- `clear()` removes the `sableprotect` compound entirely
- All keys are prefixed with `sableprotect:` to avoid collisions with other mods using `userDataTag`

### ClaimRole

```java
public enum ClaimRole {
    OWNER,    // Full control including disassembly
    MEMBER,   // Bypasses configurable toggles, can locate/fetch
    DEFAULT   // Subject to toggle restrictions
}
```

---

## 3. Claim Registry

The registry is a server-side in-memory index that maps claim names to sub-level UUIDs. It is **not** persisted independently — it is rebuilt on server start by scanning all loaded sub-levels.

```java
public class ClaimRegistry {
    // Name → sub-level UUID (enforces global name uniqueness)
    private final Map<String, UUID> nameIndex = new HashMap<>();

    // Reverse: sub-level UUID → name (for fast lookup)
    private final Map<UUID, String> uuidToName = new HashMap<>();

    // Player → owned sub-level UUIDs (for /sp myclaims)
    private final Map<UUID, Set<UUID>> ownerIndex = new HashMap<>();

    // Player → member-of sub-level UUIDs (for /sp myclaims)
    private final Map<UUID, Set<UUID>> memberIndex = new HashMap<>();

    public boolean isNameTaken(String name);
    public @Nullable UUID getSubLevelByName(String name);
    public Collection<UUID> getOwnedBy(UUID playerUuid);
    public Collection<UUID> getMemberOf(UUID playerUuid);

    // Called by ClaimObserver when sub-levels are added/loaded
    public void index(ServerSubLevel subLevel);

    // Called when sub-levels are removed/unloaded
    public void remove(UUID subLevelUuid);

    // Called on claim/unclaim/rename/ownership transfer
    public void update(UUID subLevelUuid, ClaimData data);
}
```

**Rebuilding on load:** When `SubLevelObserver.onSubLevelAdded()` fires (including initial server load), the registry reads `ClaimData` from each sub-level's `userDataTag` and indexes it. This means the registry is always consistent with the authoritative source (the sub-level tags).

---

## 4. Protection Event Handlers

All protection handlers follow the same pattern:
1. Check if the event involves a `ServerPlayer` (skip non-player, skip client-side)
2. Look up the sub-level at the event's block position via `Sable.HELPER.getContaining(level, blockPos)`
3. If no sub-level → return (not our concern)
4. Read `ClaimData` from the sub-level
5. If unclaimed → return
6. Check the player's role and the relevant toggle
7. If denied → cancel the event and send a denial message

### BlockProtectionHandler

**Events:**
- `BlockEvent.BreakEvent` — block breaking
- `BlockEvent.EntityPlaceEvent` — block placement
- `ExplosionEvent.Detonate` — explosion damage (remove protected blocks from affected list)

**Check:** `claimData.blocksProtected && role == DEFAULT`

**Explosion handling:** Iterate `event.getAffectedBlocks()`, remove any block that is inside a claimed sub-level with blocks-protected enabled.

### InteractionProtectionHandler

**Events:**
- `PlayerInteractEvent.RightClickBlock` — block use (buttons, levers, crafting tables, etc.)
- `PlayerInteractEvent.EntityInteract` — right-click entities on the sub-level
- `AttackEntityEvent` — attack entities on the sub-level

**Check:** `claimData.interactionsProtected && role == DEFAULT`

**Exclusions:**
- Container blocks — handled by InventoryProtectionHandler
- Doors (`DoorBlock`) and fence gates (`FenceGateBlock`) — always interactable regardless of toggle (player movement only, trapdoors are NOT excluded)

**Known limitation:** When the Interactions toggle is protected, block placement is also blocked because canceling `RightClickBlock` prevents the `EntityPlaceEvent` from ever firing. This means the Interactions permission implicitly blocks placement too. This is low-priority — it is unlikely an owner would leave Blocks unprotected while keeping Interactions protected.

### InventoryProtectionHandler

**Events:**
- `PlayerInteractEvent.RightClickBlock` — specifically for container blocks

**Check:** `claimData.inventoriesProtected && role == DEFAULT`

**Container detection:** Check if the block at the event position is an instance of `Container` (or more specifically: chest, barrel, shulker box, or specific mod blocks). This handler runs **before** InteractionProtectionHandler for container blocks — if it denies, the event is cancelled; if the block is not a container, it falls through to the interaction handler.

**Implementation note:** Both InteractionProtectionHandler and InventoryProtectionHandler listen to `RightClickBlock`. Use `@SubscribeEvent(priority = EventPriority.HIGH)` on the inventory handler so it runs first. If the block entity at the position implements `net.minecraft.world.Container`, treat it as an inventory interaction. Additionally, the following mod blocks are treated as inventory interactions by registry name: `create:stock_ticker` (vault contents access) and `create:blaze_burner` (fuel insertion).

### DisassemblyProtectionHandler

**Events:**
- `PlayerInteractEvent.RightClickBlock` — for Physics Assembler interaction **and** Merging Glue item use
- `BlockEvent.EntityPlaceEvent` — fallback for Merging Glue block placement

**Check:** `role != OWNER` (always denied for non-owners, regardless of toggles)

**Physics Assembler detection:** Check if the block at the event position is `simulated:physics_assembler`. The interaction that triggers assembly/disassembly is `useWithoutItem` on the assembler block — this fires as a `RightClickBlock` event. If the assembler is on a claimed sub-level and the player is not the owner, cancel.

**Merging Glue detection (known issue — not fully protected):** The merge is initiated by right-clicking with an item tagged `simulated:merging_glue` (slime balls). This triggers a **client-side** handler (`MergingGlueItemHandler`) that collects two click positions and sends a `PlaceMergingGluePacket` directly to the server, which calls `level.setBlockAndUpdate()` — bypassing `EntityPlaceEvent` entirely. Server-side `RightClickBlock` cancellation does not prevent the client-side handler from proceeding with the merge selection. The current handler catches `RightClickBlock` for the held item tag and retains `EntityPlaceEvent` as a fallback, but neither fully blocks the merge. A complete fix likely requires a mixin into `PlaceMergingGluePacket.handle()` or a client-side component.

---

## 5. Command Tree

Commands are registered via `RegisterCommandsEvent` on the NeoForge event bus.

```
/sp
├── claim <name: string>
├── claimuuid <uuid: string> <name: string>                            (OP)
│   ├── <owner: EntityArgument>                     (online player)
│   └── owneruuid <owner_uuid: string>              (raw UUID)
├── myclaims
├── info [name: string]
├── locate <name: string>
├── fetch <name: string>
├── edit <name: string>
│   ├── blocks <protected|unprotected>
│   ├── interactions <protected|unprotected>
│   ├── inventories <protected|unprotected>
│   ├── rename <newname: string>
│   ├── changeowner <player: EntityArgument>
│   ├── addmember <player: EntityArgument>
│   └── removemember <player: EntityArgument>
├── unclaim <name: string> [CONFIRM]
└── debug                                                              (OP)
```

**Targeting for `/sp claim` and `/sp info` (no name):**
Use the player's look direction to find the nearest sub-level via physics-based spatial query:
1. Get the player's eye position and look vector; compute the ray endpoint at max reach (~6 blocks)
2. Call `Sable.HELPER.getAllIntersecting(level, BoundingBox3d(eye, end))` to find all sub-levels whose physics bounds overlap the ray
3. For each candidate, transform the ray into the sub-level's local/plot coordinate space using `subLevel.logicalPose().transformPositionInverse()`
4. Step along the local-space ray checking for non-air blocks; track the closest hit across all candidates
5. Note: `getContaining(level, pos)` only checks plot-grid chunk membership and does **not** work for world-space raycasts

**Name argument completion:**
For commands that take `<name>`, provide tab-completion via a `SuggestionProvider` that queries the `ClaimRegistry.nameIndex`. For owner-only commands, filter to the player's own claims. For member commands (locate, fetch), include claims the player is a member of.

**Permission checks:** Each command executor checks `ClaimData.getRole(player.getUUID())` and returns a failure message if the player lacks the required role.

---

## 6. Sub-Level Lifecycle Handling

### ClaimObserver (implements `SubLevelObserver`)

Registered once per level via `SableSubLevelContainerReadyEvent`:

```java
SableEventPlatform.INSTANCE.onSubLevelContainerReady((level, container) -> {
    container.addObserver(new ClaimObserver(claimRegistry));
});
```

**`onSubLevelAdded(SubLevel subLevel)`:**
1. Read `ClaimData` from the sub-level's `userDataTag`
2. If present, index it in `ClaimRegistry`
3. **Split inheritance:** If `((ServerSubLevel) subLevel).getSplitFromSubLevel()` is non-null:
   a. Look up the parent sub-level's `ClaimData`
   b. If the parent is claimed and the new fragment's mass (`getMassTracker().getMass()`) is >= 4:
      - Copy the parent's claim data (owner, members, flags)
      - Generate a unique name by appending a numbered suffix (e.g., `"ShipName-2"`, `"ShipName-3"`)
      - Write the new `ClaimData` to the fragment
      - Index in `ClaimRegistry`
   c. If mass < 4: leave unclaimed (prevents claimed debris)

**`onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason)`:**
1. Remove from `ClaimRegistry` (both by UUID and by name)
2. No need to clear `userDataTag` — the sub-level is being destroyed

### SplitListener

Register via `SubLevelHeatMapManager.addSplitListener(...)` during mod init. This fires *before* the new sub-levels are created, providing the block list for each new fragment. We don't need to act here — the `onSubLevelAdded` path handles inheritance. However, this listener could be used in the future for diagnostics or pre-split validation.

---

## 7. Physics Freeze (Fetch)

### Overview

The `/sp fetch` command teleports an out-of-bounds sub-level back inside the world border and freezes its physics for 60 seconds.

### Implementation

**Teleport location:**
1. Get the sub-level's current world position from `subLevel.logicalPose().position()`
2. Get the world border from `level.getWorldBorder()`
3. Find the nearest point on the border to the sub-level's current position
4. Offset ~50 blocks inward from the border edge along the vector from border to center
5. Determine a safe Y by checking for ground at that XZ position
6. Use `RigidBodyHandle.teleport(newPosition, currentOrientation)` to move the sub-level

**Physics freeze:**
Sable does not expose a direct "freeze" API. The approach is to use a `FixedConstraint` that pins the sub-level to the world:

```java
RigidBodyHandle handle = RigidBodyHandle.of(subLevel);
Pose3d pose = subLevel.logicalPose();

// Pin the sub-level to the world at its current position
FixedConstraintHandle constraint = physicsSystem.getPipeline().addConstraint(
    subLevel,      // body A (the sub-level)
    null,          // body B (null = world anchor)
    new FixedConstraintConfiguration(
        pose.position(),           // position in A's frame
        pose.position(),           // position in world frame
        pose.orientation()         // orientation
    )
);

// Also zero out any existing velocity
physicsSystem.getPipeline().resetVelocity(subLevel);
```

**Freeze expiry:**
Track active freezes in a `Map<UUID, FreezeState>` where `FreezeState` holds the `FixedConstraintHandle` and expiry tick. Each server tick, check for expired freezes and remove the constraint:

```java
record FreezeState(FixedConstraintHandle constraint, long expiryTick) {}

// In a ServerTickEvent handler:
frozenSubLevels.entrySet().removeIf(entry -> {
    if (currentTick >= entry.getValue().expiryTick()) {
        entry.getValue().constraint().remove();
        // Notify the owner/members that the freeze has expired
        return true;
    }
    return false;
});
```

**Edge cases:**
- If the sub-level is removed while frozen (e.g., disassembled), the freeze entry should be cleaned up in `onSubLevelRemoved`
- If the sub-level is already inside the world border, the command should fail with a message

---

## 8. Chat Info Window

The `/sp info` window is built using Minecraft's `Component` system with `ClickEvent` and `HoverEvent` for interactive buttons.

**Button construction pattern:**

```java
// Clickable button
Component button = Component.literal("[Rename]")
    .withStyle(style -> style
        .withColor(ChatFormatting.AQUA)
        .withClickEvent(new ClickEvent(
            ClickEvent.Action.SUGGEST_COMMAND,
            "/sp edit " + name + " rename "))
        .withHoverEvent(new HoverEvent(
            HoverEvent.Action.SHOW_TEXT,
            Component.literal("Click to rename"))));

// Toggle button (clicks auto-submit)
String newState = currentlyProtected ? "unprotected" : "protected";
Component toggle = Component.literal("[" + (currentlyProtected ? "PROTECTED" : "UNPROTECTED") + "]")
    .withStyle(style -> style
        .withColor(currentlyProtected ? ChatFormatting.GREEN : ChatFormatting.RED)
        .withClickEvent(new ClickEvent(
            ClickEvent.Action.RUN_COMMAND,
            "/sp edit " + name + " blocks " + newState)));
```

**Role-based visibility:**
- Owner: all buttons visible
- Member: Locate and Fetch visible; toggles shown as plain text
- Default: toggles and membership shown as plain text; Locate/Fetch hidden

**Re-print after mutation:** All `/sp edit` and `/sp unclaim` commands call the info-window builder at the end of their execution to send an updated view.

---

## 9. Configuration

Server-side config via NeoForge `ModConfigSpec` at `config/sableprotect-common.toml`:

```toml
# Minimum mass for a sub-level to be claimable (and to inherit claims on split)
minimumClaimMass = 4

# Duration of the physics freeze after /sp fetch, in seconds
fetchFreezeDurationSeconds = 60

# How far inside the world border to place a fetched sub-level, in blocks
fetchBorderInset = 50
```

These are intentionally minimal. Most behavior is per-claim (stored in `userDataTag`), not global config.

---

## 10. Implementation Phases

### Phase 1: Core Claim Data and Basic Protection
**Goal:** A player can claim a sub-level and it is protected from outsiders. Testable in-game with basic commands.

**Deliverables:**
- `ClaimData` — serialization, deserialization, read/write from `userDataTag`
- `ClaimRole` enum
- `ClaimRegistry` — in-memory name/UUID index
- `ClaimObserver` — indexes claims on sub-level load, cleans up on remove
- `SableProtectMod` — entry point, event bus wiring
- `/sp claim <name>` — claim the targeted sub-level
- `/sp unclaim <name> [CONFIRM]` — remove a claim
- `/sp info <name>` — basic text output (no interactive buttons yet)
- `/sp debug` — OP-only debug toggle
- `/sp claimuuid <uuid> <name> [owner]` — OP-only claim by UUID
- `SubLevelLookup` — physics-based `getAllIntersecting` + local-space raycast
- `DebugHelper` — per-player debug state tracking
- `BlockProtectionHandler` — block break, place, explosions
- `InteractionProtectionHandler` — right-click block, entity interact, attack entity
- `InventoryProtectionHandler` — container right-click filtering
- `DisassemblyProtectionHandler` — physics assembler and merging glue

**Verification:**
1. Assemble a sub-level, run `/sp claim TestShip`
2. Switch to a second account (or use a fake player) — verify block break/place is denied
3. Verify right-clicking buttons/levers is denied
4. Verify opening chests is denied
5. Verify the owner can still do all of the above
6. Verify interacting with the physics assembler as a non-owner is denied
7. Verify placing merging glue on the sub-level as a non-owner is denied
8. Run `/sp unclaim TestShip CONFIRM` — verify all protections are removed
9. Test explosion protection: TNT near a claimed sub-level should not damage its blocks

---

### Phase 2: Membership and Permission Toggles
**Goal:** Owners can add members and toggle protection categories. The info window becomes interactive.

**Deliverables:**
- `/sp edit <name> addmember <player>`
- `/sp edit <name> removemember <player>`
- `/sp edit <name> blocks|interactions|inventories protected|unprotected`
- `/sp edit <name> rename <newname>`
- `/sp edit <name> changeowner <player>`
- `/sp myclaims`
- Interactive chat info window with click events (role-based button visibility)
- Tab completion for claim names in all commands

**Verification:**
1. Add a member — verify they can break blocks, open chests, use interactions
2. Toggle blocks to `unprotected` — verify default users can now break/place but still can't open chests
3. Toggle inventories to `unprotected` — verify default users can now open chests
4. Verify member cannot use physics assembler (disassembly remains owner-only)
5. Transfer ownership — verify old owner becomes default, new owner has full control
6. Rename — verify old name no longer resolves, new name works
7. `/sp myclaims` — verify both owned and member-of lists appear correctly

---

### Phase 3: Split Inheritance and Merge Protection
**Goal:** Claims survive sub-level splitting and merging behaves correctly.

**Deliverables:**
- Split inheritance in `ClaimObserver.onSubLevelAdded()` — copy claim data to fragments with mass >= 4, with name suffixing via `ClaimRegistry.generateSuffixedName()` *(code wired, **NOT WORKING** — see Known Issues in DESIGN.md)*
- Merge protection in `DisassemblyProtectionHandler` — deny merging glue placement on a sub-level the placing player does not own *(implemented in Phase 1; covers the cross-owner case)*
- Cleanup of small fragments (mass < 4 → unclaimed) *(blocked by split inheritance not firing)*

**Implementation notes:**
- The `ClaimObserver` holds a reference to its `SubLevelContainer` so it can resolve the parent sub-level via `container.getSubLevel(parentUuid)` when `getSplitFromSubLevel()` is non-null.
- Mass threshold lives as `MIN_INHERIT_MASS = 4.0` in `ClaimObserver` (mirrors the `minimumClaimMass` config value; consolidate once Phase 4 lands the config).
- Inheritance generates the suffixed name from the parent's *current* name; chained splits will produce names like `Ship-2`, `Ship-3`, ... continuing past any existing suffix.
- **Known issue:** in practice fragments do not get parent data copied and remain unclaimed. The wiring is in place but does not fire as expected — suspected to be a timing issue around when `getSplitFromSubLevel()` / `userDataTag` are populated relative to `onSubLevelAdded`. Needs investigation, possibly using `SubLevelHeatMapManager.addSplitListener` instead of (or in addition to) `onSubLevelAdded`.

**Verification:**
1. Claim a sub-level, then destroy blocks to cause a split — verify both fragments are claimed with the same owner/members/flags
2. Verify the fragment name has a numbered suffix (e.g., `"TestShip-2"`)
3. Split into a tiny fragment (< 4 mass) — verify it is not claimed
4. Have two players each claim a sub-level — verify neither can place merging glue on the other's ship
5. Claim a sub-level, fly it next to an unclaimed sub-level, place merging glue — verify the merge proceeds normally
6. After merge completes, verify the surviving sub-level retains its original claim

---

### Phase 4: Locate, Fetch, and Physics Freeze
**Goal:** Members and owners can find and recover out-of-bounds ships.

**Deliverables:**
- `/sp locate <name>` — returns world coordinates
- `/sp fetch <name>` — teleports out-of-bounds sub-level inside the world border
- Physics freeze system (FixedConstraint + tick-based expiry)
- Freeze expiry notification to owner/members
- Config options for freeze duration and border inset

**Verification:**
1. Claim a sub-level, fly it outside the world border
2. Run `/sp locate ShipName` — verify coordinates are returned
3. Run `/sp fetch ShipName` — verify the sub-level appears just inside the world border, above ground
4. Verify the sub-level is completely frozen (no drift, no response to explosions or forces)
5. Wait 60 seconds — verify physics resume and the player is notified
6. Verify `/sp fetch` fails if the sub-level is already inside the border
7. Verify a member (non-owner) can also locate and fetch

---

### Phase 5: Polish and Edge Cases
**Goal:** Harden edge cases, add localization, and finalize for release.

**Deliverables:**
- Full `en_us.json` localization for all messages (denial, confirmation, info window labels, notifications)
- Admin bypass (op-level or permission node for moderators to bypass all protection)
- Proper error messages for all failure cases (name taken, not looking at a sub-level, not the owner, etc.)
- Handle edge case: sub-level removed while frozen (cleanup constraint)
- Handle edge case: server shutdown while sub-levels are frozen (constraint persists in physics state; verify behavior on restart)
- Handle edge case: player offline when freeze expires (just remove constraint silently)
- Handle edge case: concurrent claims during split (two fragments getting indexed simultaneously — ensure name suffix generation is race-safe)

**Verification:**
- Full regression of all previous phase tests
- Test with multiple players simultaneously to verify no race conditions
- Test server restart with active claims and active freezes
- Test all error messages display correctly
