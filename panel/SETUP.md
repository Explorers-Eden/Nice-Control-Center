# Nice Control Center Panel: setup

> The panel runs the Minecraft server (start/stop, console, crash restart, Java settings), shows the Nice Control Center dashboard, makes backups, runs scheduled tasks, has a file explorer, config editor and SFTP, updates Fabric and Minecraft, bridges to Discord, draws a public web map, and has user accounts with roles and an audit log.

## Deploy with Portainer

1. Portainer → **Stacks** → **Add stack** → **Web editor**.
2. Paste [portainer-stack.yml](portainer-stack.yml).
3. Under **Environment variables**, add `PANEL_ADMIN_PASSWORD`. Optionally add `PANEL_ADMIN_USER`, which defaults to `admin`. For user accounts, also add `DB_URL`, `DB_USER` and `DB_PASS` (see [Accounts](#accounts)).
4. Deploy and open `http://<host>:8080`.

If you leave `PANEL_ADMIN_PASSWORD` out, the panel generates a password on first start and prints it **once** in the container log (Portainer → Containers → nicecontrolcenter → Logs). To get a new one, delete `admin.hash` in the panel volume and restart.

The image `niceron/nicecontrolcenter:latest` is rebuilt on every change to `main`. Watchtower picks it up like any other container. When the container stops, the panel stops Minecraft cleanly first. `stop_grace_period: 120s` in the stack gives it time to save.

## Getting a server into the panel

The panel runs the Fabric server in the server folder. To bring in an existing one:

1. Get the Fabric server launcher from [fabricmc.net/use/server](https://fabricmc.net/use/server). It's a jar named like `fabric-server-mc.26.3-loader.0.19.5-launcher.1.1.0.jar`.
2. Put it in the server volume, together with your `mods/`, `config/`, world and `server.properties` if you're moving an existing server. The easiest way is to bind-mount a host folder instead of the named volume:
   ```yaml
   volumes:
     - /srv/minecraft:/data/server
   ```
   The panel runs as uid 1000, so give it the folder first: `chown -R 1000:1000 /srv/minecraft`.
3. In the panel under **Startup & Java**, set **Server jar** to that file name. The panel also tells you when it finds a different `fabric-server…jar`.
4. Accept the EULA when the panel asks, then press **Start**.

## Dashboard

The **Dashboard** tab shows the Nice Control Center mod's full dashboard: performance, world, players, server settings, data packs, updates, scheduled commands and reports. It only works while the server runs.

- The panel installs the mod for your server's Minecraft version and updates it before each start. Switch this off under **Startup & Java** if you'd rather manage it yourself; you need mod version 1.1.0 or newer.
- The mod only answers the panel: it listens inside the container and doesn't need its own port or login link.
- Everything in the dashboard is covered by the panel's permissions (see [Accounts](#accounts)), and every change goes to the audit log.
- Set `PANEL_PUBLIC_URL` (e.g. `https://panel.example.com`) so that `/ncc web` in the game links straight to the Dashboard tab.

## Backups

**Backups** makes zip files of the server folder in the backup volume. You can open them anywhere.

- **While the server runs**, saving pauses for the copy (`save-off`, `save-all flush`, then `save-on`), so the world on disk is consistent. Players don't notice.
- **Leave out** folders and files with one pattern per line, relative to the server folder:
  - `logs/**` leaves out a folder.
  - `**/*.log` leaves out a file type anywhere.
  - `world/DIM-1/**` leaves out the Nether.
  - By default, logs, crash reports and the files Fabric downloads again by itself (`.fabric`, `libraries`, `versions`) are left out.
- **Keeping:** the newest N backups, plus one per day for D days and one per week for W weeks. Pinned backups are never removed automatically.
- **Restore** works while the server is stopped. Choose what to bring back (e.g. only `world/` or only `config/`). Each chosen folder is replaced as a whole, and a safety backup of the current state is made first.

## Scheduled tasks

**Schedule** runs tasks at set times on chosen weekdays, or every few minutes. The times use `TIMEZONE` (e.g. `Europe/Berlin`).

A task is a list of steps:
- **Restart** after a countdown in chat. The message is configurable, and `{time}` becomes "5 minutes", "30 seconds" and so on.
- **Stop**, **Start**, **Backup**
- **Console command**, **Chat message**, **Wait**

The templates set up a daily restart at 04:00 (backup first, then a 5-minute countdown) and a backup every 6 hours. **Run now** starts any task immediately.

## Files and configs

- **Files:** the server folder in the browser.
  - Upload (drag files onto the list), download (folders come as a zip), rename or move, copy, zip and unzip, new folders, delete, and edit text files.
  - Deleted files go to `.panel-trash` for 7 days.
  - While the server runs, the world folder is read-only, so a running world can't be damaged.
- **Configs:** every file in `config/`, grouped by mod.
  - JSON, JSON5, TOML, YAML and properties are checked before saving; on an error the editor jumps to the line. **Save anyway** stays possible.
  - Simple files can be switched to **Form**: switches, numbers and text, with the comments as hints, while the file's layout and comments are kept.
  - The previous version stays as `<file>.bak`.
  - Most mods read their config at start, so restart afterwards.
- **Log cleanup** (Files tab): deletes files older than N days in `logs/`, `crash-reports/` and `debug/`, with a preview. It runs daily after 05:00; `latest.log` is never touched.

## Versions

**Versions** shows the installed Minecraft and Fabric Loader versions.

- **Fabric Loader:** when a newer one is out for your Minecraft version, **Update Fabric Loader** prepares the update.
- **Another Minecraft version:** choose it, then press **Check the mods**. The panel looks up every mod on Modrinth for that version and shows one of:
  - works as it is
  - update available
  - no version yet
  - not on Modrinth: the panel can't check it, so it stays as it is
  - needs a newer Fabric Loader
- **Your choice per mod:** update, keep, or turn off. Turned-off mods get a `.disabled` ending; rename them back in Files to turn them on again.
- **The update itself:**
  1. Backup.
  2. Download and verify the new launcher and mod files.
  3. Stop the server.
  4. Swap the files.
  5. Start the server. If it doesn't come up within 15 minutes, the backup is restored automatically and the old version starts again.
- **Undo** reverses the last successful update by restoring its backup, world included.

Minecraft itself can't take a world back to an older version, so go back with Undo or a backup, never by picking an older version.

## Discord

The **Discord** tab connects a bot. It does four things:

1. **Linking required to play:**
   - Someone without a linked Discord account sees a code on the join screen. They send it to the bot as a direct message and can join right after.
   - Optionally they must also still be on your Discord server.
   - Operators and a list of names can skip it.
   - Players send `unlink` to the bot to remove their link; admins can unlink in the tab.
2. **Chat both ways:**
   - Game chat appears in the channel with the player's name and head.
   - Discord messages appear in the game as `[Discord] Name: text`.
   - Only chat is relayed, never commands, and mentions can't ping anyone.
3. **Event messages:** joins, leaves, deaths, advancements, server online/offline and crashes. Each can be switched off and has its own text.
4. **Bot status:** e.g. "3/20 players online" or "Server offline".

**Setting up the bot** (the tab has the same steps):
1. On [discord.com/developers](https://discord.com/developers/applications): **New Application** → **Bot** → **Reset Token** and copy the token. Switch on **Message Content Intent**.
2. **OAuth2** → **URL Generator**: scope `bot`, permissions *View Channels*, *Send Messages*, *Read Message History*, *Manage Webhooks*. Open the link and add the bot to your server.
3. In Discord, turn on **Developer Mode**, then copy the server ID and the chat channel's ID.
4. Paste the token and both IDs in the panel, switch the bridge on and save. The token can also come from `DISCORD_TOKEN`.

Links are kept in the panel volume, so the login check needs neither the database nor Discord. If the bot isn't connected, players are let in, so a Discord outage can't lock everyone out.

## Web map

The panel draws a top-down map of the world from the region files, one pixel per block like a vanilla map, with relief and water depth.

- **No load on the server:** the panel does the work, and the map stays online while the server is off. New areas appear after the server saves them (every 5 minutes, or at shutdown).
- **Layers:** online players with their heads, GOML claims, waypoint hubs (public ones by default) and the world border, if one is set.
- **Colors:** modded blocks get their real map color once the server has run with the Nice Control Center mod.
- **Own address:** set `MAP_HOST` (e.g. `map.example.com`) and point that subdomain at port 8080 in your reverse proxy. On that address only the map is reachable, nothing of the panel. Without `MAP_HOST` the map is at `/map/` on the panel's address.
- **Map tab:** choose the layers, hide dimensions, change the title, or have everything drawn again.

## World: pregenerate and trim

The **World** tab works on one area: dimension, center, radius, square or circle. It can be filled in from the world border or centered on spawn.

- **Pregenerate** creates every chunk in the area ahead of time, so players don't wait for terrain and the map is complete.
  - It runs while the server is up, in a spiral from the center.
  - It only works as fast as the server has room for, slowing down when ticks get long.
  - It keeps going with nobody online.
- **Trim** deletes everything generated outside the area: whole region files, single chunks along the edge, entities and points of interest. Those areas generate fresh when someone goes there.
  - **Preview** first: the edge appears in green on a map and what goes in red. **Keep chunks players spent at least N minutes in** protects builds outside the area.
  - Trimming only works while the server is stopped, and a backup is made first.

## SFTP

Connect with FileZilla, WinSCP, Cyberduck or `sftp -P 2022 name@your-server` and log in with your panel name and password.
- **SSH keys:** add a public key under **Account** to log in without a password.
- **Permissions:** the account needs the SFTP permission. Without "Upload, edit…" it's read-only.
- **Scope:** SFTP shows the server folder and nothing outside it. The world folder is read-only while the server runs.
- **Audit:** uploads, renames and deletes go to the audit log.
- **Port:** forward 2022 (or set `SFTP_PORT`) in the stack; this is plain TCP, not through the reverse proxy.

## Accounts

The **container admin** (`PANEL_ADMIN_USER` / `PANEL_ADMIN_PASSWORD`) can always log in and do everything, even while the database is down. Use it to set things up and as a spare key.

For everyone else, point the panel at your Postgres:

| Variable | Example |
|---|---|
| `DB_URL` | `jdbc:postgresql://postgres:5432/nicecontrolcenter` (a `postgres://…` URL works too) |
| `DB_USER` | `nicecontrolcenter` |
| `DB_PASS` | … |

Create an empty database and a user that owns it. The panel creates and updates its tables itself on start. If the database isn't reachable, the panel keeps running, retries every 10 seconds, and only the container admin can log in until it's back.

Then, under **Users**:
- **Users** get one or more roles. Disabling, deleting or changing a user's password logs them out everywhere at once.
- **Roles** decide what a user may do: see the server, start/stop it, read the console, run commands, see the dashboard, manage players, change server or Java settings, manage updates and scheduled tasks, see/make/restore backups, browse or change files, edit mod configs, use SFTP, manage users, read the audit log. **Admin** can do everything. **Moderator** and **Viewer** are built in and adjustable, and you can add your own. The panel only shows each account what it's allowed to use.
- The **Audit log** records every login (failed ones too), server action, console command, settings change and account change, with who, when and from which IP.

## Startup & Java

- **Memory:** `-Xms` / `-Xmx` for the Minecraft server. If the container has a memory limit, keep it about 1–1.5 GB above the maximum: Java needs more than its heap, and the panel itself uses a little.
- **Garbage collector flags:** Aikar's G1 flags (the usual choice) or generational ZGC (short pauses, best with plenty of memory).
- **Extra flags:** anything else, such as `-Dfile.encoding=UTF-8`. Memory flags belong in the memory fields.
- **Java runtime:** the built-in Java 25 runs every Minecraft 26.x version.
- **Start the server when the panel starts** and **Restart after a crash** are on by default.

The preview shows the exact command line. Changes apply on the next start.

## Crashes

An unexpected exit counts as a crash. The panel shows the exit code and the newest crash report, then restarts after 10 s, 30 s, 2 min and then every 10 min. After 5 crashes in 15 minutes it stops trying, so a broken mod doesn't loop forever. **Stop** clears the crashed state.

## Volumes

| Path in the container | What's in it |
|---|---|
| `/data/server` | the Minecraft server folder: world, mods, config, logs |
| `/data/backups` | backups |
| `/data/map` | map tiles for the map subdomain (coming) |
| `/data/panel` | panel settings and the generated admin password hash |

## Environment variables

| Variable | Default | |
|---|---|---|
| `PANEL_ADMIN_USER` | `admin` | login name |
| `PANEL_ADMIN_PASSWORD` | generated | login password |
| `DB_URL` / `DB_USER` / `DB_PASS` | – | Postgres for user accounts and the audit log |
| `PANEL_PUBLIC_URL` | – | the panel's address, for `/ncc web` links in the game |
| `TIMEZONE` | the container's | time zone for scheduled tasks and backup names, e.g. `Europe/Berlin` |
| `DISCORD_TOKEN` | – | the Discord bot token (instead of saving it in the panel) |
| `MAP_HOST` | – | the map's own address, e.g. `map.example.com` |
| `SFTP_PORT` | `2022` | SFTP port inside the container (`off` turns SFTP off) |
| `DASHBOARD_PORT` | `8765` | port the mod's dashboard uses inside the container (only change it if something else needs 8765) |
| `PANEL_PORT` | `8080` | port the panel listens on inside the container |

## Reverse proxy

Point the panel's subdomain (for example `panel.example.com`) at port 8080 and enable WebSocket support (in Nginx Proxy Manager: "Websockets Support"), so the live console works. Behind HTTPS the login cookie is marked `Secure` automatically (via `X-Forwarded-Proto`). The map gets its own subdomain on the same port later.

## Building locally

```
cd panel
./gradlew shadowJar
SERVER_DIR=./run/server PANEL_DATA=./run/panel PANEL_ADMIN_PASSWORD=test java -jar build/libs/nice-control-center-panel-*.jar
```

Requires JDK 25. The panel compiles a few Minecraft-free classes and the dashboard's style straight from `../mod`, so build from a full checkout.

Docker image, from the repository root:

```
docker build -f panel/Dockerfile -t niceron/nicecontrolcenter .
```
