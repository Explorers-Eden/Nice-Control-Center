# Go-live checklist: replacing the test server with the live server

The panel currently runs a test server. This moves the live server (now in Crafty) into the panel.

Keep the Crafty server untouched (only stopped) until everything runs, so you can always go back.

## 1. Before the day

- [ ] **Updates:** pushed and built: panel image (1.12.8 or newer), Nice Control Center mod (1.13.9 or newer), Merl (1.6.2 or newer).
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
- **The pet shop:** names, head textures, prices, sale dates, amounts. The head textures are easiest to copy out of `config/nicecontrolcenter-pets.json`.
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
     market_listings, market_sales, market_sync,
     claim_members, claim_players, claim_sync, claims,
     discord_links, user_ssh_keys, sessions, user_roles, role_permissions, users, roles, audit_log,
     flyway_schema_history
   CASCADE;
   ```

5. [ ] **Deploy the stack again:** Stacks → the stack → Editor. Set `MINECRAFT_PORT` to the port players will use (the live server's), then **Update the stack** with **Re-pull image and redeploy**. That makes a new container and new, empty volumes. Crafty publishes 25500–25600 even while its server is stopped: if the port is in that range, first stop the live server in Crafty, make its backup (section 4, step 1) and stop the whole Crafty stack, or the panel's container can't start. Sign in with `PANEL_ADMIN_USER` / `PANEL_ADMIN_PASSWORD` (without a password set, the panel prints a new one once in its log).
6. [ ] Things that come with a fresh panel:
   - **SFTP host key:** the panel makes a new one, so SFTP clients (and the website's deploy) warn about a changed key. Accept the new one / update the website's known host.
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
- [ ] Traefik: the panel's domain, plus `MAP_HOST` and `INFO_HOST` if you use them, point at the panel. Set `PANEL_PUBLIC_URL` (or `INFO_HOST`) in the stack too: `/info` in the game links there. The website's SFTP access uses port 2022 (`SFTP_PORT`), which must be open.

## 6. First start

1. [ ] **Start** the server from the panel and watch the **Console**. The panel shows "running" when it's done. On the first start, the mod updates and data pack loading can take a minute.
2. [ ] **Home dashboard:** TPS, players and the mod connection show up. If the mod isn't connected, the pages say so.
3. [ ] **Server settings from your notes (step 2):** set them again in the panel; each page writes its file into the server's `config` folder when you save.
   - **World → Borders & reset:** the permanent area and world edge for the live world; pregenerate if needed.
   - **Settings → Tuning** and **Players → Maintenance** (check that maintenance is **off**).
4. [ ] **Claims:**
   1. Claims → GOML import → **Check**, then **Import** (the server must be running with GOML).
   2. Claims → Settings: set them up again and switch claims **on**.
   3. Look at a few imported claims on the map.
   4. Remove GOML at the next restart.
5. [ ] **Gameplay and market:** switch on what you want under Settings → Gameplay (Items & tools, World & nature, Chat & tab list) and set up the Market. Under Market → Pets, switch pets on and add the pets again. The market starts empty, and nobody owns a pet yet.
6. [ ] **Links:** under Public pages → Info page → Links in the game, check the address for `/info` and your link commands (like `/website` and `/discord`), then press **Save** once so they're written into the server's `config` folder. The server log says when a link's name is already taken by another command.
7. [ ] **Discord:** the bridge posts "The server is online"; check that chat and joins go both ways.
8. [ ] **Join yourself:** try `/claim`, `/market`, `/merl`, felling a tree and a death message. For pets: give yourself one with `/pets grant <you> <pet>`, take it out with `/pets on`, and try a few moves and particles in `/pets`. Click the links from `/info` and your link commands.
9. [ ] Make a **backup** in the panel and check that it finishes.

## 7. Afterwards

- [ ] **Website:** follow `PANEL-MIGRATION.md` in the website repo (new SFTP and database secrets; profiles read links and claims from the panel).
- [ ] **A Realm Recrafted:** push the README update (Market, Pets, Land Claims) and the removal of the config conversion; delete that repo's SFTP secrets.
- [ ] **Permissions:** with a permissions mod, `nicecontrolcenter.pets.admin` allows `/pets grant` and `/pets revoke` (operators level 2 without one); `nicecontrolcenter.pets.use`, `nicecontrolcenter.market.use` and `nicecontrolcenter.links.use` are for everyone by default.
- [ ] **Public pages:** switch on the public info page and the map under Public pages → Info page / World → Map, if they aren't already.
- [ ] **Crafty:** once all of this has run fine for a few days, retire the old Crafty server (keep its last backup).

## If something goes wrong

1. Stop the server in the panel.
2. Start the live server in Crafty again: its folder was only stopped, never changed.
3. Players get the old address back (port or DNS). Then look at the panel's console and logs in peace.
