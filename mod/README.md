# Nice Control Center

**Nice Control Center** is a server-side Fabric mod by Explorer's Eden that adds a web dashboard for running a Minecraft server. At its core is a live performance monitor that answers one question: *what is slowing the server down, and why?* Around it are the tools an admin needs day to day: the server log with errors traced to the data pack or mod that caused them, a console, a copy of the in-game chat, a player inspector, scheduled commands, automatic updates for mods, data packs and the server resource pack, and editors for gamerules, `server.properties` and data pack settings.

The mod serves the dashboard itself. Nothing is uploaded anywhere.

| | |
| --- | --- |
| **Type** | Fabric mod (server side) |
| **Minecraft** | 26.1, 26.1.1, 26.1.2, 26.2, 26.3 |
| **Requires** | Fabric Loader 0.19.5+, Fabric API, Java 25 |
| **Optional** | [Mod Menu](https://modrinth.com/mod/modmenu) (settings screen in singleplayer), LuckPerms or another Fabric permissions mod |
| **Works with** | Dedicated servers and singleplayer worlds; the [Nice Control Center Panel](../panel/SETUP.md) (Docker) |
| **License** | GPL-3.0 |
| **Source** | [Explorers-Eden/Nice-Control-Center](https://github.com/Explorers-Eden/Nice-Control-Center) |

## Contents

1. [Installation](#installation)
2. [Getting started](#getting-started)
3. [The dashboard](#the-dashboard)
4. [Performance monitor](#performance-monitor)
5. [Admin tools](#admin-tools)
6. [Alerts and reports](#alerts-and-reports)
7. [Commands](#commands)
8. [Permissions](#permissions)
9. [Configuration](#configuration)
10. [With the Nice Control Center Panel](#with-the-nice-control-center-panel)
11. [Files](#files)
12. [Performance impact](#performance-impact)
13. [Troubleshooting](#troubleshooting)
14. [Development](#development)

## Installation

1. Download the jar for your Minecraft version from the [releases](https://github.com/Explorers-Eden/Nice-Control-Center/releases). Each release has one jar per Minecraft version (`…-mc26.3.jar`, `…-mc26.2.jar`, `…-mc26.1.jar`; the 26.1 jar covers 26.1, 26.1.1 and 26.1.2).
2. Put it into the server's `mods/` folder together with Fabric API.
3. Start the server. The mod creates `config/nicecontrolcenter.json` and starts monitoring right away.

Players don't need the mod. In singleplayer, put it into your client's `mods/` folder; the dashboard then runs while a world is open.

## Getting started

Run `/ncc web` in game or in the server console. It prints a link to the dashboard; open it in any browser. The link contains a private token, so don't share it.

By default the token is **renewed at every server start**, so a dashboard link only works until the next restart. To keep links working longer, set `token_hours` (see [Configuration](#configuration)). `/ncc web regen` makes a new link at any time.

Where the dashboard listens:

- **Singleplayer:** on `127.0.0.1`, so only your own computer can open it.
- **Dedicated server:** on `0.0.0.0`, port `8765`. Make sure the port is reachable. On a hosting panel, set `port` to one of your allocated ports. Set `public_url` so the printed link uses your server's address.

For a quick look without a browser, `/ncc` shows the server's health and the top causes of lag in chat.

## The dashboard

The top bar stays in view while you scroll. Its first row holds the in-game date and time (the Nice Actions calendar when it's installed, otherwise the Minecraft day), the live status and the light/dark switch. Its second row holds the stat cards: TPS, MSPT (min, median, 95th percentile, max), CPU, memory, garbage collection, entities, block entities, chunks and players. They always show the server right now. Hover a card for details. On phones the cards form one row that scrolls sideways.

Below it, a banner sums up the server's health and lets you pick the time window (1, 5, 15 or 60 minutes) for the charts and lists.

| Tab | What's in it |
| --- | --- |
| **Overview** | Charts over time and the main causes of lag, each with the reason and a tip |
| **Performance** | Where the tick goes, data packs and functions, mods, entities, block entities, lag spikes |
| **World** | World generation, busiest chunks, redstone and block updates, players' surroundings |
| **Players** | The player inspector, messages, kick and ban |
| **Server** | Errors and warnings, server.properties, gamerules, settings advice, bloat check |
| **Data packs** | The settings of installed data packs |
| **Updates** | Mod, data pack and resource pack updates |
| **Console** | The live server log and a command line |
| **Chat** | A copy of the in-game chat |
| **Schedule** | Scheduled commands |
| **Reports** | Recordings, automatic lag reports and comparisons |

Sections can be folded by clicking their title. Folded sections and the theme are remembered per browser. Useful link parameters: `?window=5` (1, 5, 15 or 60 minutes) and `?theme=light`.

## Performance monitor

The monitor starts with the server and keeps the last hour. Every cost is converted to the same unit, **milliseconds per tick**, so a data pack, a mod, a mob type and chunk loading can be compared directly.

- **Server health:** TPS, MSPT, CPU, memory and GC, live and over the chosen window.
- **What's costing performance:** the main causes, ranked, each with the reason and a tip.
- **Where the tick goes:** data pack functions, mob processing, block entities, chunk management, pending block ticks, network and console, autosave and more.
- **Data packs:** time per data pack, per function and per command line, including how often a line runs per call. For example, `execute as @e[...]` matching 150 entities shows up as "about 150 executions per run".
- **Mods:** a Spark-style sampler looks at the server thread every 20 ms and credits each sample to the mod whose code was running. Mixins count for the mod that added them.
- **Entities and block entities:** time per type, how many are loaded, and the time each one takes.
- **World generation:** new chunks per minute and the time per step, structure and feature, grouped by data pack or mod. World generation runs on worker threads, so it doesn't slow the tick, but players wait for terrain.
- **Players:** ping, what's loaded around each player, and how much new terrain is generated near them.
- **Busiest chunks** and **redstone and block updates** (redstone clocks, piston machines, flowing liquids), each with a teleport command.
- **Lag spikes:** each slow tick with the phases, functions and entity types that made it slow.
- **Settings advice:** checks view and simulation distance, gamerules, Java memory and the garbage collector.
- **Bloat check** (on demand): scoreboard size, command storage, entity tags and the biggest files in the world folder.

## Admin tools

### Errors and warnings

Warnings and errors from the server log, grouped by message and credited to the data pack or mod that caused them. A "since the last /reload" filter shows what a reload broke, and after a `/reload` with new errors operators get a chat message. Known harmless noise is marked; more can be hidden with `error_ignore`.

### Console

The live server log, filterable by level and searchable, plus a command line that runs commands with console rights. Commands run from the dashboard are logged as "Dashboard ran: …". Both can be switched off (`web_console`, `web_console_commands`).

### Chat

A copy of what players see in their chat window, with Minecraft's colors and formatting: player chat, `/say`, `/me`, `/msg`, `tellraw`, command feedback, joins, leaves, deaths, advancements and Discord messages (from Discord: JustSync or the panel's Discord bridge). A message sent to many players at once is one line. Messages only some players got show who got them, and can be hidden with **Private messages**. Filters: player chat, Discord, events, server messages. A box at the bottom sends a message to everyone as `/say`. The chat is kept in memory since the server started and follows the console settings.

### Player inspector

Everyone who has played: online players live (position, health, food, XP, game mode, ping, inventory, armor, ender chest), offline players as last saved. Hover an item for its name, enchantments and id. With the Explorer's Eden player database (`players_storage`, default `eden:database`) it also shows homes, `/back`, the last death, the last grave with its contents, waypoints, and race and class. With Get Off My Lawn installed, it shows claims too. Every location has a copyable `/tp`.

- **Messages:** private messages from the dashboard. Players answer privately with the [Answer] button or `/nccreply`; nothing goes to public chat. New answers get a badge.
- **Moderation:** kick, ban and unban, also for offline players.
- **Client and x-ray hints:** online players show their client (Fabric, Vanilla, Lunar Client, …) and the mods recognised from the network channels they register (voice chat, minimaps, recipe viewers, …). Client-only mods and most cheat clients can't be seen this way. An "x-ray hint" badge marks players who dug unusually little stone per diamond or ancient debris compared with the server's other players. It's a hint, not proof.

### Scheduled commands

Tasks with one or more commands that run once, every day, on chosen weekdays, every few minutes or hours, or **after another task has finished** (with a delay), so tasks can be chained; loops are refused. `wait 30s`, `wait 5m` or `wait 1h` lines and an optional pause between commands space them out, for example for a restart countdown. Each task can be paused or run right away, and the last run's output shows which commands worked. Runs missed while the server is off are skipped.

### Editors

- **Gamerules:** every gamerule with its in-game name and description, grouped like in the game. Changed ones are marked with a reset link. Changes apply at once.
- **server.properties:** every setting, grouped and explained, with the right input for each. Passwords and secrets are never shown. Saving goes through the server's own settings and keeps a backup. Most values take effect at the next restart.
- **Data pack settings:** the settings of data packs with an in-game settings menu (Explorer's Eden style, stored in `eden:settings`), laid out like their menus, with a search across all settings. Only what a pack's own menu offers can be changed, with the same choices and limits, and saving runs the menu's own confirm command.

### Updates

Mods in `mods/` and data pack zips in the world's `datapacks/` folder are checked on Modrinth by file hash (no setup needed) and, if configured, on GitHub releases. By default, new versions are downloaded, checked (checksum, same mod id, valid data pack) and installed when the server stops, so they take effect at the next start. Replaced files are kept and can be rolled back. Updates that need a mod you don't have are held back. No world backups are made. In singleplayer, updates are only checked, never installed.

**Server resource pack:** set `resource_pack_source` (or type it in the Updates tab) to a direct `https://…/pack.zip` link, `github:owner/repo` (optionally `@tag` and `#part-of-file-name`) or `modrinth:project`. The mod finds the newest zip, checks it, and writes its link and SHA-1 into server.properties at the next restart. Without a source, the link already in server.properties is watched and its SHA-1 kept right. A build script can start a check right away with the command from `/ncc updates webhook`.

## Alerts and reports

- **Lag alerts:** when MSPT stays above 45 or TPS below 18 for 15 seconds, operators get a chat message naming the main cause, and another when it's over.
- **Automatic lag reports:** every lag episode is saved as a report, including the 5 minutes before it, so "it lagged at 3 am" can be looked at the next day. At most 10 per day.
- **Recordings:** `/ncc record start [minutes]` (or the dashboard button) records for up to 24 hours and saves a single HTML report that opens offline in any browser.
- **Compare:** pick two reports, or a report and the live last 15 minutes, to see what got better or worse, for example after updating a data pack.

## Commands

`/nicecontrolcenter`, short `/ncc`:

| Command | What it does |
| --- | --- |
| `/ncc` or `/ncc status [minutes]` | Health summary and the top causes. Hover a line for the reason and a tip. |
| `/ncc top datapacks\|functions\|mods\|entities\|blockentities\|chunks\|phases [minutes]` | Top lists |
| `/ncc bossbar` | Shows or hides a live TPS/MSPT boss bar (only for you, remembered across restarts) |
| `/ncc bloat` | Runs the bloat check |
| `/ncc monitor on\|off` | Switches the monitor on or off. Nothing is measured while it's off. |
| `/ncc web` | Link to the dashboard |
| `/ncc web regen` | New private link; old links stop working |
| `/ncc web port <port>` | Moves the dashboard to another port |
| `/ncc record start [minutes]` / `stop` / `status` | Manual recordings |
| `/ncc updates` | What's new, waiting or held |
| `/ncc updates check` | Checks for updates now |
| `/ncc updates skip <entry>` | Skips the offered version of a mod or pack |
| `/ncc updates rollback [backup]` | Puts the replaced versions back at the next restart (default: the latest backup) |
| `/ncc updates webhook [regen]` | Token and `curl` command for a build script to start a resource pack check |
| `/nccreply <message>` | For every player: answer a dashboard message privately |

## Permissions

Everything is for operators (level 2 and up) by default. With LuckPerms or another Fabric permissions mod, parts can be handed out:

| Node | Allows |
| --- | --- |
| `nicecontrolcenter.command.status` | `/ncc`, `/ncc status`, `/ncc top …`, `/ncc bossbar`, `/ncc bloat` |
| `nicecontrolcenter.command.web` | `/ncc web …` (dashboard link, new link, port) |
| `nicecontrolcenter.command.monitor` | `/ncc monitor on\|off` |
| `nicecontrolcenter.command.record` | `/ncc record …` |
| `nicecontrolcenter.command.updates` | `/ncc updates …` |
| `nicecontrolcenter.notify` | Lag alerts and "report ready" messages |

`nicecontrolcenter.command.*` or `nicecontrolcenter.*` grants everything. Anyone with a valid dashboard link can use the whole dashboard, within the limits set in the config.

## Configuration

Settings live in `config/nicecontrolcenter.json`. Changes are picked up within a few seconds while the server runs; `port`, `bind` and `sampler_interval_ms` need a restart. In singleplayer, the settings can also be changed in game through Mod Menu (Mods → Nice Control Center → Configure).

### Dashboard

| Key | Default | What it does |
| --- | --- | --- |
| `web_enabled` | `true` | Serve the dashboard. |
| `bind` | `"auto"` | `auto` = `127.0.0.1` in singleplayer and `0.0.0.0` on dedicated servers, or any address. |
| `port` | `8765` | Dashboard port. If it's taken, the next 9 ports are tried. Also changed by `/ncc web port`. |
| `public_url` | `""` | Base address for printed links, e.g. `http://play.example.com:8765`. Empty = worked out automatically. |
| `token` | made by the mod | Private part of the dashboard link. |
| `token_hours` | `0` | Hours a dashboard link works before the mod replaces its token, also across restarts. `0` = a new token at every server start. |
| `token_created` | kept by the mod | When the current token was made. |
| `web_console` | `true` | Show the server log (Console tab) and the chat (Chat tab). |
| `web_console_commands` | `true` | Allow commands from the console and sending messages from the Chat tab (console rights). |
| `web_settings_edit` | `true` | Allow changing data pack settings. When off, they are shown read-only. |
| `web_properties_edit` | `true` | Allow changing server.properties. When off, it's shown read-only. |
| `web_gamerules_edit` | `true` | Allow changing gamerules. |
| `web_player_actions` | `true` | Allow messaging, kicking and banning players. |
| `web_schedule` | `true` | Allow creating and changing scheduled commands (they run with console rights). |
| `message_prefix` | `"[Admin]"` | Prefix of private messages from the dashboard. |
| `message_color` | `"gold"` | Color of that prefix: a Minecraft color name (`gold`, `dark_purple`, `aqua`, …), a common name like `purple`, `pink` or `orange`, or `#RRGGBB`. |
| `players_storage` | `"eden:database"` | Command storage with the player database shown in the Players tab. Empty = none. |

### Monitor

| Key | Default | What it does |
| --- | --- | --- |
| `monitor_enabled` | `true` | Run the monitor when the server starts. Also changed by `/ncc monitor on\|off`. |
| `sampler_interval_ms` | `20` | How often the mod sampler looks at the server thread. Higher = less overhead, less detail. |
| `spike_threshold_ms` | `100` | Ticks slower than this are listed as lag spikes. |
| `max_recording_hours` | `24` | Recordings stop on their own after this long. |
| `error_ignore` | `[]` | Regular expressions for log messages the error watcher ignores. |

### Alerts and lag reports

| Key | Default | What it does |
| --- | --- | --- |
| `alerts_enabled` | `true` | Lag alerts in chat. |
| `alert_mspt` / `alert_tps` | `45` / `18` | Lag means average MSPT above `alert_mspt` **or** TPS below `alert_tps`… |
| `alert_seconds` | `15` | …for this many seconds in a row. Recovery needs the same time under the limits. |
| `alert_cooldown_minutes` | `10` | Minimum time between two lag alerts. |
| `auto_record_lag` | `true` | Save a report of every lag episode, including the 5 minutes before it. |
| `auto_record_max_minutes` | `30` | Automatic lag reports stop after this long even if the lag continues. |
| `auto_record_per_day` | `10` | At most this many automatic lag reports per day. |

### Updates

| Key | Default | What it does |
| --- | --- | --- |
| `update_mode` | `"auto"` | `auto` = download and install at the next restart, `stage` = download and install after approval in the dashboard, `check` = only show what's new, `off` = never check. |
| `update_check_hours` | `24` | Hours between update checks (plus one a minute after start). |
| `update_channels` | `["release"]` | Modrinth version types to accept: `release`, `beta`, `alpha`. |
| `update_github` | `{}` | Update from GitHub releases instead: `{"<mod id or pack file name without .zip>": "owner/repo"}`. The release asset with the Minecraft version in its name is preferred. |
| `update_ignore` | `[]` | Mod ids or pack file names (without `.zip`) that are never updated. |
| `update_keep_backups` | `10` | How many backups of replaced versions to keep. |
| `resource_pack_source` | `""` | Where the server resource pack comes from (see [Updates](#updates)). Empty = watch the link in server.properties. |
| `resource_pack_check_minutes` | `10` | Minutes between resource pack checks. `0` = never. |
| `resource_pack_webhook_token` | `""` | Token for the build script ping. Empty = off. Set by `/ncc updates webhook`. |

## With the Nice Control Center Panel

The [Nice Control Center Panel](../panel/SETUP.md) runs a Fabric server in Docker and manages it from outside: start and stop, backups, schedules, files, versions, a world trimmer, a web map and a Discord bridge. When the panel runs the server:

- The panel installs and updates the mod.
- The dashboard only listens inside the container. The panel shows it in its Dashboard tab, with its own accounts and permissions, so `port`, `bind`, `public_url` and the token don't matter.
- `/ncc web` links open the panel.
- The mod checks at login whether a player may join (Discord account linking), reports chat and game events for the Discord bridge, and shows Discord messages in the Chat tab.
- The panel's map uses the mod for block colors and overlays (players, world border, claims, waypoint hubs), and the panel pregenerates terrain through the mod.

## Files

| Path | Contents |
| --- | --- |
| `config/nicecontrolcenter.json` | Settings |
| `config/nicecontrolcenter-bossbar.json` | Players who turned on the boss bar |
| `nicecontrolcenter/reports/` | Recordings and automatic lag reports (HTML) |
| `nicecontrolcenter/schedule.json` | Scheduled commands |
| `nicecontrolcenter/backups/` | Backups of server.properties made by the editor |
| `nicecontrolcenter/updates/backup/` | Mod and data pack versions replaced by updates |

## Performance impact

Timing a step costs about 20 ns (two clock reads). A data pack running 30,000 command steps per tick costs about 0.7 ms per tick extra to measure; typical servers see much less. The sampler costs a fraction of a percent. `/ncc monitor off` switches measuring off completely when you don't need it.

Mod numbers come from sampling, so they are estimates. Mixin wrappers around methods that run thousands of times per tick tend to look a bit more expensive than they are.

## Troubleshooting

- **The link doesn't open:** the port isn't reachable from your browser. Open it in your firewall or hosting panel, or set `port` to an allocated port. Check the server log for the port the dashboard really uses.
- **"Open the dashboard with the link from /ncc web":** the token in your link is outdated. Without `token_hours` a new one is made at every restart; run `/ncc web` again.
- **The printed link has the wrong address:** set `public_url`.
- **Nothing is measured:** the monitor is off; switch it on with `/ncc monitor on` or the button in the top bar. An empty server pauses itself after `pause-when-empty-seconds` (server.properties), so there's little to measure until someone joins.
- **Commands or editing are missing in the dashboard:** they're switched off in the config (`web_console_commands`, `web_settings_edit`, …).

## Development

```
cd mod
./gradlew build
```

Requires JDK 25. The jar ends up in `mod/build/libs/`. It builds for Minecraft 26.3 by default; `./gradlew build -Pmc_target=26.2` (or `26.1`) builds for an older version. The versions per target are in `versions/<target>.properties`, and the few classes that differ in `src/mc/<target>/java`.

GitHub Actions (`.github/workflows/ci.yml` in the repository root) builds the jar on every push and pull request that touches `mod/`. On `main` it also publishes a GitHub release: the version and Minecraft versions come from `tools/release_infos.yml`, the release notes from `changelog.log`. To release a new version, raise `Version number` and `Version subtitle` in `tools/release_infos.yml` and add the changes to `changelog.log`.
