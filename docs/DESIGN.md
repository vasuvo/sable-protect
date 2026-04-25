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
Covers placing and breaking individual blocks on the sub-level. If block protection is enabled, all explosions are also protected against.

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
<name>  [Locate]  [Fetch from Out of Bounds]*
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

Clickable buttons pre-type or auto-submit the corresponding `/sp` command. Non-owners see the protection toggles as static text rather than buttons. Members see Locate and Fetch but not the editing or membership buttons.

### `/sp locate <name>`
Returns the current coordinates of the named sub-level. Available to the owner and all members.

### `/sp fetch <name>`
If the named sub-level is outside the vanilla world border, teleports it to the nearest point just inside the border (approximately 50 blocks inward) and above ground at that location. Physics are then **completely frozen** for 1 minute, giving the owner time to board and shut off engines before the freeze expires. Available to the owner and all members.

### `/sp edit <name> blocks|interactions|inventories protected|unprotected`
Toggles the named protection category on or off. Owner only.

### `/sp edit <name> rename <newname>`
Renames the sub-level. The new name must not already be taken globally. Owner only.

### `/sp edit <name> changeowner <player>`
Transfers ownership of the sub-level to another player. Owner only.

### `/sp edit <name> addmember <player>`
Adds a player as a member. Owner only.

### `/sp edit <name> removemember <player>`
Removes a member. Owner only.

### `/sp unclaim <name>`
Prompts for confirmation. The owner must then run `/sp unclaim <name> CONFIRM` to complete the action. Removes all claim data from the sub-level.

---

## Debug / Admin Commands

These commands require OP (permission level 2) and are not available to regular players.

### `/sp debug`
Toggles debug mode for the issuing player. When enabled, sub-level lookups and protection checks display diagnostic information in chat (candidate sub-levels found, UUIDs, hit positions, etc.). Debug state is per-player and does not persist across server restarts.

### Admin bypass

Eligible admins can bypass all four protection categories — break/place blocks, interact with anything, open inventories, disassemble, merge — on any claim regardless of ownership. The bypass is **opt-in per session**: admins are subject to normal protection rules until they actively enable it via `/sp bypass`, and the toggle resets on server restart so an admin always starts with protection applied. To be eligible, the player's vanilla permission level must meet or exceed `adminBypassPermissionLevel` (default 4 — full ops only). Setting the config to 5 disables the bypass entirely.

### `/sp bypass`

OP-only (level 2 to run the command, but the actual bypass effect requires meeting `adminBypassPermissionLevel`). Toggles admin claim-protection bypass for the issuing player. State is per-player and does not persist across server restarts.

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
