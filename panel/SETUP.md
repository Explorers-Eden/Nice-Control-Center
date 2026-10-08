# Nice Control Center Panel: setup

> The panel is in development. It runs the Minecraft server (start/stop, console, crash restart, Java settings). Backups, schedules, users, files, updates, Discord and the map follow step by step.

## Deploy with Portainer

1. Portainer → **Stacks** → **Add stack** → **Web editor**.
2. Paste [portainer-stack.yml](portainer-stack.yml).
3. Under **Environment variables**, add `PANEL_ADMIN_PASSWORD`. Optionally add `PANEL_ADMIN_USER`, which defaults to `admin`.
4. Deploy and open `http://<host>:8080`.

If you leave `PANEL_ADMIN_PASSWORD` out, the panel generates a password on first start and prints it **once** in the container log (Portainer → Containers → nicecontrolcenter → Logs). To get a new one, delete `admin.hash` in the panel volume and restart.

The image `niceron/nicecontrolcenter:latest` is rebuilt on every change to `main`. Watchtower picks it up like any other container. When the container stops, the panel stops Minecraft cleanly first. `stop_grace_period: 120s` in the stack gives it time to save.

## Getting a server into the panel

Until the version manager arrives, the panel runs whatever Fabric server is in the server folder:

1. Get the Fabric server launcher from [fabricmc.net/use/server](https://fabricmc.net/use/server). It's a jar named like `fabric-server-mc.26.3-loader.0.19.5-launcher.1.1.0.jar`.
2. Put it in the server volume, together with your `mods/`, `config/`, world and `server.properties` if you're moving an existing server. The easiest way is to bind-mount a host folder instead of the named volume:
   ```yaml
   volumes:
     - /srv/minecraft:/data/server
   ```
   The panel runs as uid 1000, so give it the folder first: `chown -R 1000:1000 /srv/minecraft`.
3. In the panel under **Startup & Java**, set **Server jar** to that file name. The panel also tells you when it finds a different `fabric-server…jar`.
4. Accept the EULA when the panel asks, then press **Start**.

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
| `/data/backups` | backups (coming) |
| `/data/map` | map tiles for the map subdomain (coming) |
| `/data/panel` | panel settings and the generated admin password hash |

## Environment variables

| Variable | Default | |
|---|---|---|
| `PANEL_ADMIN_USER` | `admin` | login name |
| `PANEL_ADMIN_PASSWORD` | generated | login password |
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
