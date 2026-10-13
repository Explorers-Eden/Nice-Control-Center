# Go-live checklist: replacing the test server with the live server

The panel currently runs a test server. This moves the live server (now in Crafty) into the panel.

Keep the Crafty server untouched (only stopped) until everything runs, so you can always go back.

## 1. Before the day

- [ ] **Updates:** pushed and built: panel image (1.10.0 or newer), Nice Control Center mod (1.11.0 or newer), Merl (1.6.2 or newer). In Portainer, update the stack with **Re-pull image and redeploy**.
- [ ] **Versions:** note the live server's Minecraft and Fabric version, its memory and Java flags, its port and its mod list. In the panel, put the same memory and flags under **Settings → Startup & Java** (Java 25).
- [ ] **Discord links:** players linked through DiscordJustSync have to link again through the panel's bridge, because the old links aren't carried over. Tell them, or switch off "require a link" for the first days.
- [ ] **Announce** the downtime (Discord, and in game).

## 2. Keep what you set up on the test server

The panel's own settings are in `/data/panel` and stay: accounts, Discord, map, info page, schedules, backup settings, branding.

The **server-side settings** are in the server's `config` folder and would be overwritten by the live server's folder. Save these files from the test server first (Files or SFTP, `config/`):

- `nicecontrolcenter-borders.json`: permanent area and world edge
- `nicecontrolcenter-performance.json`: Tuning
- `nicecontrolcenter-maintenance.json`
- `nicecontrolcenter-claims.json`: claim settings
- `nicecontrolcenter-tweaks.json`: gameplay tweaks (Tweaks)
- `nicecontrolcenter-market.json`: market settings (Market → Settings)
- `nicecontrolcenter-pets.json`: pets and the pet shop (Market → Pets)
- `nicecontrolcenter.json`: the mod's own config

## 3. Clean out the test server

1. [ ] Stop the test server in the panel.
2. [ ] Optional: make a last backup of it, then delete the old test backups under **Backups**.
3. [ ] Empty the server folder: **Files** in the panel, SFTP, or on the host in the volume `nicecontrolcenter-server`.
4. [ ] Test data in the panel to clear:
   - **Map:** empty the volume `nicecontrolcenter-map`, so the test world's tiles go. The live world renders from scratch, which takes a while for a big world.
   - **Stats history:** `/data/panel/stats` (optional).
   - **Claims in the database:** run the SQL below in the panel's database. The test world's claims would otherwise stay until the first copy from the live world.

     ```sql
     TRUNCATE claims, claim_members, claim_players, claim_sync;
     ```

## 4. Move the live server in

1. [ ] Stop the live server in Crafty and make a full backup there.
2. [ ] Copy the **whole** live server folder into the panel's server folder: world (with its `datapacks`), `mods`, `config`, `server.properties`, `ops.json`, `whitelist.json`, `banned-*.json`, `usercache.json`.
   - **Fastest:** on the host, straight into the Docker volume `nicecontrolcenter-server` (Portainer → Volumes shows its path).
   - Then `chown -R 1000:1000` that folder: the panel runs as uid 1000.
   - SFTP to port 2022 works too, but is slow for a big world.
3. [ ] Copy the `nicecontrolcenter-*.json` files from step 2 into the live `config` folder.
4. [ ] **Server jar:** under **Settings → Startup & Java**, set the server jar to the live folder's Fabric launcher jar. Or use **Updates → Minecraft** to install Fabric for that version.
5. [ ] **Mods:** check that the Nice Control Center mod matches the Minecraft version (the panel can keep it up to date for you). **Leave GOML in for now**, for the claim import in step 6. **Remove Discord: JustSync**: the panel's bridge replaces it, and both would post twice. **Remove FallingTree** if you use tree felling (**Settings → Gameplay → Items & tools**): both would fell trees.
6. [ ] **server.properties:**
   - `server-port=25565`: inside the container it's always 25565; the outside port is `MINECRAFT_PORT` in the stack.
   - `pause-when-empty-seconds`: as you like (the panel handles pausing).
   - RCON and the management server aren't needed.

## 5. Network

- [ ] Players connect on `MINECRAFT_PORT` from the stack. Crafty publishes 25500–25600 on the same host, so stop Crafty's server (or the whole Crafty stack) before the panel takes the same port.
- [ ] If the port players use changes, update the DNS record (and the SRV record, if you have one).
- [ ] Traefik: the panel's domain, plus `MAP_HOST` and `INFO_HOST` if you use them, point at the panel. The website's SFTP access uses port 2022 (`SFTP_PORT`), which must be open.

## 6. First start

1. [ ] **Start** the server from the panel and watch the **Console**. The panel shows "running" when it's done. On the first start, the mod updates and data pack loading can take a minute.
2. [ ] **Home dashboard:** TPS, players and the mod connection show up. If the mod isn't connected, the pages say so.
3. [ ] **World → Borders & reset:** the permanent area is right for the live world; pregenerate if needed. Check the **Schedule** (restarts, backups, outer wilds reset) against the live times.
4. [ ] **Claims:**
   1. Claims → GOML import → **Check**, then **Import** (the server must be running with GOML).
   2. Claims → Settings → switch claims **on**.
   3. Look at a few imported claims on the map.
   4. Remove GOML at the next restart.
5. [ ] **Gameplay and market:** switch on what you want under Settings → Gameplay (Items & tools, World & nature, Chat & tab list) and in Market (also Pets there). The market starts empty on the live world; pets are set up in the panel, so they carry over.
6. [ ] **Discord:** the bridge posts "The server is online"; check that chat and joins go both ways.
7. [ ] **Join yourself:** try `/claim`, `/market`, `/pets`, `/merl`, felling a tree, a death message, and that maintenance mode is **off**.
8. [ ] Make a **backup** in the panel and check that it finishes.

## 7. Afterwards

- [ ] **Website:** follow `PANEL-MIGRATION.md` in the website repo (new SFTP and database secrets; profiles read links and claims from the panel).
- [ ] **A Realm Recrafted:** push the README update and the removal of the config conversion; delete that repo's SFTP secrets.
- [ ] **Public pages:** switch on the public info page and the map under Public pages → Info page / World → Map, if they aren't already.
- [ ] **Crafty:** once all of this has run fine for a few days, retire the old Crafty server (keep its last backup).

## If something goes wrong

1. Stop the server in the panel.
2. Start the live server in Crafty again: its folder was only stopped, never changed.
3. Players get the old address back (port or DNS). Then look at the panel's console and logs in peace.
