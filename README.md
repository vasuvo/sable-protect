# Sable Protect

Server-side claim and protection for [Sable](https://github.com/RyanHCode/Sable) airships on NeoForge 1.21.1.

Players claim a sub-level by name; the mod prevents anyone but the owner and members from breaking blocks, interacting with controls, opening containers, or disassembling/merging the ship. Per-claim toggles let the owner relax individual categories of protection. Contraption-mounted breakers (Create's mechanical drill, Create Simulated's rock cutting wheel) are also blocked from chewing through claimed ships unless they're operated from a friendly contraption.

## Installation

Drop the jar into your server's `mods/` folder. Required: NeoForge 1.21.1, [Sable](https://github.com/RyanHCode/Sable), [Simulated-Project](https://github.com/Simulated-Team/Simulated-Project), [Veil](https://github.com/FoundryMC/Veil). Optional: [LuckPerms](https://luckperms.net/).

## Commands

All commands are prefixed with `/sp`.

### Player commands

| Command | Description |
| --- | --- |
| `/sp claim <name>` | Claim the sub-level you're looking at. |
| `/sp unclaim <name> [CONFIRM]` | Remove a claim (two-step). |
| `/sp info [name]` | Show the info window for a claim. With no argument, targets the sub-level you're looking at. |
| `/sp myclaims` | List claims you own or are a member of. |
| `/sp edit <name> <action>` | Modify a claim — see subcommands below. |
| `/sp fetch <name>` | Bring an out-of-bounds sub-level back inside the world border (60s freeze). Works on unloaded ships. |
| `/sp ground <name>` | Emergency drop straight down to the surface (60s freeze). Backup-only — see `[Ground]` for usage rules. |
| `/sp steal <name> [CONFIRM]` | Take ownership of an unattended sub-level inside No Man's Land. |

### `/sp edit` subcommands

| Command | Description |
| --- | --- |
| `/sp edit <name> blocks\|interactions\|inventories protected\|unprotected` | Toggle a protection category. |
| `/sp edit <name> rename <newname>` | Rename the claim. |
| `/sp edit <name> changeowner <player> [<player>]` | Transfer ownership. Two-step confirm — type the new owner's name twice. The previous owner is demoted to a member so they keep access. |
| `/sp edit <name> addmember <player>` | Add a member. |
| `/sp edit <name> removemember <player>` | Remove a member. |

### Window commands

Buttons rendered inline in `/sp info`. Visibility and lit/greyed state depend on role and context.

| Button | Visible to | Notes |
| --- | --- | --- |
| Coordinates | Crew | Click-copies as a space-separated `X Y Z` triple. Live when loaded, italic grey when unloaded. |
| `[Fetch from Out of Bounds]` | Crew | Only when the ship is outside the world border (live or cached position). |
| `[Ground]` | Crew | Lit only when no crew is within the absence radius. Hover always includes a stability warning. |
| `[Steal]` | Non-owners | Only inside No Man's Land. Lit only when on board AND the crew is absent. |
| `[Rename]`, `[Unclaim]` | Owner | |
| `[Change Owner]`, `[Add Member]`, `[Remove]` | Owner | Membership management. |
| `[PROTECTED]` / `[UNPROTECTED]` | Owner | Toggle for the corresponding protection category. |

### Admin commands

OP-level by default. With LuckPerms installed, each maps to a permission node — see `docs/DESIGN.md`.

| Command | Description |
| --- | --- |
| `/sp claimuuid <uuid> <name> [<player> \| owneruuid <uuid>]` | Claim a sub-level by UUID, with an optional explicit owner. Useful for sub-levels that are difficult to target visually. |
| `/sp debug` | Toggle debug output for the issuing player. |
| `/sp bypass` | Toggle the admin claim-protection bypass for the current session. Resets on server restart. |
| `/sp reload` | Force-reread the config file and refresh internal caches. |

## Documentation

- `docs/DESIGN.md` — feature requirements, UX, permission nodes, audit log, persistence model.
- `docs/TECHNICAL_SPEC.md` — implementation phases, data model, package structure.

## License

LGPL-2.1. See [LICENSE](LICENSE).
