# Go-live checklist: replacing the test server with the live server

The panel currently runs a test server. This moves the live server (now in Crafty) into the panel.

Keep the Crafty server untouched (only stopped) until everything runs, so you can always go back.

## 1. Before the day

- [ ] **Updates:** pushed and built: panel image (1.12.32 or newer), Nice Control Center mod (1.13.25 or newer), Merl (1.6.2 or newer).
- [ ] **Live server:** note its Minecraft and Fabric version, its memory and Java flags, its port and its mod list.
- [ ] **Discord links:** players linked through DiscordJustSync have to link again through the panel's bridge, because the old links aren't carried over. Tell them, or switch off "require a link" for the first days.
- [ ] **Announce** the downtime (Discord, and in game).

## 2. Write down what you set up on the test server

Everything gets wiped and set up fresh: the test server, the panel's own data and its database. Note (or screenshot) what you want to set up the same way again:

- **The stack's variables** in Portainer (`PANEL_PUBLIC_URL`, `MAP_HOST`, `INFO_HOST`, `DB_*`, `PANEL_ADMIN_*`, ports…): they stay with the stack; only `MINECRAFT_PORT` changes, in section 3.
- **Discord:** bot token, channel ids and the bridge options (Discord page).
- **Public pages:** info page texts and switches, links in the game (`/info` texts, `/website`, `/discord`…), the map settings, the server list MOTDs, the logo and banner (keep the image files).
- **Server:** Startup & Java (memory, flags), Schedule (restarts, backups, outer wilds reset), backup settings.
- **Settings and gameplay:** borders and permanent area, Tuning, Maintenance, claim settings, Gameplay (Items & tools, World & nature, Chat & tab list), Market.
- **Pets:** names, descriptions, head textures and sizes (Community → Pets), and their listings: prices, sale dates, amounts (Market → Server shop → Pet listings). The head textures are easiest to copy out of `config/nicecontrolcenter-pets.json`.
- **Users and roles** you made in the panel, and their SSH keys.

Tip: copy the panel's settings files (`*.json` in the volume `nicecontrolcenter-panel`, on the host) somewhere safe and keep them only to look things up. Don't copy them back.

## 3. Wipe everything

1. [ ] Stop the test server in the panel.
2. [ ] In Portainer, **remove the container** (Containers → `nicecontrolcenter` → Remove). The stack stays, with its variables.
3. [ ] **Remove its volumes** (Volumes): `nicecontrolcenter-server`, `nicecontrolcenter-backups`, `nicecontrolcenter-map` and `nicecontrolcenter-panel` (with the stack's name in front, e.g. `nicecontrolcenter_nicecontrolcenter-server`). That takes the test world, its backups, the map tiles and all panel settings. Docker only lets you remove a volume once no container uses it, so the container goes first.
4. [ ] **Wipe the panel's tables** in Postgres (e.g. Portainer → the Postgres container → Console: `psql -U <DB_USER> -d <database>`). Only the panel's own tables go; the panel creates them again empty when it starts:

   ```sql
   DROP VIEW IF EXISTS claim_player_summary;
   DROP TABLE IF EXISTS
     smp_players, smp_command_storage, smp_sync,
     market_listings, market_sales, market_sync,
     claim_members, claim_players, claim_sync, claims,
     discord_links, user_ssh_keys, sessions, user_roles, role_permissions, users, roles, audit_log,
     flyway_schema_history
   CASCADE;
   ```

5. [ ] **Deploy the stack again:** Stacks → the stack → Editor. Set `MINECRAFT_PORT` to the port players will use (the live server's), then **Update the stack** with **Re-pull image and redeploy**. That makes a new container and new, empty volumes. Crafty publishes 25500–25600 even while its server is stopped: if the port is in that range, first stop the live server in Crafty, make its backup (section 4, step 1) and stop the whole Crafty stack, or the panel's container can't start. Sign in with `PANEL_ADMIN_USER` / `PANEL_ADMIN_PASSWORD` (without a password set, the panel prints a new one once in its log).
6. [ ] Things that come with a fresh panel:
   - **SFTP host key:** the panel makes a new one, so SFTP clients warn about a changed key. Accept the new one. (The website doesn't use SFTP any more.)
   - **Discord links:** everyone links again (also those who linked on the test server).
   - The **map** renders the live world from scratch, which takes a while for a big world.

## 4. Move the live server in

1. [ ] Stop the live server in Crafty and make a full backup there.
2. [ ] Copy the **whole** live server folder into the panel's (now empty) server folder: world (with its `datapacks`), `mods`, `config`, `server.properties`, `ops.json`, `whitelist.json`, `banned-*.json`, `usercache.json`.
   - **Fastest:** on the host, straight into the Docker volume `nicecontrolcenter-server` (Portainer → Volumes shows its path).
   - Then `chown -R 1000:1000` that folder: the panel runs as uid 1000.
   - SFTP to port 2022 works too, but is slow for a big world.
3. [ ] If the live `config` folder has `nicecontrolcenter-*.json` files from an older version of the mod, delete them: the settings are set up fresh in step 6. Leave other mods' configs as they are.
4. [ ] **Startup & Java** (Settings → Server): the live server's memory and flags (Java 25), and the server jar: the live folder's Fabric launcher jar. Or use **Updates → Minecraft** to install Fabric for that version.
5. [ ] **Mods:** check that the Nice Control Center mod matches the Minecraft version (the panel can keep it up to date for you). **Leave GOML in for now**, for the claim import in step 6. **Remove Discord: JustSync**: the panel's bridge replaces it, and both would post twice. **Remove FallingTree** if you use tree felling (**Settings → Gameplay → Items & tools**): both would fell trees.
6. [ ] **server.properties:**
   - `server-port=25565`: inside the container it's always 25565; the outside port is `MINECRAFT_PORT` in the stack.
   - `pause-when-empty-seconds`: as you like (the panel handles pausing).
   - RCON and the management server aren't needed.
7. [ ] **Panel setup from your notes** (before the first start is fine): Discord, users and roles, branding, public pages (info page, links in the game, map, MOTDs), Schedule and backup settings.

## 5. Network

- [ ] Players connect on `MINECRAFT_PORT` from the stack (set in section 3). Crafty publishes 25500–25600 on the same host, so stop Crafty's server (or the whole Crafty stack) before the panel takes the same port: if the port is still taken when you redeploy, the container won't start; stop Crafty and redeploy again.
- [ ] If the port players use changes, update the DNS record (and the SRV record, if you have one).
- [ ] Traefik: the panel's domain, plus `MAP_HOST` and `INFO_HOST` if you use them, point at the panel. Set `PANEL_PUBLIC_URL` (or `INFO_HOST`) in the stack too: `/info` in the game links there.
- [ ] **Database for the website:** the website reads everything from the panel's Postgres, so GitHub Actions must reach it (section 7). SFTP (port 2022) only needs to be open if you use it yourself.

## 6. First start

1. [ ] **Start** the server from the panel and watch the **Console**. The panel shows "running" when it's done. On the first start, the mod updates and data pack loading can take a minute.
2. [ ] **Home dashboard:** TPS, players and the mod connection show up. If the mod isn't connected, the pages say so.
3. [ ] **Server settings from your notes (step 2):** set them again in the panel; each page writes its file into the server's `config` folder when you save.
   - **World → Borders & reset:** the permanent area and world edge for the live world; pregenerate if needed.
   - **Settings → Tuning** and **Settings → Server → Maintenance** (check that maintenance is **off**).
4. [ ] **Claims:**
   1. Claims → GOML import → **Check**, then **Import** (the server must be running with GOML).
   2. Claims → Settings: set them up again and switch claims **on**.
   3. Look at a few imported claims on the map.
   4. Remove GOML at the next restart.
5. [ ] **Gameplay and market:** switch on what you want under Settings → Gameplay (Items & tools, World & nature, Chat & tab list) and set up the Market. Under **Community → Pets**, switch pets on and add the pets again, then list the ones for sale under **Market → Server shop → Pet listings**. The market starts empty, and nobody owns a pet yet.
6. [ ] **Links:** under Public pages → Info page → Links in the game, check the address for `/info` and your link commands (like `/website` and `/discord`), then press **Save** once so they're written into the server's `config` folder. The server log says when a link's name is already taken by another command.
7. [ ] **Discord:** the bridge posts "The server is online"; check that chat and joins go both ways.
8. [ ] **Join yourself:** try `/claim`, `/market`, `/merl`, felling a tree and a death message. For pets: give yourself one with `/pets grant <you> <pet>`, take it out with `/pets on`, and try a few moves and particles in `/pets`. Click the links from `/info` and your link commands.
9. [ ] Make a **backup** in the panel and check that it finishes.

## 7. Website (explorerseden.eu)

The website reads **everything** about the SMP from the panel's database: Discord links, land claims, market listings, and the world's player data and command storage, which the panel copies there every minute when it changed. It no longer uses SFTP. The website changes are ready but **not pushed yet** (they need the panel's tables): push them in step 5.

1. [ ] **Panel tables:** after the first start (section 6), check in the panel's database:
   ```sql
   SELECT count(*) FROM discord_links;
   SELECT count(*) FROM smp_players;               -- everyone who has a player file
   SELECT name, saved_at FROM smp_command_storage;  -- the data packs' storage, e.g. eden
   SELECT * FROM smp_sync;                          -- level name and when the last copy ran
   ```
   Players and storage show up within a minute (the storage once the world has saved).
2. [ ] **Read-only database user for the website** (in the panel's database):
   ```sql
   CREATE ROLE website_reader LOGIN PASSWORD 'choose-one';
   GRANT CONNECT ON DATABASE panel TO website_reader;   -- your panel database name
   GRANT USAGE ON SCHEMA public TO website_reader;
   GRANT SELECT ON discord_links, claims, claim_members, claim_players, claim_player_summary,
     market_listings, market_sync, smp_players, smp_command_storage, smp_sync TO website_reader;
   ```
   The Postgres port must be reachable from GitHub Actions (for SSL see step 3).
3. [ ] **GitHub secrets and variables** (explorerseden.eu → Settings → Secrets and variables → Actions). This is the complete list after the change:

   **No longer needed: delete these secrets.** Nothing in the website uses them any more (it reads the database instead of SFTP):

   | Secret | Was used for |
   | --- | --- |
   | `SFTP_HOST` | the server's SFTP host |
   | `SFTP_PORT` | the server's SFTP port |
   | `SFTP_USER` | the SFTP account |
   | `SFTP_PASSWORD` | the SFTP password |
   | `SMP_WORLD_SFTP_REMOTE_PATH` | where the world's player files were downloaded from |
   | `WAYPOINT_SFTP_REMOTE_PATH` | the command storage file with the waypoint hubs, homes and graves |
   | `SMP_CONFIG_SFTP_REMOTE_PATH` | the server config the old Player Inspector read |

   No repository variables become unused.

   **New: add these.**

   | Name | Kind | Value |
   | --- | --- | --- |
   | `PANEL_DATABASE_URL` | secret | `postgres://website_reader:choose-one@<db-host>:5432/<panel database>` (the user from step 2) |
   | `PANEL_PUBLIC_URL` | variable | the address of the panel's public info page without `/info`, e.g. `https://info.explorerseden.eu` (your `INFO_HOST`). Market item icons load from `<that>/info/items/…`; without it the Market tab shows no icons. |
   | `PANEL_WAYPOINT_STORAGE` | variable, optional | only if the waypoint hubs aren't in the `eden` storage: the namespace from the old `WAYPOINT_SFTP_REMOTE_PATH` file (`command_storage_<namespace>.dat`, in 26.3 `data/<namespace>/command_storage.dat`). Look it up before deleting that secret. |
   | `PANEL_DATABASE_SSL` | secret, optional | `true` if the database needs SSL from outside (then also add it to the workflow's `env` next to `PANEL_DATABASE_URL`). |

   **Unchanged: keep these.** `REPO_TOKEN`, `TRANSLATIONS_DATABASE_URL`, `DOCKER_USERNAME`, `DOCKER_PASSWORD` (secrets) and `SYNC_TRANSLATIONS_LIVE` (variable, used by "Sync translations").
4. [ ] **Admin Player Inspector:** it's removed from the website (the panel's Players page covers it). Nothing to do: the next "Publish generated data to Postgres" run deletes its old data from the website database.
5. [ ] **Push the website changes** (in the explorerseden.eu repo: the scripts read the panel's database, the SFTP steps and the inspector are gone). Then run **Generate Files and Deploy** by hand (Actions tab) and check the log:
   - "Fetch waypoint hub data": `Loaded the "eden" command storage from the panel` and the number of public hubs.
   - "Generate SMP player profile data": `Loaded N player(s) from the panel`, `Loaded N Discord link(s), M claim(s) and K market listing(s) from the panel.` and `Wrote N player profile(s)`.
6. [ ] **Open My SMP Profile:** race and class, stats, advancements, waypoints, home and last grave are there; the Claims tab lists each claim with its name, colour, size and members (only claims with an owner); the Market tab shows what the player sells on `/market` with icons, price and days left.

Testing the website scripts locally without the database: `SMP_LOCAL_WORLD_DIR=/path/to/world` (player files), `WAYPOINT_LOCAL_FILE=/path/to/command_storage.dat`, and for links, claims and market `PANEL_LOCAL_LINKS_FILE`, `PANEL_LOCAL_CLAIMS_FILE`, `PANEL_LOCAL_MARKET_FILE`.

## 8. Afterwards

- [ ] **A Realm Recrafted:** push the README update (Market, Pets, Land Claims) and the removal of the config conversion; delete that repo's SFTP secrets.
- [ ] **Permissions:** with a permissions mod, `nicecontrolcenter.pets.admin` allows `/pets grant` and `/pets revoke` (operators level 2 without one); `nicecontrolcenter.pets.use`, `nicecontrolcenter.market.use` and `nicecontrolcenter.links.use` are for everyone by default.
- [ ] **Public pages:** switch on the public info page and the map under Public pages → Info page / World → Map, if they aren't already.
- [ ] **Crafty:** once all of this has run fine for a few days, retire the old Crafty server (keep its last backup).

## If something goes wrong

1. Stop the server in the panel.
2. Start the live server in Crafty again: its folder was only stopped, never changed.
3. Players get the old address back (port or DNS). Then look at the panel's console and logs in peace.
