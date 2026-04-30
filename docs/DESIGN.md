# sable-protect Design Document

## Overview

When a player assembles a Sable sub-level, they may claim it using `/sp claim`. A claimed sub-level is protected from unwanted interactions by outsiders. The owner controls which categories of interaction are permitted to non-members, can invite members who bypass those restrictions, and has access to secondary utilities (locating and fetching out-of-bounds ships).

---

## Protected Interaction Categories

There are four categories of protected interaction. Three are configurable toggles; one is a fixed restriction.

### Disassembly *(fixed — owner-only, not configurable)*
Covers any action that could result in the destruction or splitting of the sub-level:
- Triggering the Physics Assembler to disassemble the ship
- Placing merging glue on the sub-level

This restriction is always active and cannot be loosened — not even for members. Only the owner can perform these actions.

### Blocks *(configurable toggle)*
Covers placing and breaking individual blocks on the sub-level. If block protection is enabled, all explosions are also protected against, and contraption-mounted block-breakers (Create's mechanical drill, Create Simulated's rock cutting wheel) are blocked from drilling through claimed sub-levels — see *Contraption breakers* below.

### Interactions *(configurable toggle)*
Covers all standard block interactions not covered by another category (e.g., buttons, levers, crafting tables, furnaces, etc.). Doors and fence gates are always interactable regardless of this toggle, since they are used purely for player movement. Block placement is not affected by this toggle — it is controlled exclusively by the Blocks permission.

### Inventories *(configurable toggle)*
Covers interactions with storage containers: chests, barrels, and shulker boxes. Also includes interaction with the Create stock ticker and blaze burner, which provide access to vault contents and fuel respectively.

---

## Permission Roles

There are three roles:

| Role | Disassembly | Blocks | Interactions | Inventories | Locate / Fetch | Edit permissions / Manage members |
|---|---|---|---|---|---|---|
| **Owner** | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| **Member** | ✗ | ✓ | ✓ | ✓ | ✓ | ✗ |
| **Default** | ✗ | per toggle | per toggle | per toggle | ✗ | ✗ |

Members always bypass the three configurable toggles. The owner is the only one who can change permissions, add/remove members, rename, transfer ownership, or unclaim.

---

## Commands

All commands use the `/sp` prefix ("sable protect"). Commands that modify state re-print the info window after execution.

### `/sp claim <name>`
Claims the sub-level the player is currently looking at and assigns it a name. Requirements:
- The sub-level has a mass equal to or greater than 4.
- The player must be within normal interaction range of the sub-level (cannot claim a distant ship flying overhead).
- The name must be **globally unique** across the entire server — no two claimed sub-levels may share a name.
- The claimed sub-level's owner is set to the issuing player.

### `/sp myclaims`
Lists all sub-levels the player owns, followed by all sub-levels they are a member of.

### `/sp info [name]`
Opens the info window for the named sub-level. If no name is given, targets the sub-level the player is looking at.

Anyone may view the info window of any claimed sub-level. However, buttons are shown only if the viewing player has permission to use them — players without permission see plain text instead of clickable buttons for those fields.

**Info window layout:**

```
--------------------------------------------
<name>  [Fetch from Out of Bounds]*
Location: X, Y, Z**
Blocks:        [PROTECTED / UNPROTECTED]
Interactions:  [PROTECTED / UNPROTECTED]
Inventories:   [PROTECTED / UNPROTECTED]
               [Rename]  [Unclaim]

Owner: <name>  [Change Owner]
Members  [Add Member]:
  <name>  [Remove]
  <name>  [Remove]
--------------------------------------------
```

\* "Fetch from Out of Bounds" is only shown when the sub-level is currently outside the world border.

\*\* "Location" is shown to owners and members. When the sub-level is loaded the coordinates are live; when unloaded the line shows the last-known position (italic + grey). Clicking the coordinate text copies a space-separated `X Y Z` triple to the clipboard, suitable for pasting into `/tp` or similar commands.

Clickable buttons pre-type or auto-submit the corresponding `/sp` command. Non-owners see the protection toggles as static text rather than buttons. Members see Fetch but not the editing or membership buttons.

### `/sp ground <name>`
Emergency landing. Teleports the named sub-level **straight down** to the surface at its current XZ, snaps the orientation to upright, and freezes physics for the configured duration. The XZ coordinates are unchanged — this is a vertical-only drop. The drop point is the world's `MOTION_BLOCKING_NO_LEAVES` heightmap plus a generous `+20` block buffer to avoid collision artifacts on awkward hulls.

Available to the owner and all members. Requires the entire crew — *including the issuer and anyone currently on board* — to be outside `absenceRadius` blocks (default 100) of the ship; the command is intended as a backup option for when you literally can't reach your ship and is deliberately less polished than `/sp fetch`. Works on unloaded sub-levels using the same plot-chunk force-load mechanism as fetch.

The info window's `[Ground]` button reflects eligibility live: lit (cyan, clickable) when no crew is within range; greyed-out (with a hover explaining the reason and a stability warning) otherwise. `[Ground]` is hidden entirely when the ship is outside the world border — `[Fetch from Out of Bounds]` is shown in its place there, since fetching is strictly better than grounding for an out-of-bounds ship.

### `/sp fetch <name>`
If the named sub-level is outside the vanilla world border, teleports it to the nearest point just inside the border (approximately 50 blocks inward) and above ground at that location. Physics are then **completely frozen** for 1 minute, giving the owner time to board and shut off engines before the freeze expires. Available to the owner and all members.

Works on unloaded sub-levels too: if the cached last-known position is outside the world border, the mod force-loads the sub-level's plot chunk to bring it back online, runs the teleport + freeze, and holds the chunk loaded for the freeze duration so the player can board. The info-window `[Fetch from Out of Bounds]` button appears in this case as well. If the sub-level fails to load within ~15 seconds (e.g., the plot chunk is corrupted or has been deleted), the chunk force-load is released and a failure message is sent. A claim that has never been observed (no cached position) cannot be fetched while unloaded — load it once first.

### `/sp edit <name> blocks|interactions|inventories protected|unprotected`
Toggles the named protection category on or off. Owner only.

The three toggles are linked by an invariant: **a ship cannot have Blocks unprotected while Interactions or Inventories are protected.** This avoids nonsensical configurations like "anyone can break my ship's blocks but only members can press buttons." Toggle changes cascade to maintain it:
- *Unprotecting Blocks* automatically unprotects Interactions and Inventories.
- *Protecting Interactions* or *Protecting Inventories* automatically protects Blocks.
- *Protecting Blocks* leaves the others unchanged (any combination is then valid).
- *Unprotecting Interactions* or *Unprotecting Inventories* leaves the others unchanged.

### `/sp edit <name> rename <newname>`
Renames the sub-level. The new name must not already be taken globally. Owner only.

### `/sp edit <name> changeowner <player> [<player>]`
Transfers ownership of the sub-level to another player. Owner only. Two-step confirmation: `/sp edit <name> changeowner <player>` previews the change and asks the user to re-type the new owner's name; `/sp edit <name> changeowner <player> <player>` executes when the two names match exactly. The previous owner is demoted to a member so they retain access on the ship.

### `/sp edit <name> addmember <player>`
Adds a player as a member. Owner only.

### `/sp edit <name> removemember <player>`
Removes a member. Owner only.

### `/sp unclaim <name>`
Prompts for confirmation. The owner must then run `/sp unclaim <name> CONFIRM` to complete the action. Removes all claim data from the sub-level.

### `/sp steal <name>`
Steals ownership of a claimed sub-level inside No Man's Land. Two-step confirmation: `/sp steal <name>` previews the action, `/sp steal <name> CONFIRM` executes. All of the following must be true:
- The sub-level must currently be inside the No Man's Land rectangle.
- The player must be physically on board (riding or standing on the sub-level).
- No online owner or member may be within `absenceRadius` blocks (default 100) of the ship's center. Offline crew members and crew on a different dimension count as absent.
- The player isn't already the owner.

On success, ownership transfers to the issuing player, the member list is wiped, and the name + protection toggles are preserved. The previous owner and any prior members receive a red chat warning naming the player who took their ship; offline targets are not notified.

---

## Contraption breakers

Most ways of breaking a block flow through `BlockEvent.BreakEvent`, which the Blocks toggle covers via the standard event handler. Two block-breakers in this mod ecosystem don't:

- Create's **Mechanical Drill** when mounted on a moving contraption — destroys blocks via `BlockHelper.destroyBlockAs(level, pos, null, ...)`. The null player short-circuits the event.
- Create Simulated's **Rock Cutting Wheel** (driven by a Borehead Bearing) — submits candidate positions to a server-wide `MultiMiningServerManager` that aggregates progress and destroys blocks the same way, again with no player context.

Both are gated by the Blocks toggle (so an owner who unprotects Blocks accepts that breakers can drill into their ship). When Blocks is protected, a contraption-mounted drill or rock cutting wheel may break a block in a claimed sub-level only if:

1. Its host contraption is anchored on the **same** sub-level as the target — drilling your own ship is always allowed, including self-disassembly fail-safes.
2. Or its host sub-level is itself claimed, and that claim's owner is the owner-or-member of the target's claim — friendly excavators (e.g. a co-owner's mining ship) work as expected.
3. Otherwise the break is denied. For drills, the contraption stalls cleanly on the protected block and resumes movement past it; for rock cutting wheels, the position is rejected at intake before any progress accumulates.

Breakers anchored in the open world (no host sub-level — e.g. a stationary drill assembly on bedrock) cannot be attributed. They are denied by default; flip `allowExternalAnchorBreaking` in the config if you need stationary external mining setups to work against ships.

Other Create breakers (saws, ploughs, rollers, harvesters, deployers) are intentionally **not** restricted. Their use cases — on-ship farms, surface clearing, deployer-driven crafting — are valuable enough that the grief risk doesn't justify blocking them. The Deployer is the exception that's trivially handled: it uses a fake player and so does fire `BlockEvent.BreakEvent`, meaning it's already covered by the standard handler.

---

## Audit log

A plain-text append-only log of claim lifecycle events is written to `<server-root>/logs/sableprotect-audit.log`. One event per line, ISO-8601 UTC timestamp at the start. Events are logged only when the underlying state change has been persisted (no logging for failed commands).

**Logged events:**
- `CREATE` — `/sp claim`, `/sp claimuuid`, split-inheritance fragment.
- `TRANSFER` — `/sp edit changeowner`, `/sp steal`.
- `DELETE` — `/sp unclaim`, sub-level genuinely destroyed (Physics Assembler disassembly, merge consumption).

**Not logged:** rename, member add/remove, toggle protection, info views, failed commands.

**Example:**
```
2026-04-26T08:30:15Z CREATE     name="MyShip"  uuid=abc12345-...  actor=Vasuvo(uuid)  context=command
2026-04-26T08:31:42Z TRANSFER   name="MyShip"  uuid=abc12345-...  from=Vasuvo(uuid) to=Bob(uuid)  context=changeowner
2026-04-26T09:02:18Z TRANSFER   name="MyShip"  uuid=abc12345-...  from=Bob(uuid) to=Carl(uuid)    context=steal
2026-04-26T10:15:03Z DELETE     name="MyShip"  uuid=abc12345-...  actor=Carl(uuid)  context=unclaim
2026-04-26T11:00:55Z DELETE     name="OldShip" uuid=def...        actor=<system>    context=destroyed
```

The log is single-file (no rotation). Player names are resolved from online players first, then from the server's profile cache; if neither is available, only the UUID is recorded. Automatic events (split inheritance, sub-level destruction) use `<system>` for the actor since no player context is available at the lifecycle hook.

---

## Persistence

All claims are stored server-side in `<world>/data/sableprotect_claims.dat`, independent of any individual sub-level's chunk-load state. This means:

- `/sp myclaims`, `/sp info <name>`, and `/sp edit <name>` work for claimed sub-levels even when they're not currently loaded.
- Edits made while a sub-level is unloaded (rename, change owner, toggle changes, member changes) are applied to the sub-level's `userDataTag` automatically the next time it loads.
- A claim is only dropped when its sub-level is genuinely destroyed (disassembly, merge consumption, etc.) — a chunk unload, dimension change, or server restart never loses tracking.
- `/sp fetch` works on unloaded sub-levels by force-loading the cached plot chunk; `/sp steal` still requires the ship to already be loaded (it needs an on-board check). Both report a clear "not loaded" error if the cached metadata isn't available.
- The `Location` line in `/sp info` shows the last-known position even when the sub-level is unloaded — a snapshot is captured every time a sub-level is added to or removed from a container, so the displayed coordinates are accurate as of the most recent load/unload event.

The info window shows a grey `[unloaded]` annotation next to the title when a claim's sub-level isn't currently loaded so the player understands why the position-dependent buttons are missing.

---

## No Man's Land

A configurable rectangular XZ region inside which all claim protections are suspended and ships can be stolen via `/sp steal`. The region is defined by `noManLand.minX/maxX/minZ/maxZ` in `config/sableprotect-common.toml` and is gated by `noManLand.enabled` (default `false`, so existing servers don't suddenly gain a permission-free zone).

While a sub-level's center is inside the rectangle:
- **Blocks**, **Interactions**, **Inventories**, and **Disassembly** protections are all bypassed for everyone, regardless of the claim's toggles or membership.
- The info window shows a red `[NO MAN'S LAND]` annotation next to the claim name so players understand why protections aren't applying.
- Non-owners viewing the info window see a `[Steal]` button next to the title.
- The mixin-based packet protections (Physics Assembler trigger, merging glue, spring, steering wheel, throttle lever, rope break) are also suspended in NML, matching the event-based protections.

The claim itself isn't removed — it survives a trip through No Man's Land unchanged unless someone uses `/sp steal` while the crew is absent. A ship that re-enters protected airspace immediately resumes its claim restrictions.

---

## Debug / Admin Commands

These commands require OP (permission level 2) and are not available to regular players.

### `/sp debug`
Toggles debug mode for the issuing player. When enabled, sub-level lookups and protection checks display diagnostic information in chat (candidate sub-levels found, UUIDs, hit positions, etc.). Debug state is per-player and does not persist across server restarts.

### Admin bypass

Eligible admins can bypass all four protection categories — break/place blocks, interact with anything, open inventories, disassemble, merge — on any claim regardless of ownership. The bypass is **opt-in per session**: admins are subject to normal protection rules until they actively enable it via `/sp bypass`, and the toggle resets on server restart so an admin always starts with protection applied. Eligibility is checked via the LuckPerms node `sableprotect.bypass.use` (when LuckPerms is installed) or, as a fallback, the vanilla permission level configured by `adminBypassPermissionLevel` (default 4 — full ops only). Setting the config to 5 disables the bypass entirely.

### Permissions

OP-level features can be gated through LuckPerms when it's installed; otherwise they fall back to vanilla permission levels. LuckPerms decisions take precedence — granting a node enables the feature for non-OPs, denying it (e.g., `sableprotect.command.bypass = false`) revokes it even from OPs. When LuckPerms hasn't expressed an opinion (`UNDEFINED`), the vanilla level is consulted so existing op-only setups keep working without configuration.

| Node                            | Vanilla fallback level | What it gates                                                  |
| ------------------------------- | ---------------------- | -------------------------------------------------------------- |
| `sableprotect.command.debug`    | 2                      | `/sp debug` toggle                                             |
| `sableprotect.command.bypass`   | 2                      | `/sp bypass` command (still requires `sableprotect.bypass.use` to take effect) |
| `sableprotect.command.reload`   | 2                      | `/sp reload`                                                   |
| `sableprotect.command.claimuuid`| 2                      | `/sp claimuuid` (claim by UUID, override existing)              |
| `sableprotect.edit.override`    | 2                      | Edit any claim regardless of ownership (`/sp edit ...` on a claim you don't own) |
| `sableprotect.bypass.use`       | `adminBypassPermissionLevel` (default 4) | Eligibility for the actual claim-protection bypass effect |

### `/sp bypass`

OP-only (level 2 to run the command, but the actual bypass effect requires meeting `adminBypassPermissionLevel`). Toggles admin claim-protection bypass for the issuing player. State is per-player and does not persist across server restarts.

### `/sp reload`
OP-only. Force-reloads the mod's configuration from disk and resets in-memory caches. Useful after editing `config/sableprotect-common.toml` to apply the new values without restarting the server. NeoForge's file watcher normally picks up edits automatically; this command is a manual fallback that also re-reads the bundled language strings.

### `/sp claimuuid <uuid> <name> [<player> | owneruuid <owner-uuid>]`
Claims the sub-level with the given UUID, bypassing the look-based targeting used by `/sp claim`. Useful for claiming sub-levels that are difficult to target visually. The name must still be globally unique. Owner options:
- Omitted — defaults to the command sender.
- `<player>` — an online player (tab-completable).
- `owneruuid <uuid>` — a raw UUID for offline players, fake players, or server-owned claims.

---

## Known Issues

- **Interactions protection also blocks block placement.** When the Interactions toggle is protected, right-clicking a surface to place a block is also denied because the `RightClickBlock` event is canceled before the placement event can fire. This is low-priority — it is unlikely an owner would leave Blocks unprotected while keeping Interactions protected.
- **Physics Staff is not protected.** The Physics Staff (Simulated-Project) targets sub-levels by UUID via `PhysicsStaffActionPacket` / `PhysicsStaffDragPacket`, bypassing both `RightClickBlock` and the position-based protection checks. A non-owner can currently lock or drag any sub-level. Mitigation requires per-packet mixins similar to the others; this is a planned follow-up.

> **Previously documented bypasses now fixed via mixin** (see `mixin/sim/`): merging glue, spring, Physics Assembler activation, steering wheel, throttle lever, and rope-break packets are all intercepted server-side before their handlers mutate state.

---

## Sub-Level Lifecycle

### Splitting

When a claimed sub-level splits (due to block destruction creating disconnected regions), each new fragment **inherits the original sub-level's owner, members, and permission settings**. The original sub-level retains its data unchanged; fragments receive a copy of it, with a numbered suffix to differentiate it from the original and make sure all names stay unique.
If a split claim has a mass less than 4, it does not inherit parent permissions and becomes unclaimed (to prevent random claimed litter dotting the landscape).

### Merging

Merging is driven by the Simulated-Project Merging Glue block. The rules are:

- You may merge an **owned** sub-level with an **unclaimed** sub-level.
- You may **not** merge with a sub-level claimed by another player. Glue placement on another player's claimed sub-level is blocked (covered by the Disassembly restriction on that sub-level).
- When a merge completes, the heavier (surviving) sub-level keeps its claim data. The lighter (absorbed) sub-level is destroyed and its claim data is discarded.
