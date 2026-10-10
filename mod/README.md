# Nice Control Center

A web dashboard for Fabric servers that shows what's causing lag and why, with console, chat, player tools, scheduled tasks and auto-updates.

**Nice Control Center** is a server-side Fabric mod by Explorer's Eden. You install it on the server, type `/ncc web`, and get a link to a dashboard you open in your browser. The dashboard is served by the mod itself; nothing is uploaded anywhere.

**[⬇ Download the latest release](https://github.com/Explorers-Eden/Nice-Control-Center/releases/latest)**

| | |
| --- | --- |
| **Type** | Fabric mod, server side only (players don't need it) |
| **Minecraft** | 26.1, 26.1.1, 26.1.2, 26.2, 26.3 |
| **Requires** | Fabric Loader 0.19.5+, Fabric API, Java 25 |
| **Optional** | [Mod Menu](https://modrinth.com/mod/modmenu) (settings screen in singleplayer), LuckPerms or another Fabric permissions mod |
| **Works with** | Dedicated servers, singleplayer worlds, the [Nice Control Center Panel](../panel/SETUP.md) (Docker) |
| **License** | GPL-3.0 |
| **Source** | [Explorers-Eden/Nice-Control-Center](https://github.com/Explorers-Eden/Nice-Control-Center) |

## What it does

- **Finds lag.** Measures everything (data packs, functions, mods, mobs, block entities, chunks, redstone) in the same unit, *milliseconds per tick*, and tells you what costs the most, why, and what to do about it.
- **Warns you.** Operators get a chat message when the server lags, and every lag episode is saved as a report you can open later.
- **Shows errors.** Warnings and errors from the log, grouped and traced to the data pack or mod that caused them.
- **Lets you run the server from the browser.** Console, in-game chat, player inspector (inventory, ender chest, homes, graves), kick/ban, private messages and scheduled commands.
- **Keeps things up to date.** Updates mods, data packs and the server resource pack automatically from Modrinth or GitHub.
- **Edits settings.** Gamerules, `server.properties` and data pack settings, each explained.

## Installation

1. Download the jar for your Minecraft version from the [latest release](https://github.com/Explorers-Eden/Nice-Control-Center/releases/latest). There is one jar per version: `…-mc26.3.jar`, `…-mc26.2.jar` and `…-mc26.1.jar` (the last one covers 26.1, 26.1.1 and 26.1.2).
2. Put it into the server's `mods/` folder together with [Fabric API](https://modrinth.com/mod/fabric-api).
3. Start the server. The mod creates `config/nicecontrolcenter.json` and starts monitoring right away.

**Singleplayer:** put the jar into your client's `mods/` folder. The dashboard runs while a world is open.

## Opening the dashboard

1. Run `/ncc web` in game or in the server console.
2. Click or copy the printed link and open it in any browser.

The link contains a private token. **Anyone with the link can use the dashboard, so don't share it.**

- The token is **renewed at every server start** by default, so old links stop working after a restart. Set `token_hours` to keep links working longer.
- `/ncc web regen` makes a new link right away (old links stop working).

**Where it listens:**

| | Address | Who can open it |
| --- | --- | --- |
| Singleplayer | `127.0.0.1:8765` | Only your own computer |
| Dedicated server | `0.0.0.0:8765` | Anyone who can reach the port (and has the link) |

On a hosting panel, set `port` to one of your allocated ports and `public_url` to your server's address, so the printed link is correct.

No browser at hand? `/ncc` shows the server's health and the top causes of lag right in chat.

## Commands

The main command is `/nicecontrolcenter`, short `/ncc`. `[minutes]` is optional and sets the time window (default: the last few minutes).

| Command | What it does |
| --- | --- |
| `/ncc` or `/ncc status [minutes]` | Health summary and top causes of lag. Hover a line for the reason and a tip. |
| `/ncc top <list> [minutes]` | Top list. `<list>` is `datapacks`, `functions`, `mods`, `entities`, `blockentities`, `chunks` or `phases`. |
| `/ncc bossbar` | Shows or hides a live TPS/MSPT boss bar, only for you (remembered across restarts) |
| `/ncc bloat` | Checks scoreboards, storage, entity tags and big files in the world folder |
| `/ncc monitor on` / `off` | Switches measuring on or off |
| `/ncc web` | Prints the dashboard link |
| `/ncc web regen` | Makes a new link; old links stop working |
| `/ncc web port <port>` | Moves the dashboard to another port |
| `/ncc record start [minutes]` | Starts a recording (up to 24 hours) |
| `/ncc record stop` / `status` | Stops a recording or shows its status |
| `/ncc updates` | Shows what's new, waiting or held back |
| `/ncc updates check` | Checks for updates now |
| `/ncc updates skip <entry>` | Skips the offered version of a mod or pack |
| `/ncc updates rollback [backup]` | Puts replaced versions back at the next restart (default: latest backup) |
| `/ncc updates webhook [regen]` | Prints a token and `curl` command so a build script can trigger a resource pack check |
| `/nccreply <message>` | **For all players:** answer a private dashboard message |

## Permissions

By default, everything is for **operators (level 2+)**. `/nccreply` works for everyone.

With LuckPerms or another Fabric permissions mod you can give parts to other players:

| Node | Allows |
| --- | --- |
| `nicecontrolcenter.command.status` | `/ncc`, `/ncc status`, `/ncc top …`, `/ncc bossbar`, `/ncc bloat` |
| `nicecontrolcenter.command.web` | `/ncc web …` (link, new link, port) |
| `nicecontrolcenter.command.monitor` | `/ncc monitor on\|off` |
| `nicecontrolcenter.command.record` | `/ncc record …` |
| `nicecontrolcenter.command.updates` | `/ncc updates …` |
| `nicecontrolcenter.notify` | Receive lag alerts and "report ready" messages |
| `nicecontrolcenter.command.*` / `nicecontrolcenter.*` | Everything |

Note: the dashboard itself has no per-player permissions. Anyone with a valid link can use all of it, within the limits you set in the config (`web_console_commands`, `web_player_actions`, …).

## Configuration

Settings are in `config/nicecontrolcenter.json`. Changes are picked up within a few seconds while the server runs. Only `port`, `bind` and `sampler_interval_ms` need a restart. In singleplayer you can also use Mod Menu (Mods → Nice Control Center → Configure).

### Default config

```json
{
  "monitor_enabled": true,
  "web_enabled": true,
  "bind": "auto",
  "port": 8765,
  "public_url": "",
  "token": "",
  "token_hours": 0,
  "token_created": 0,
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
  "web_schedule": true,
  "message_prefix": "[Admin]",
  "message_color": "gold",
  "web_player_actions": true,
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

`token` and `token_created` are filled in by the mod; leave them alone.

### What each setting does

**Dashboard access**

| Key | Default | What it does |
| --- | --- | --- |
| `web_enabled` | `true` | Serve the dashboard at all. |
| `bind` | `"auto"` | Address to listen on. `auto` = `127.0.0.1` in singleplayer, `0.0.0.0` on servers. |
| `port` | `8765` | Dashboard port. If taken, the next 9 ports are tried. |
| `public_url` | `""` | Address used in printed links, e.g. `http://play.example.com:8765`. Empty = guessed. |
| `token_hours` | `0` | How many hours a link stays valid, also across restarts. `0` = new link at every start. |

**What the dashboard may do**

| Key | Default | When `false` |
| --- | --- | --- |
| `web_console` | `true` | Console and Chat tabs are hidden |
| `web_console_commands` | `true` | No commands from the console, no sending from the Chat tab |
| `web_settings_edit` | `true` | Data pack settings are read-only |
| `web_properties_edit` | `true` | server.properties is read-only |
| `web_gamerules_edit` | `true` | Gamerules are read-only |
| `web_player_actions` | `true` | No messaging, kicking or banning |
| `web_schedule` | `true` | Scheduled commands can't be created or changed |

**Players tab**

| Key | Default | What it does |
| --- | --- | --- |
| `message_prefix` | `"[Admin]"` | Prefix of private messages sent from the dashboard. |
| `message_color` | `"gold"` | Prefix color: a Minecraft color (`gold`, `aqua`, …), `purple`, `pink`, `orange` or `#RRGGBB`. |
| `players_storage` | `"eden:database"` | Command storage with the Explorer's Eden player database (homes, graves, waypoints). Empty = none. |

**Monitor**

| Key | Default | What it does |
| --- | --- | --- |
| `monitor_enabled` | `true` | Measure from server start. Also `/ncc monitor on\|off`. |
| `sampler_interval_ms` | `20` | How often the mod sampler runs. Higher = less overhead, less detail. |
| `spike_threshold_ms` | `100` | Ticks slower than this count as lag spikes. |
| `max_recording_hours` | `24` | Recordings stop on their own after this long. |
| `error_ignore` | `[]` | Regular expressions for log messages to ignore in Errors and warnings. |

**Lag alerts and reports**

| Key | Default | What it does |
| --- | --- | --- |
| `alerts_enabled` | `true` | Send lag alerts in chat. |
| `alert_mspt` / `alert_tps` | `45` / `18` | "Lag" = MSPT above `alert_mspt` **or** TPS below `alert_tps`… |
| `alert_seconds` | `15` | …for this many seconds in a row. |
| `alert_cooldown_minutes` | `10` | Minimum time between two alerts. |
| `auto_record_lag` | `true` | Save a report of every lag episode (with the 5 minutes before it). |
| `auto_record_max_minutes` | `30` | Longest automatic report. |
| `auto_record_per_day` | `10` | Most automatic reports per day. |

**Updates**

| Key | Default | What it does |
| --- | --- | --- |
| `update_mode` | `"auto"` | `auto` = install at next restart, `stage` = install after you approve it in the dashboard, `check` = only show, `off` = never check. |
| `update_check_hours` | `24` | Hours between checks (plus one a minute after start). |
| `update_channels` | `["release"]` | Modrinth version types to accept: `release`, `beta`, `alpha`. |
| `update_github` | `{}` | Use GitHub releases for some entries: `{"<mod id or pack name>": "owner/repo"}`. |
| `update_ignore` | `[]` | Mod ids or pack names (without `.zip`) never to update. |
| `update_keep_backups` | `10` | How many backups of replaced files to keep. |
| `resource_pack_source` | `""` | Where the server resource pack comes from: `https://…/pack.zip`, `github:owner/repo[@tag][#file-part]` or `modrinth:project`. Empty = just watch the link in server.properties. |
| `resource_pack_check_minutes` | `10` | Minutes between resource pack checks. `0` = never. |
| `resource_pack_webhook_token` | `""` | Token for build-script pings. Set by `/ncc updates webhook`. Empty = off. |

## Dashboard tabs

The top bar always shows the in-game date and time, the connection status, a light/dark switch and live stat cards (TPS, MSPT, CPU, memory, GC, entities, block entities, chunks, players). Hover a card for details. Below it you pick the time window (1, 5, 15 or 60 minutes) for charts and lists.

| Tab | What's in it |
| --- | --- |
| **Overview** | Charts and the main causes of lag, each with the reason and a tip |
| **Performance** | Where the tick goes: data packs, functions, mods, entities, block entities, lag spikes |
| **World** | World generation, busiest chunks, redstone and block updates, what's around players |
| **Players** | Player inspector, messages, kick and ban |
| **Server** | Errors and warnings, server.properties, gamerules, settings advice, bloat check |
| **Data packs** | Settings of installed data packs |
| **Updates** | Mod, data pack and resource pack updates |
| **Console** | Live server log and a command line |
| **Chat** | A copy of the in-game chat |
| **Schedule** | Scheduled commands |
| **Reports** | Recordings, automatic lag reports and comparisons |

Click a section title to fold it. Link options: `?window=5` and `?theme=light`.

## Feature details

### Performance monitor

Keeps the last hour. Every cost is in **milliseconds per tick**, so a data pack, a mod and a mob type can be compared directly.

- **Data packs:** time per pack, function and command line, including how often a line runs. `execute as @e[...]` hitting 150 entities shows as "about 150 executions per run".
- **Mods:** a sampler checks the server thread every 20 ms and credits the mod whose code was running (mixins count for the mod that added them).
- **Entities and block entities:** time per type, count, and time per single entity.
- **World generation:** new chunks per minute, time per step, structure and feature. Runs on worker threads, so it doesn't slow the tick, but players wait for terrain.
- **Busiest chunks** and **redstone/block updates**, each with a teleport command.
- **Lag spikes:** each slow tick with what made it slow.
- **Settings advice:** view/simulation distance, gamerules, Java memory and garbage collector.

### Errors and warnings

Log warnings and errors grouped by message and traced to the pack or mod behind them. A "since the last /reload" filter shows what a reload broke, and operators get a chat message when a `/reload` causes new errors. Known harmless messages are marked; hide more with `error_ignore`.

### Console and chat

- **Console:** live log with level filter and search, plus a command line with console rights. Dashboard commands are logged as "Dashboard ran: …".
- **Chat:** what players see in chat, with colors: player chat, `/say`, `/me`, `/msg`, `tellraw`, joins, deaths, advancements and Discord messages (JustSync or the panel's Discord bridge). A box at the bottom sends a `/say`. Kept in memory since server start.

### Player inspector

Every player who has joined. Online: position, health, food, XP, game mode, ping, inventory, armor, ender chest (live). Offline: last saved state. With the Explorer's Eden player database it also shows homes, `/back`, last death, last grave, waypoints, race and class; with Get Off My Lawn, claims. Every location has a copyable `/tp`.

- **Messages:** send private messages. Players answer with the [Answer] button or `/nccreply`.
- **Moderation:** kick, ban, unban (also offline players).
- **Client and x-ray hints:** shows the client (Fabric, Vanilla, Lunar, …) and recognised mods. An "x-ray hint" marks players who mined unusually little stone per diamond or ancient debris. It's a hint, not proof.

### Scheduled commands

Tasks that run once, daily, on chosen weekdays, every few minutes/hours, or **after another task** (chains; loops are refused). Use `wait 30s` / `wait 5m` / `wait 1h` lines for things like restart countdowns. Tasks can be paused or run right away. Runs missed while the server is off are skipped.

### Editors

- **Gamerules:** all gamerules with in-game names and descriptions. Changes apply at once.
- **server.properties:** all settings, explained. Secrets are never shown, a backup is kept, most changes apply at the next restart.
- **Data pack settings:** for packs with an Explorer's Eden style settings menu. Only what the pack's menu allows can be changed.

### Updates

Mods in `mods/` and data pack zips in the world's `datapacks/` folder are found on Modrinth by file hash (no setup needed), or on GitHub via `update_github`. New versions are downloaded, checked and installed **when the server stops**, so they're active at the next start. Replaced files are kept for rollback. Updates that need a mod you don't have are held back. **No world backups are made.** In singleplayer, updates are only checked.

**Resource pack:** with `resource_pack_source` set, the mod finds the newest zip and writes its link and SHA-1 into server.properties at the next restart. Without it, the existing link is watched and its SHA-1 kept correct.

### Alerts and reports

- **Lag alerts:** chat message to operators when lag starts (naming the main cause) and when it ends.
- **Automatic lag reports:** each lag episode is saved, including the 5 minutes before it, so "it lagged at 3 am" can be checked the next day.
- **Recordings:** `/ncc record start` saves a single HTML report that opens offline in any browser.
- **Compare:** two reports side by side, or a report against the last 15 minutes, e.g. before and after a data pack update.

## With the Nice Control Center Panel

The [Nice Control Center Panel](../panel/SETUP.md) runs a Fabric server in Docker and adds start/stop, backups, files, versions, a world trimmer, a web map and a Discord bridge. When the panel runs the server:

- The panel installs and updates the mod.
- The dashboard is shown inside the panel with the panel's own accounts, so `port`, `bind`, `public_url` and the token don't matter. `/ncc web` links open the panel.
- The mod handles Discord account linking at login, sends chat and events to the Discord bridge, and shows Discord messages in the Chat tab.
- The panel's map and terrain pregeneration use the mod.

## Files

| Path | Contents |
| --- | --- |
| `config/nicecontrolcenter.json` | Settings |
| `config/nicecontrolcenter-bossbar.json` | Players who turned on the boss bar |
| `nicecontrolcenter/reports/` | Recordings and lag reports (HTML) |
| `nicecontrolcenter/schedule.json` | Scheduled commands |
| `nicecontrolcenter/backups/` | server.properties backups |
| `nicecontrolcenter/updates/backup/` | Mod and data pack versions replaced by updates |

## Performance impact

Small. Timing a step costs about 20 ns. Even a data pack running 30,000 command steps per tick adds only about 0.7 ms per tick; most servers see far less. The sampler costs a fraction of a percent. `/ncc monitor off` turns measuring off completely.

Mod numbers come from sampling, so they're estimates. Mixins on methods that run thousands of times per tick can look slightly more expensive than they are.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| The link doesn't open | The port isn't reachable. Open it in your firewall/hosting panel or set `port` to an allocated port. The server log shows which port is really used. |
| "Open the dashboard with the link from /ncc web" | Your link is outdated (new token after a restart). Run `/ncc web` again, or set `token_hours`. |
| The printed link has the wrong address | Set `public_url`. |
| Nothing is measured | Monitor is off: `/ncc monitor on`. An empty server also pauses itself after `pause-when-empty-seconds`. |
| Buttons or editors are missing | They're switched off in the config (`web_console_commands`, `web_settings_edit`, …). |

## Development

```
cd mod
./gradlew build
```

Requires JDK 25. The jar ends up in `mod/build/libs/`. Builds for Minecraft 26.3 by default; use `./gradlew build -Pmc_target=26.2` (or `26.1`) for older versions. Versions per target are in `versions/<target>.properties`, version-specific classes in `src/mc/<target>/java`.

GitHub Actions (`.github/workflows/ci.yml`) builds on every push and pull request touching `mod/`. On `main` it also publishes a release: version info comes from `tools/release_infos.yml`, release notes from `changelog.log`. To release, raise `Version number` and `Version subtitle` in `tools/release_infos.yml` and add your changes to `changelog.log`.
