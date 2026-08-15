# PokeLogger

A CoreProtect-style logger for Pixelmon 9.3.16. Logs trades, /pokegift transfers, Pokémon deletions, captures, evolutions, and held-item changes to a local SQLite database, with a `/plr lookup <player>` command to review history.

## Contribution
Strict code format is enforced using **Spotless** paired with **Palantir Java Format**.

Clean your code locally before pushing:

```bash
./gradlew spotlessApply
```

## How this works

Pixelmon fires its own NeoForge events for exactly these actions:

| Action | Event |
| - | - |
| Trade | `PixelmonTradeEvent.Post`
| /pokegift | `PokegiftEvent`
| Deletion/loss | `PixelmonDeletedEvent`
| Capture | `CaptureEvent.SuccessfulCapture`
| Evolution | `EvolveEvent.Post`
| Held item | `HeldItemChangedEvent.Post`

Full event list: <https://reforged.gg/docs/1211/com/pixelmonmod/pixelmon/api/events/package-summary.html>

## Commands & Permissions

| Command | Permission Node | Description |
| - | - | - |
| `/plr lookup <query>` | `pokelogger.lookup` | Search and view transaction logs for a player. (Alias: `/plr l`) |
| `/plr rollback <player> [index] [confirm]` | `pokelogger.rollback` / `pokelogger.restore` | Preview and restore a released/deleted Pokémon back to player party. |
| `/plr undo <player>` | `pokelogger.undo` | Shortcut to preview rollback for the most recent deletion (`index: 1`). |
| `/plr purge <duration> [confirm]` | `pokelogger.purge` | Delete database entries older than a specified duration. |
| `/plr export <player> [query]` | `pokelogger.export` | Export a player's logs to a `.txt` file in `config/pokelogger/exports/`. |
| `/plr reload` | `pokelogger.reload` | Reload the database connection without restarting the server. |
| `/plr status` | `pokelogger.status` | View total database entries and file size on disk. |
| `/plr help` | `pokelogger.help` | View in-game command documentation. |

### Lookup Query Syntax (`/plr l` / `/plr lookup`)

Lookups require a **player** and a **time frame (`t:`)**. Query tokens can be provided in any order.

* **Player:** `u:<player>` or `user:<player>` (or plain username if first argument)
* **Time Range (Required):** `t:<duration>` or `time:<duration>` (e.g., `30m`, `6h`, `1d`, `7d`, `365d`)
* **Actions:** `a:<action>` or `action:<action>` (comma-separated for multiples)
* `trade` - Pokémon trades
* `gift` - Pokémon gifts
* `capture` / `catch` - Wild captures
* `evolve` / `evolution` - Evolutions
* `delete` / `release` - Released or command-deleted Pokémon
* `helditem` / `held_item` - Held item changes
* `pokemon` - Captures, releases, gifts, and trades
* `+pokemon` / `-pokemon` - Pokémon gains or losses
* `+helditem` / `-helditem` - Held item gains or losses

* **Search / Filter:** `i:<text>` or `include:<text>` (matches species, nickname, or item name)
* **Limit Results:** `c:<count>` or `count:<count>` (default: `25`, max: `200`)
* **Page Navigation:** `p:<page>` or `page:<page>` (results are displayed `8` per page)

#### Examples

* `/plr l Ash t:1d` - View all actions for user "Ash" from the last 24 hours.
* `/plr l u:Ash a:trade,gift t:7d` - Search trades and gifts over the last 7 days.
* `/plr l Ash a:+pokemon i:charizard t:30d` - Check how Ash obtained a Charizard in the last 30 days.
* `/plr l Ash t:1d c:50` - Query up to 50 entries across pages.

### Restoring Pokémon (`/plr rollback` & `/plr undo`)

* **Requirements:** Target player must be **online** and have an **empty party slot**.
* **Previewing:** Running `/plr rollback <player> [index]` without `confirm` displays an interactive clickable chat prompt with Pokémon details (Level, Species, Shiny status, Deletion timestamp).
* **Confirming:** Click the chat prompt or append `confirm` to the command (requires `pokelogger.restore`).

```text
/plr rollback Ash 1
/plr rollback Ash 1 confirm
/plr undo Ash

```

### Maintenance & Exports

* **Purge Data:**
```text
/plr purge 90d
/plr purge 90d confirm

```


* **Export Logs:**
```text
/plr export Ash t:30d

```

## What's stubbed out

- **Rollback**-`PixelmonDeletedEvent` fires with the full Pokémon NBT still
 intact, so we snapshot it into the `nbt_snapshot` column. Actually writing
 a `/plr rollback <uuid>` command that reconstructs and re-inserts that

 - Pokémon into the player's PC is intentionally left out - it needs decisions
 about which storage API to write into and a confirmation flow (like
 CoreProtect's rollback preview), which is a project of its own.
- **GUI-based trade cancellation / permission nodes**- only op-level (`/plr`
 requires permission level 2) is wired up. Swap `hasPermission(2)` for a
 proper permission-mod integration (LuckPerms etc.) if you want finer control.
