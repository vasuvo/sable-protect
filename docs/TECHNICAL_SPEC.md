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
│   ├── ClaimData.java              # Per-sub-level claim state (serialized to userDataTag and to ClaimStorage)
│   ├── ClaimRegistry.java          # Server-wide name→UUID index, storage-backed (Phase 7)
│   ├── ClaimRole.java              # Enum: OWNER, MEMBER, DEFAULT
│   └── ClaimStorage.java           # SavedData persisted to <world>/data/sableprotect_claims.dat (Phase 7)
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
│   ├── FetchCommand.java           # /sp fetch + physics freeze
│   ├── GroundCommand.java          # /sp ground (Phase 12) — vertical drop + freeze
│   ├── MyClaimsCommand.java        # /sp myclaims
│   ├── DebugCommand.java           # /sp debug (OP-only, toggle debug output)
│   ├── BypassCommand.java          # /sp bypass (OP-only, toggle admin claim-protection bypass)
│   ├── StealCommand.java           # /sp steal (Phase 6, requires NML + on-board + crew absent)
│   └── ReloadCommand.java          # /sp reload (Phase 8, OP-only force config + lang reload)
├── config/
│   └── SableProtectConfig.java     # ModConfigSpec (minimum mass, freeze duration, border inset)
├── freeze/
│   ├── FreezeManager.java          # Tracks active /sp fetch freezes; ticks expiry; cleans up on remove; can hold a forced chunk for the freeze's lifetime (Phase 11)
│   └── PendingFetchManager.java    # Queue of /sp fetch requests against unloaded sub-levels; force-loads plot chunks and times out on no-show (Phase 11)
├── lifecycle/
│   ├── ClaimObserver.java          # SubLevelObserver for add/remove tracking
│   └── SplitInheritanceQueue.java  # Bridges Sable's SplitListener to onSubLevelAdded
├── mixin/
│   └── sim/                        # Simulated-Project packet handler mixins (block client-side bypasses)
│       ├── AssemblePacketMixin.java
│       ├── PlaceMergingGluePacketMixin.java
│       ├── PlaceSpringPacketMixin.java
│       ├── RopeBreakPacketMixin.java
│       ├── SteeringWheelPacketMixin.java
│       └── ThrottleLeverSignalPacketMixin.java
├── audit/
│   └── AuditLog.java               # Append-only plain-text log of claim lifecycle events (Phase 15)
├── permissions/
│   ├── Permissions.java            # Public permission gate; LP-aware with vanilla fallback (Phase 9)
│   └── LuckPermsBridge.java        # Direct LP API — only loaded when LP is installed (Phase 9)
└── util/
    ├── SubLevelLookup.java         # Physics-based spatial lookup for targeting sub-levels
    ├── DebugHelper.java            # Per-player debug toggle state
    ├── BypassHelper.java           # Per-player admin-bypass opt-in state (session-only)
    ├── NoMansLand.java             # Config-backed rectangle test (Phase 6)
    ├── Lang.java                   # Server-side string lookup (Phase 8) — bundles en_us.json
    └── CrewPresence.java           # Shared crew-within-radius check (Phase 12) — used by /sp steal and /sp ground
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

The registry is a server-side index over the persistent {@link ClaimStorage} (introduced in Phase 7). It maintains in-memory derived indexes — name → UUID, UUID → name, owner → UUIDs, member → UUIDs — backed by the canonical `Map<UUID, ClaimData>` stored in the `SavedData` file.

**Persistence model:**
- `ClaimStorage` is a vanilla `SavedData` anchored to the overworld's data directory (`<world>/data/sableprotect_claims.dat`). It is the canonical source of truth for all claims.
- Each `ServerSubLevel.userDataTag` carries a mirror of its claim, which is kept in sync when the sub-level is loaded. The mirror is *not* authoritative — on conflict the storage wins.
- The registry is attached to storage on `ServerStartedEvent` and detached on `ServerStoppingEvent`. Any claims the registry accumulated pre-attach (from sub-level containers ready before `ServerStartedEvent`) are reconciled at attach time.

**Reconciliation on sub-level load (`ClaimObserver.onSubLevelAdded` → `ClaimRegistry.index`):**
- *storage has, tag has, agree:* re-index, no writes.
- *storage has, tag empty (or differs):* claim was edited while unloaded — write storage's data into the tag.
- *storage empty, tag has:* legacy claim from pre-Phase-7 — migrate by writing to storage.
- *both empty:* nothing to track.

**Lifecycle on sub-level removal (`ClaimObserver.onSubLevelRemoved`):**
- `UNLOADED` reason → keep the claim in storage and indexes; the sub-level can reload.
- `REMOVED` reason → drop from storage and indexes; the sub-level was destroyed (disassembled, merged, etc.).

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

## 3a. Mixin-Based Packet Protection

Several Simulated-Project blocks dispatch interactions via Veil's `VeilPacketManager` from
client-side input handlers, bypassing the standard `useItemOn` / `useWithoutItem` flow that
the NeoForge `RightClickBlock` event hooks into. To protect those, a small set of mixins
intercepts each packet's `handle(ServerPacketContext)` method at HEAD, resolves the target
`BlockPos` to a sub-level via `Sable.HELPER.getContaining(level, pos)`, and aborts the
handler if the player isn't authorized.

| Packet                                       | Role required                          | UI feedback                                |
| -------------------------------------------- | -------------------------------------- | ------------------------------------------ |
| `AssemblePacket` (Physics Assembler trigger) | Owner                                  | Chat denial                                |
| `PlaceMergingGluePacket`                     | Owner of both sides                    | Chat denial (action bar)                   |
| `PlaceSpringPacket`                          | Owner of both sides                    | Chat denial (action bar)                   |
| `SteeringWheelPacket`                        | Member or above (Interactions toggle)  | **Silent** — packet fires per input frame  |
| `ThrottleLeverSignalPacket`                  | Member or above (Interactions toggle)  | **Silent**                                 |
| `RopeBreakPacket`                            | Member or above (Blocks toggle)        | Chat denial                                |

The shared decision logic lives in `protection/PacketProtection.java`; each mixin is a thin
shim that pulls the relevant `BlockPos` out of the packet (via `@Shadow` for record fields,
or `@Local` for variables computed inside the handler) and calls into the helper. All
mixins use `remap = false` and target classes by string name (`@Mixin(targets = "...")`)
so the mod compiles without Simulated-Project on the classpath.

The mixin config is `src/main/resources/sableprotect.mixins.json`, registered via
`[[mixins]] config = "sableprotect.mixins.json"` in `neoforge.mods.toml`. Veil is declared
`compileOnly` in `build.gradle` so we can reference `ServerPacketContext` directly; at
runtime Veil is provided by the user's installed Sable / Sim-Project (no new dependency).

**Known gap:** The Physics Staff (`PhysicsStaffActionPacket`, `PhysicsStaffDragPacket`)
targets sub-levels by UUID rather than block position and is not yet covered. See the
DESIGN.md Known Issues section.

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
├── fetch <name: string>
├── ground <name: string>
├── edit <name: string>
│   ├── blocks <protected|unprotected>
│   ├── interactions <protected|unprotected>
│   ├── inventories <protected|unprotected>
│   ├── rename <newname: string>
│   ├── changeowner <player: EntityArgument>
│   ├── addmember <player: EntityArgument>
│   └── removemember <player: EntityArgument>
├── unclaim <name: string> [CONFIRM]
├── steal <name: string> [CONFIRM]
├── debug                                                              (OP)
├── bypass                                                             (OP)
└── reload                                                             (OP)
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
freezeDurationSeconds = 60

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
- Split inheritance in `ClaimObserver` — copy claim data to fragments with mass >= configured minimum, with name suffixing via `ClaimRegistry.generateSuffixedName()` *(implemented; deferred-tick approach, see notes)*
- Merge protection in `DisassemblyProtectionHandler` — deny merging glue placement on a sub-level the placing player does not own *(implemented in Phase 1; covers the cross-owner case)*
- Cleanup of small fragments (mass < min → unclaimed) *(implemented)*

**Implementation notes:**
- The `ClaimObserver` holds a reference to its `SubLevelContainer` so it can resolve the parent sub-level via `container.getSubLevel(parentUuid)`.
- Mass threshold reads from `SableProtectConfig.MINIMUM_CLAIM_MASS` so claim and split-inheritance share a single source of truth.
- Inheritance generates the suffixed name from the parent's *current* name; chained splits will produce names like `Ship-2`, `Ship-3`, ... continuing past any existing suffix.
- **Timing:** `onSubLevelAdded` fires inside `SubLevelAssemblyHelper.assembleBlocks()`, immediately after `container.allocateNewSubLevel(pose)` and **before** the plot has been populated with blocks, mass has been computed, or `setSplitFrom()` has been called. So the inheritance check cannot run during the add callback. The observer instead enrolls every fresh sub-level in a `pending` map, and re-checks each entry on subsequent `tick()` calls. By the next tick, `getSplitFromSubLevel()` is set and mass is populated, so inheritance can be applied. Pending entries time out after `MAX_PENDING_TICKS` (5) ticks if neither a claim nor a split parent appears, at which point the sub-level is treated as a freshly-assembled, unclaimed sub-level.

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
- `/sp locate <name>` — returns world coordinates *(implemented)*
- `/sp fetch <name>` — teleports out-of-bounds sub-level inside the world border *(implemented)*
- Physics freeze system (FixedConstraint + tick-based expiry) *(implemented in `freeze/FreezeManager`)*
- Freeze expiry notification to owner/members *(implemented; subscriber list snapshotted at freeze time)*
- Config options (`minimumClaimMass`, `freezeDurationSeconds`, `fetchBorderInset`) *(implemented in `config/SableProtectConfig`)*
- Info-window `[Locate]` / `[Fetch]` buttons for owners and members *(implemented)*

**Implementation notes:**
- Teleport uses `pipeline.resetVelocity(subLevel)` followed by `pipeline.teleport(subLevel, pos, orientation)` (matching the order used by Sable's own `/sable subLevel teleport` command).
- Freeze does **not** use a physics constraint. A world-anchor `FixedConstraint` was tried first, but caused the rigid body to be flung to extreme coordinates — Sable then auto-removed the sub-level via its `SUB_LEVEL_REMOVE_MAX` Y check. The current approach stores the anchor pose at freeze time and re-applies it every tick (`resetVelocity` + `teleport`) until expiry. This is functionally identical from the player's perspective and avoids the constraint timing/coordinate-frame issues.
- `ClaimObserver.onSubLevelRemoved` calls `FreezeManager.cancel()` to drop the constraint when a frozen sub-level is unloaded or destroyed mid-freeze.
- Safe-Y is computed from the world's `MOTION_BLOCKING_NO_LEAVES` heightmap plus a 5-block buffer.
- `[Fetch]` is currently shown unconditionally in the info window for owners/members; per the design doc it should only appear when the sub-level is outside the world border. The command itself rejects in-border fetches, so this is a UX-only follow-up.

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
- Full `en_us.json` localization for player-visible messages (denial, confirmation, info window labels, notifications) *(implemented for all `Component.translatable` strings; UI button labels and separators remain literal as design choice)*
- Admin bypass via `adminBypassPermissionLevel` config (default 4, max 5 = disabled) — opt-in per session via `/sp bypass`; eligible admins start each session with protection applied until they toggle it off *(implemented in `ProtectionHelper.isAdminBypass` + `util/BypassHelper`, applied in all four protection handlers)*
- Proper error messages for failure cases — name taken, not looking at a sub-level, not the owner, not loaded, not authorized for locate/fetch, etc. *(implemented across all commands)*
- Sub-level removed while frozen → constraint cleaned up *(implemented in `ClaimObserver.onSubLevelRemoved` → `FreezeManager.cancel`)*
- Server shutdown while frozen → constraints dropped before physics teardown *(implemented in `ServerStoppingEvent` → `FreezeManager.cancelAll`; `ClaimRegistry.clear()` also called)*
- Player offline when freeze expires → message silently skipped *(implemented; `playerList.getPlayer(uuid)` returns null → no notification)*
- Concurrent split name-suffix race *(non-issue; sub-level lifecycle callbacks all run on the server tick thread — documented in `ClaimRegistry.generateSuffixedName` Javadoc)*
- Info-window `[Fetch from Out of Bounds]` button only shown when the sub-level is actually outside the world border *(implemented via `InfoCommand.isOutsideWorldBorder`)*

**Implementation notes:**
- The bypass disables on permission level 5 (above vanilla max of 4), so server admins can opt out by setting the config value to 5.
- `ServerStoppingEvent` fires before the level (and thus the physics container) tears down, which is the correct moment to drop constraints — leaving them in place during teardown can result in dangling Rapier handles.

**Verification:**
- Full regression of all previous phase tests
- Test with multiple players simultaneously to verify no race conditions
- Test server restart with active claims and active freezes
- Test all error messages display correctly

---

### Phase 6: No Man's Land
**Goal:** A configurable rectangular region in which all claim protections are suspended and ships can be stolen by anyone willing to board them after the crew is absent.

**Deliverables:**
- `noManLand` config block (`enabled`, `minX`/`maxX`/`minZ`/`maxZ`); the absence-radius config used to live here as `stealAbsenceRadius` and was lifted to a top-level `absenceRadius` in Phase 12 *(implemented in `SableProtectConfig`)*
- `util/NoMansLand.java` — single source of truth for the in-rectangle test, normalizing user-supplied corner order and short-circuiting when disabled *(implemented)*
- `ProtectionHelper.getClaimContext` and `PacketProtection.resolveClaim` return null for in-NML claims, causing every event-based and mixin-based protection to no-op *(implemented)*
- `InteractionProtectionHandler` entity-interact / attack-entity paths early-out on in-NML sub-levels (they read `ClaimData` directly, bypassing `getClaimContext`) *(implemented)*
- `/sp steal <name> [CONFIRM]` — two-step ownership transfer with on-board + crew-absence preflight checks; preserves name + toggles, clears members, sends a red notification to all online prior owner/members *(implemented in `command/StealCommand`)*
- Tab completion for `/sp steal` filters to claims currently in NML where the player isn't already the owner *(implemented)*
- Info window: `[NO MAN'S LAND]` red annotation next to the title when applicable, `[Steal]` button for non-owners on in-NML ships *(implemented)*

**Implementation notes:**
- "On board" is determined by `Sable.HELPER.getTrackingOrVehicleSubLevel(player)`, matching how Sable itself attributes a player to a sub-level (riding, standing on, etc.).
- "Crew absent" is enforced via squared-distance comparison from each online crew member's position to the sub-level's `logicalPose().position()`. Members on a different dimension count as absent. Offline members count as absent.
- The initiator's own presence is excluded from the absence check (a member running `/sp steal` on themselves isn't blocked by their own proximity).
- `/sp steal` does not interact with the admin bypass; bypass is irrelevant inside NML because protections are already off.
- The claim's NBT (`sableprotect` compound on `userDataTag`) is unchanged when a ship enters/leaves NML — the gating is purely runtime, computed against the current pose. A claimed ship that re-enters protected airspace is immediately re-protected.

**Verification:**
1. With NML enabled, fly a claimed sub-level into the configured rectangle; verify a non-member can break/place blocks and use interactions on it.
2. Verify the info window shows `[NO MAN'S LAND]` while inside the rectangle and the annotation disappears once the ship moves out.
3. With the owner online and standing on the ship, attempt `/sp steal` from a non-owner: should fail with the crew-present message naming the owner.
4. With the owner offline (or kicked, or far away), board the ship as a non-owner and run `/sp steal <name>` then `/sp steal <name> CONFIRM`: ownership should transfer, the previous owner gets a red notification on next login (or immediately if online), and members are cleared.
5. Move the ship out of NML and verify the new owner's protections re-engage.

---

### Phase 7: Persistent Claim Tracking
**Goal:** Claims survive sub-level unload, dimension change, and server restart. `/sp myclaims`, `/sp info`, and `/sp edit` continue to work for unloaded claims; any edits made while a sub-level is unloaded are applied to its `userDataTag` next time it loads.

**Deliverables:**
- `claim/ClaimStorage.java` — `SavedData` holding `Map<UUID, ClaimData>` persisted to `<world>/data/sableprotect_claims.dat` *(implemented)*
- `ClaimRegistry` refactored to delegate writes through storage; in-memory indexes are derived and rebuilt from storage on `attach()` *(implemented)*
- `ClaimObserver.onSubLevelRemoved` distinguishes `UNLOADED` (keep tracking) from `REMOVED` (drop) *(implemented)*
- `ClaimObserver.onSubLevelAdded` reconciles `userDataTag` against storage on every load — the storage wins on conflict, so edits made while unloaded are applied to the tag when the sub-level loads next *(implemented in `ClaimRegistry.index`)*
- `SableProtectMod` attaches storage on `ServerStartedEvent`, detaches on `ServerStoppingEvent` *(implemented)*
- `EditCommand`, `UnclaimCommand`, `InfoCommand` read claim data from the registry instead of `userDataTag`, so they work for unloaded claims; sub-level lookup is best-effort and `userDataTag` is mirrored only when loaded *(implemented)*
- `/sp info <name>` shows an `[unloaded]` annotation when the sub-level isn't currently loaded; `[Locate]`, `[Fetch]`, and `[Steal]` are hidden in that case (they all need a loaded body) *(implemented)*
- `LocateCommand`, `FetchCommand`, `StealCommand` continue to require a loaded sub-level (their physics-dependent operations can't work otherwise), but with consistent "not_loaded" error messaging *(implemented)*

**Implementation notes:**
- Storage anchors to the overworld so a single file covers all dimensions; sub-level UUIDs are globally unique within the server.
- `ClaimRegistry.touchClaim(uuid)` is the new in-place mutation API — callers obtain a `ClaimData` reference via `getClaim`, mutate it, then call `touchClaim` to mark dirty + re-index. This avoids needing fresh allocations on every edit.
- The legacy `update`/`remove`/`clear` methods are retained as `@Deprecated` thin wrappers around `putClaim`/`removeClaim`/`detach` so any out-of-tree callers keep working during the transition.
- Pre-attach sub-level adds (from `onSubLevelContainerReady` callbacks that fire before `ServerStartedEvent`) populate the registry's indexes only; on `attach()` the storage takes precedence and indexes are rebuilt from it. Any tag-only claims discovered post-attach migrate into storage automatically via `ClaimRegistry.index`.

**Verification:**
1. Claim a sub-level, log out, log back in — verify `/sp myclaims` still lists it and `/sp info <name>` succeeds even before the sub-level reloads.
2. Unload a sub-level by getting far away from it. Run `/sp edit <name> rename Foo` from a different player. Walk back to reload the sub-level — verify `userDataTag` now reflects the new name.
3. Restart the server with claimed sub-levels in unloaded chunks — verify `/sp myclaims` still shows them.
4. Disassemble a claimed sub-level via the Physics Assembler — verify the claim is removed from storage (next `/sp myclaims` doesn't list it).

---

### Phase 8: English Strings, Toggle Cascade, Hot Reload
**Goal:** Three small UX/operations improvements.

**Deliverables:**
- `util/Lang.java` — bundles `en_us.json` inside the jar and resolves keys to English `Component.literal` at runtime, since the client never receives the lang resource for a server-only mod *(implemented; all 14 source files migrated from `Component.translatable(...)` to `Lang.tr(...)`)*
- `EditCommand.executeToggle` enforces the invariant `!blocks ⇒ !interactions ∧ !inventories`. Unprotecting blocks cascades down; protecting interactions or inventories cascades up to also protect blocks *(implemented)*
- `/sp reload` (OP-only) calls `ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.COMMON, FMLPaths.CONFIGDIR.get())` to force-reread the config TOML, then calls `Lang.reload()`, then prints current NML status + rectangle bounds for verification *(implemented)*

**Implementation notes:**
- `Lang.tr` mimics Minecraft's `%s` and `%N$s` substitution via `String.format`. Component arguments lose their styling (rendered as plain text via `Component.getString()`); for messages that need colored substitutions, build the component by hand at the call site.
- The toggle cascade only applies on user-driven edits via `/sp edit`. Existing claims with violating combinations from earlier versions are not auto-migrated; toggling will normalize them on next change.
- NeoForge's `ConfigFileTypeHandler` already auto-watches the config TOML, so editing the file usually triggers a reload without `/sp reload`. The command is a manual fallback that also covers the case where the watcher missed an edit (rare on Windows network drives, etc.).

---

### Phase 9: LuckPerms Integration
**Goal:** Replace the OP-level (`hasPermissions(2)` / `hasPermissions(4)`) gates with permission-node checks that consult LuckPerms when it's installed and fall back to the vanilla level when it isn't.

**Deliverables:**
- `permissions/Permissions.java` — `Permissions.has(player, node, fallbackLevel)` and `has(source, node, fallbackLevel)`. LuckPerms availability is cached after first `ModList.isLoaded("luckperms")` call. *(implemented)*
- `permissions/LuckPermsBridge.java` — direct LuckPerms API call (`LuckPermsProvider.get().getUserManager().getUser(uuid).getCachedData().getPermissionData().checkPermission(node)`), isolated in its own class so it isn't loaded when LP is absent (avoids `NoClassDefFoundError`). *(implemented)*
- Soft mod-dep on `luckperms` in `neoforge.mods.toml` (optional, side=SERVER), `compileOnly("net.luckperms:api:5.5")` plus the `https://repo.lucko.me/` Maven repo in `build.gradle`. *(implemented)*
- `Permissions.Nodes` constants for all gated features: `command.debug`, `command.bypass`, `command.reload`, `command.claimuuid`, `edit.override`, `bypass.use`. *(implemented)*
- Migrated call sites: `DebugCommand`, `BypassCommand`, `ReloadCommand`, `ClaimUuidCommand`, `EditCommand` (override gate at suggestion time + non-owner branch), `ProtectionHelper.isAdminBypass`. *(implemented)*

**Tristate semantics:**
- LP returns `TRUE` → grant.
- LP returns `FALSE` → deny (overrides OP).
- LP returns `UNDEFINED` → fall back to the vanilla `hasPermissions(level)` check.

This means an existing OP-only deployment without LuckPerms node configuration keeps working unchanged — OPs still pass via the vanilla level fallback. Once an admin adds explicit grant/deny entries in LP, those take precedence.

**Implementation notes:**
- `LuckPermsBridge` references LP types directly (`LuckPerms`, `User`, `Tristate`) and lives in its own file so the JVM doesn't try to resolve those types unless `Permissions.has` actually dispatches to it. The dispatch is gated by `Permissions.isLuckPermsAvailable()`, which only references `ModList`.
- `LuckPermsBridge.query` swallows any throwable (LP not yet initialized, user not loaded, …) and returns `Tristate.UNDEFINED`, so a transient LP error degrades to vanilla-level behavior rather than denying everything.
- Console / non-player command sources skip the LP check entirely and fall through to `source.hasPermission(level)` — LP nodes don't apply to non-players.
- The existing `BypassHelper` per-player session toggle is unchanged. The eligibility check that was `player.hasPermissions(adminBypassPermissionLevel)` now goes through `Permissions.has(player, BYPASS_USE, adminBypassPermissionLevel)`.

---

### Phase 10: Inline Location, Last-Known Position
**Goal:** Replace the `[Locate]` button + `/sp locate` command with an inline coordinate readout in the info window. Show coordinates even when the sub-level is unloaded.

**Deliverables:**
- `ClaimData.lastKnownPosition` (`Vec3?`) with NBT serialization under `sableprotect:lastPos { x, y, z }`. *(implemented)*
- `ClaimObserver` snapshots the current pose into the cache on every `onSubLevelAdded` and on `onSubLevelRemoved` with `UNLOADED` reason. Split-fragment inheritance overwrites the inherited cache with the fragment's own pose. *(implemented)*
- `ClaimCommand` and `ClaimUuidCommand` capture position at claim creation. *(implemented)*
- `InfoCommand` renders a `Location: X, Y, Z` line below the title for owners and members. Loaded → live position in cyan; unloaded → cached position in italic grey. Click-copies a space-separated `X Y Z` triple suitable for `/tp`. *(implemented)*
- `[Locate]` button removed from the info window; `/sp locate` command and supporting lang strings removed. *(implemented; `LocateCommand.java` deleted)*

**Implementation notes:**
- Position cache is part of `ClaimData` so it persists through both the `userDataTag` mirror and the canonical `ClaimStorage` SavedData — survives unload, dimension change, server restart.
- Capture happens on the lifecycle hooks rather than per-tick; an unloaded ship doesn't move, so the snapshot is correct until the next load. If the server crashes between load and unload, the cache reverts to the previous snapshot until the ship reloads.
- Coordinates are floored to integers for display; the exact double precision isn't useful in chat. Click-to-copy uses the same floored values.
- Pre-Phase-10 claims with no cached position still display correctly — the `Location` line is simply omitted when both `subLevel` and `getLastKnownPosition()` are null.

---

### Phase 11: Fetch Unloaded Sub-Levels
**Goal:** `/sp fetch` works on a claimed sub-level whose chunks aren't currently loaded, as long as its cached last-known position is outside the world border.

**Deliverables:**
- `ClaimData.lastKnownPlotChunk` (`ChunkPos?`) and `lastKnownDimension` (`ResourceKey<Level>?`), serialized under `sableprotect:lastPlotChunk { cx, cz }` and `sableprotect:lastDim "namespace:path"`. *(implemented)*
- Lifecycle hooks (`ClaimObserver`, `ClaimCommand`, `ClaimUuidCommand`, split inheritance) populate both new fields alongside `lastKnownPosition`. *(implemented)*
- `freeze/PendingFetchManager` — per-mod queue keyed by sub-level UUID. `register` records the entry; `consume(uuid)` retrieves and removes; `tick` enforces a 100-tick (5s) timeout, releasing the force-loaded chunk and notifying the requester on failure. *(implemented)*
- `FreezeManager.freeze` overload accepting an optional `(ChunkPos, ResourceKey<Level>)` pair. The freeze owns the chunk-force for its full duration; expiry / cancellation releases it via `level.setChunkForced(false)`. *(implemented)*
- `FetchCommand` branches:
  - **Loaded path** (existing): teleport + freeze.
  - **Unloaded path** (new): validate cached pos / plot chunk / dimension, verify outside-border, force-load the plot chunk via `level.setChunkForced(true)`, register a `PendingFetchManager.Entry`, message the user "Loading...". *(implemented)*
- `ClaimObserver.onSubLevelAdded` consumes any pending fetch entry for the just-loaded sub-level and dispatches `FetchCommand.executePendingFetch`, which runs the teleport + freeze with the chunk-hold attached. *(implemented)*
- `SableProtectMod` ticks `pendingFetchManager` alongside `freezeManager` and calls `cancelAll(server)` on stop to release any held chunks. *(implemented)*

**Implementation notes:**
- Sable's `SubLevelHoldingChunkMap` stores unloaded sub-levels keyed by the **world chunk** at their last position — *not* by the plot grid index. The chunk we force-load is therefore derived from `lastKnownPosition.x/z >> 4`, not from `subLevel.getPlot().getCenterChunk()`. (An earlier draft of Phase 11 cached the plot-grid chunk, which had nonsensical coordinates like `(1280448, 1291200)` and caused vanilla to return `EmptyLevelChunk @ [0,0]` instead of loading anything; corrected in Phase 14.)
- The 5-second timeout protects against pathological cases (corrupted plot chunk, dimension unloaded, etc.) — without it a misconfigured fetch could hold a chunk forced indefinitely.
- The freeze takes ownership of the force-load on success: the chunk stays loaded for the 60s freeze, ensuring the player can board even if no other player is nearby. After the freeze expires, the chunk is released and the sub-level can re-unload normally if no players are around.
- The `executePendingFetch` entry point uses the live orientation post-load (we don't cache orientation; it's already preserved by Sable's serialization).
- A claim with no cached metadata (pre-Phase-11 or never observed) reports `unloaded_unavailable` rather than triggering an inert force-load.

**Verification:**
1. Claim a ship, fly it outside the world border, walk away until it unloads — check `/sp info` shows the cached last-known position and `[unloaded]` annotation.
2. Run `/sp fetch <name>` from the same player → "Loading 'X' to fetch..." → within a couple of seconds, "Fetched 'X' to ...; frozen for 60s" → the ship is loaded at the destination and the player can board.
3. After the 60s freeze expires, walk far away again — the ship can unload normally.
4. Run `/sp fetch` against a ship whose cached position is *inside* the border → "already inside the world border" rejection.
5. Edge case: pre-Phase-11 claim that's never been observed loaded → `unloaded_unavailable` message.

---

### Phase 12: Ground Command + Config Cleanup
**Goal:** Add `/sp ground` as a backup landing option, generalize the absence-radius and freeze-duration config keys, and share the crew-presence check between commands.

**Deliverables:**
- `command/GroundCommand.java` — vertical-only teleport with snap-to-upright orientation, drops at `MOTION_BLOCKING_NO_LEAVES + 20` for collision tolerance, runs the standard freeze. Loaded path runs the teleport synchronously; unloaded path force-loads the plot chunk via `PendingFetchManager` and dispatches when the sub-level reappears. *(implemented)*
- `util/CrewPresence.findCrewWithinRadius(server, claim, pos, dimension, radiusSqr, excluding)` — shared absence test. Steal passes the issuer UUID for `excluding`; ground passes null. *(implemented; `StealCommand` refactored to use it)*
- `PendingFetchManager.Entry` extended with `@Nullable Quaterniondc orientationOverride` and `String successLangKey`. `FetchCommand.executePendingFetch` honors the override (falls back to live orientation when null) and uses the entry's lang key for the success message. *(implemented)*
- Info-window `[Ground]` button — visible to crew; lit (cyan, clickable) when no crew is within range, greyed (dark grey, non-clickable) otherwise. Hover shows the reason and a stability warning either way. *(implemented)*
- Config rename: `noManLand.stealAbsenceRadius` → top-level `absenceRadius`; `fetchFreezeDurationSeconds` → `freezeDurationSeconds`. *(implemented; this is a breaking change for existing user configs — they'll fall back to defaults on first read.)*

**Implementation notes:**
- The destination Y buffer is hardcoded at `+20` (`GroundCommand.GROUND_HEIGHT_BUFFER`) rather than configurable — the user can re-edit the value if they need it tighter, but this matches the "be generous, this is a backup" framing.
- Snap-to-upright is achieved via `new Quaterniond()` (identity quaternion). The orientation is locked into the freeze for the full duration so the ship doesn't immediately tip over post-teleport.
- The ground absence check does <em>not</em> exclude the issuer — even a crew member standing on the ship blocks ground. This is intentional per the design: `/sp ground` is a backup-only option for "I can't reach my ship", and a crew member who's nearby should be able to land it normally.
- The `[Ground]` button in the info window evaluates eligibility at info-window-render time. Since the info window is re-printed after every `/sp edit` mutation, the lit/greyed state stays roughly current.
- For unloaded sub-levels, the absence check uses the cached `lastKnownPosition` and `lastKnownDimension`. Crew who walked into the area while the ship was unloaded would still register correctly because we check against their <em>current</em> player positions, not cached values.

**Verification:**
1. Claim a ship, fly it up high, land via `/sp ground` while standing on board with no other crew nearby → should succeed (only-crew-member-present blocks the issuer? No — issuer counts. Re-test after walking 100+ blocks away.)
2. With another crew member standing nearby, attempt `/sp ground` → fails with `crew_present` message naming the blocker.
3. With all crew far away (or offline), run `/sp ground` → ship teleports straight down, snaps upright, freezes for 60s.
4. Walk far enough away to unload the ship, then run `/sp ground <name>` → `unloaded_loading` → load → ground succeeds.
5. Info window `[Ground]` button: greyed with hover text when crew is nearby, cyan + clickable when not.

---

### Phase 15: Audit Log
**Goal:** Append-only plain-text log of claim lifecycle events for admin/diagnostic purposes.

**Deliverables:**
- `audit/AuditLog.java` — opens `<server-root>/logs/sableprotect-audit.log` in append mode on `ServerStartedEvent`, closes on `ServerStoppingEvent`. Methods: `logCreate(server, name, uuid, actor, context)`, `logTransfer(server, name, uuid, fromUuid, toUuid, context)`, `logDelete(server, name, uuid, actor, context)`. *(implemented)*
- Hooks into command paths after successful state changes:
  - `ClaimCommand` → `CREATE context=command`
  - `ClaimUuidCommand` → `CREATE context=claimuuid`
  - `EditCommand.executeChangeOwner` → `TRANSFER context=changeowner`
  - `StealCommand.executeConfirmed` → `TRANSFER context=steal`
  - `UnclaimCommand.executeConfirmed` → `DELETE context=unclaim`
  - `ClaimObserver.onSubLevelRemoved(REMOVED)` → `DELETE context=destroyed`, `actor=<system>`
  - `ClaimObserver.applyInheritance` → `CREATE context=splitinheritance`, `actor=<system>` *(implemented)*

**Implementation notes:**
- Plain text instead of JSON Lines so the log is grep-friendly and matches `latest.log` conventions.
- Single growing file, no rotation. Admin can compress/archive manually if it grows large.
- Player names are resolved via `server.getPlayerList().getPlayer(uuid)` first, then `server.getProfileCache().get(uuid)`; falls back to bare UUID if neither is available (e.g., player has never logged in but is referenced as a claim member somehow).
- Failed commands are not logged. Only state changes that actually persist (in `ClaimRegistry` / `ClaimStorage`) generate audit entries.
- Sub-level destruction logs as `context=destroyed` regardless of whether it was disassembly or merge — Sable's `SubLevelRemovalReason.REMOVED` doesn't distinguish, and we don't always have a player context.
