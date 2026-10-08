# Nice Control Center

A web control center for Fabric servers (Minecraft 26.3). At its heart is a live performance monitor that answers one question: **what is slowing the server down, and why?** Around it: the server log with errors traced to data packs and mods, a console, automatic updates for mods, data packs and the server resource pack, and editors for server.properties and data pack settings.

It starts with the server and runs continuously (you can switch it off). It keeps the last hour and shows it in a web dashboard and in chat. Every cost is converted to the same unit, milliseconds per tick, so a data pack, a mod, a mob type and chunk loading can be compared directly. Each problem comes with a short explanation and a tip.

Works on dedicated servers and in singleplayer. Install it on the server only (it needs Fabric API).

## What it shows 

- **Server health:** TPS, MSPT (min, median, 95th percentile, max), CPU, memory, GC, players, chunks, entities and block entities. Shown live and over the last 1, 5, 15 and 60 minutes.
- **What's costing performance:** the main causes, ranked, each with the reason and a tip.
- **Where the tick goes:** data pack functions, mob processing, block entities, chunk management, pending block ticks, network & console, autosave and more.
- **Data packs:** time per data pack, per function and per command line, including how often a line runs per call. For example, `execute as @e[...]` matching 150 entities shows up as "about 150 executions per run".
- **Mods:** a Spark-style sampler looks at the server thread every 20 ms and credits each sample to the mod whose code was running. Mixins count for the mod that added them.
- **Entities and block entities:** time per type, how many are loaded, and the time each one takes.
- **World generation:** new chunks per minute and the time per step, structure and feature, grouped by data pack or mod. This runs on worker threads, so it doesn't slow the tick, but players wait for terrain.
- **Players:** ping, what's loaded around each player, and how much new terrain is generated near them.
- **Busiest chunks** and **redstone & block updates** (redstone clocks, piston machines, flowing liquids), each with a teleport command.
- **Lag spikes:** each slow tick with the phases, functions and entity types that made it slow.
- **Server settings:** checks view/simulation distance, gamerules, Java memory and garbage collector, with advice.
- **Bloat check** (on demand): scoreboard size, command storage, entity tags and the biggest files in the world folder.
- **Data pack settings:** the settings of installed data packs with an in-game settings menu (Explorer's Eden style, stored in `eden:settings`), laid out like their in-game menus: a tree of projects and menus on the left, the menu's entries as tiles, and one settings form at a time, plus a search across all settings. Labels come from the packs' own language files. Only what the pack's own menu offers can be changed, with the same choices and limits, and saving runs the menu's own confirm command. Every change is logged in the console.
- **In-game date and time** in the dashboard header: the Nice Actions calendar when it's installed, otherwise the Minecraft day counter.

## Admin tools

- **Errors & warnings:** server log warnings and errors, grouped by message and credited to the data pack or mod that caused them. A "since the last /reload" filter shows what a reload broke, and after a `/reload` with new errors operators get a chat message. Known harmless noise is marked; more can be hidden with `error_ignore`.
- **Console:** the live server log in the dashboard, filterable, plus a command line that runs commands with console rights (logged as "dashboard ran: …"). Both can be switched off in the config.
- **Player inspector** (Players tab): everyone who has played, with online players live (position, health, food, XP, game mode, ping, inventory, armor, ender chest) and offline players as last saved. With the Explorer's Eden player database (`players_storage`, default `eden:database`) it also shows homes, `/back`, last death, last grave with its contents, waypoints and race/class; with Get Off My Lawn, claims. Every location has a copyable `/tp`; online players can be messaged or kicked.
- **Client and x-ray hints:** online players show their client (Fabric, Vanilla, Lunar Client, …) as a badge, and the card lists mods recognised from the network channels they register (voice chat, minimaps, recipe viewers, …). Client-only mods and most cheat clients can't be seen this way. An "x-ray hint" badge marks players who dug unusually little stone per diamond or ancient debris, judged from the game's statistics and compared with the server's other players; the card shows the numbers. It's a hint, not proof.
- **Players:** private messages from the dashboard (players answer privately with the [Answer] button or `/nccreply`; nothing goes to public chat, and new answers get a badge), plus kick, ban and unban, also for offline players.
- **Scheduled commands** (Schedule tab): tasks with one or more commands that run once, every day, on chosen weekdays, every few minutes/hours, or **after another task has finished** (with a delay), so tasks can be chained (loops are refused). Inside a task, `wait 30s` / `wait 5m` / `wait 1h` lines and an optional pause between commands space them out, e.g. a restart countdown. Each task can be paused or run right away; the last run's output shows which commands worked. Runs missed while the server is off are skipped. Stored in `nicecontrolcenter/schedule.json`.
- **Gamerule editor:** every gamerule with its in-game name and description, grouped like in the game, changed ones marked with a reset link. Changes apply at once, like `/gamerule`.
- **server.properties editor:** every setting, grouped and explained, with the right input for each (switches, number limits, choices). Passwords and secrets are never shown. Saving goes through the server's own settings (so vanilla can't overwrite it later), keeps a backup in `nicecontrolcenter/backups/`, and most values take effect at the next restart.
- **Updates:** mods in `mods/` and data pack zips in the world's `datapacks/` folder are checked on Modrinth by file hash (no setup) and, if configured, on GitHub releases. By default new versions are downloaded, checked (checksum, same mod id, valid data pack) and installed when the server stops, so they take effect at the next start. The replaced files are kept in `nicecontrolcenter/updates/backup/` and can be rolled back. Updates that need a mod you don't have are held. No world backups are made. In singleplayer, updates are only checked, never installed.
- **Server resource pack:** set `resource_pack_source` (or type it in the Updates tab) to a direct `https://…/pack.zip` link, `github:owner/repo` (optionally `@tag` and `#part-of-file-name`) or `modrinth:project`. The mod finds the newest zip, checks it, works out its SHA-1 and puts link and SHA-1 into server.properties at the next restart, like the other updates. Plain links are only downloaded again when the server reports a change. Without a source, the link already in server.properties is watched and its SHA-1 kept right. A build script can trigger a check right away with the command from `/ncc updates webhook`.

## Alerts and reports

- **Lag alerts:** when MSPT stays above 45 or TPS below 18 for 15 seconds, operators get a chat message naming the main cause, and another one when it's over. Thresholds are in the config.
- **Automatic lag reports:** every lag episode is saved as a report, including the 5 minutes before it, so "it lagged at 3 am" can be looked at the next day. At most 10 per day.
- **Recordings:** `/ncc record start [minutes]` (or the dashboard button) records for up to 24 hours and saves a single HTML report that opens offline in any browser.
- **Compare:** in the dashboard, pick two reports (or a report and the live last 15 minutes) to see what got better or worse, e.g. after updating a data pack.

Reports are saved in `nicecontrolcenter/reports/` next to the server and can be opened and downloaded from the dashboard.

## Commands

`/nicecontrolcenter` (alias `/ncc`):

| Command | What it does |
| --- | --- |
| `/ncc` or `/ncc status [minutes]` | Health summary and the top causes. Hover a line for the reason and a tip. |
| `/ncc top datapacks\|functions\|mods\|entities\|blockentities\|chunks\|phases [minutes]` | Top lists |
| `/ncc bossbar` | Shows or hides a live TPS/MSPT boss bar (only for you, remembered across restarts) |
| `/ncc bloat` | Runs the bloat check |
| `/ncc monitor on\|off` | Switches the live monitor on or off. Nothing is measured while it's off, so there is no overhead. |
| `/ncc web` | Link to the dashboard |
| `/ncc web regen` | New private link; old links stop working |
| `/ncc web port <port>` | Moves the dashboard to another port |
| `/ncc record start [minutes]` / `stop` / `status` | Manual recordings |
| `/ncc updates` | What's new, waiting or held |
| `/ncc updates check` | Checks for updates now |
| `/ncc updates skip <entry>` | Skips the offered version of a mod or pack |
| `/nccreply <message>` | For every player: answer a dashboard message privately |
| `/ncc updates webhook [regen]` | Token and `curl` command for a build script to trigger a resource pack check |
| `/ncc updates rollback [backup]` | Puts the replaced versions back at the next restart (default: the latest backup) |

## Permissions

Everything is operator-only by default (level 2+). With LuckPerms or another Fabric permissions mod you can hand out parts of it:

| Node | Allows |
| --- | --- |
| `nicecontrolcenter.command.status` | `/ncc`, `/ncc status`, `/ncc top …`, `/ncc bossbar`, `/ncc bloat` |
| `nicecontrolcenter.command.web` | `/ncc web …` (dashboard link, new link, port) |
| `nicecontrolcenter.command.monitor` | `/ncc monitor on\|off` |
| `nicecontrolcenter.command.record` | `/ncc record …` |
| `nicecontrolcenter.command.updates` | `/ncc updates …` |
| `nicecontrolcenter.notify` | Lag alerts and "report ready" messages |

`nicecontrolcenter.command.*` or `nicecontrolcenter.*` grants everything.

## Dashboard

The mod serves the dashboard itself; nothing is uploaded anywhere. Open it with the link from `/ncc web`. The link contains a private token, so don't share it.

- **Singleplayer:** listens on `127.0.0.1` (only your computer).
- **Dedicated server:** listens on `0.0.0.0`. Make sure the port is open; on panel hosts, set `port` to one of your allocated ports. Set `public_url` so the printed link uses your server's address.
- Sections can be folded by clicking their title, and there is a light/dark switch. Both are remembered per browser.
- Useful link parameters: `?window=5` (1, 5, 15 or 60 minutes) and `?theme=light`.
- **With the Nice Control Center Panel** (Docker, see [panel/SETUP.md](../panel/SETUP.md)): the panel installs the mod, the dashboard listens only inside the container, and the panel shows it in its Dashboard tab with its own accounts and permissions. `port`, `bind`, `public_url` and the token don't matter there.

## Config

Changes to `config/nicecontrolcenter.json` are picked up within a few seconds while the server runs; the port, bind address and sampler interval need a restart.

In singleplayer, the settings can also be changed in game: with [Mod Menu](https://modrinth.com/mod/modmenu) installed, open Mods → Nice Control Center → Configure. Most changes apply the next time a world is opened.


`config/nicecontrolcenter.json` is created on first start with these defaults (the `token` is generated):

```json
{
  "monitor_enabled": true,
  "web_enabled": true,
  "bind": "auto",
  "port": 8765,
  "public_url": "",
  "token": "",
  "sampler_interval_ms": 20,
  "spike_threshold_ms": 100,
  "max_recording_hours": 24,
  "web_console": true,
  "web_console_commands": true,
  "web_settings_edit": true,
  "error_ignore": [],
  "update_mode": "auto",
  "update_check_hours": 24,
  "update_channels": ["release"],
  "update_github": {},
  "update_ignore": [],
  "update_keep_backups": 10,
  "resource_pack_source": "",
  "resource_pack_check_minutes": 10,
  "resource_pack_webhook_token": "",
  "web_properties_edit": true,
  "web_gamerules_edit": true,
  "web_player_actions": true,
  "web_schedule": true,
  "message_prefix": "[Admin]",
  "message_color": "gold",
  "players_storage": "eden:database",
  "alerts_enabled": true,
  "alert_mspt": 45.0,
  "alert_tps": 18.0,
  "alert_seconds": 15,
  "alert_cooldown_minutes": 10,
  "auto_record_lag": true,
  "auto_record_max_minutes": 30,
  "auto_record_per_day": 10
}
```

| Key | What it does |
| --- | --- |
| `monitor_enabled` | Run the monitor when the server starts. Also changed by `/ncc monitor on\|off`. |
| `web_enabled` | Serve the dashboard. |
| `bind` | `auto` = `127.0.0.1` in singleplayer and `0.0.0.0` on dedicated servers, or any address. |
| `port` | Dashboard port. If it's taken, the next 9 ports are tried. Also changed by `/ncc web port`. |
| `public_url` | Base address for printed links, e.g. `http://play.example.com:8765`. Empty = worked out automatically. |
| `token` | Private part of the dashboard link. Changed by `/ncc web regen`. |
| `sampler_interval_ms` | How often the mod sampler looks at the server thread. Higher = less overhead, less detail. |
| `spike_threshold_ms` | Ticks slower than this are listed as lag spikes. |
| `max_recording_hours` | Recordings stop on their own after this long. |
| `web_console` | Show the live server log in the dashboard. |
| `web_console_commands` | Allow running commands from the dashboard console (full console rights). |
| `web_settings_edit` | Allow changing data pack settings in the dashboard. When false, they are shown read-only. |
| `error_ignore` | Regular expressions for log messages the error watcher ignores. |
| `update_mode` | `auto` = download and install at the next restart, `stage` = download, install after approval in the dashboard, `check` = only show what's new, `off` = never check. |
| `update_check_hours` | Hours between update checks (there is also one a minute after start). |
| `update_channels` | Modrinth version types to accept: `release`, `beta`, `alpha`. |
| `update_github` | Update from GitHub releases instead: `{"<mod id or pack file name without .zip>": "owner/repo"}`. The release asset (`.jar` or `.zip`, preferably with the Minecraft version in its name) is used. |
| `update_ignore` | Mod ids or pack file names (without `.zip`) that are never updated. |
| `update_keep_backups` | How many backups of replaced versions to keep. |
| `resource_pack_source` | Where the server resource pack comes from (see above). Empty = watch the link in server.properties. |
| `resource_pack_check_minutes` | Minutes between resource pack checks. `0` = never. |
| `resource_pack_webhook_token` | Token for the build ping. Empty = off. Set by `/ncc updates webhook`. |
| `web_gamerules_edit` | Allow changing gamerules in the dashboard. |
| `web_player_actions` | Allow kicking and messaging players from the dashboard. |
| `web_schedule` | Allow creating and changing scheduled commands in the dashboard (they run with console rights). |
| `message_prefix` / `message_color` | Prefix of private messages from the dashboard and its color: a Minecraft color name (`gold`, `dark_purple`, `aqua`, …), a common name like `purple`, `pink` or `orange`, or `#RRGGBB`. |
| `players_storage` | Command storage with the player database shown in the Players tab (homes, graves, waypoints). Empty = none. |
| `web_properties_edit` | Allow changing server.properties in the dashboard. When false, it's shown read-only. |
| `alerts_enabled` | Lag alerts in chat. |
| `alert_mspt` / `alert_tps` | Lag means average MSPT above `alert_mspt` **or** TPS below `alert_tps`… |
| `alert_seconds` | …for this many seconds in a row. Recovery needs the same time under the limits. |
| `alert_cooldown_minutes` | Minimum time between two lag alerts. |
| `auto_record_lag` | Save a report of every lag episode, including the 5 minutes before it. |
| `auto_record_max_minutes` | Automatic lag reports stop after this long even if the lag continues. |
| `auto_record_per_day` | At most this many automatic lag reports per day. |

Players who turned on the boss bar are stored in `config/nicecontrolcenter-bossbar.json`.

## Overhead

Timing a step costs about 20 ns (two clock reads). A data pack running 30,000 command steps per tick costs about 0.7 ms/tick extra to measure; typical servers see much less. The sampler costs a fraction of a percent. Switch the monitor off with `/ncc monitor off` when you don't need it.

The mod numbers come from sampling, so they are estimates. Mixin wrappers around methods that run thousands of times per tick tend to look a bit more expensive than they are.

## Building and releases

```
cd mod
./gradlew build
```

Requires JDK 25. The jar ends up in `mod/build/libs/`.

GitHub Actions (`.github/workflows/ci.yml` in the repository root) builds the jar on every push and pull request that touches `mod/`. On `main` it also publishes a GitHub release: the version and Minecraft version come from `tools/release_infos.yml`, the release notes from `changelog.log`. Older releases for the same Minecraft version are removed. To release a new version, raise `Version number` and `Version subtitle` in `tools/release_infos.yml` and update `changelog.log`.
