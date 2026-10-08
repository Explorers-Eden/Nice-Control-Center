# Nice Control Center Panel: setup

> The panel is in development. This first version only starts and answers its health check; server management follows step by step.

## Deploy with Portainer

1. Portainer → **Stacks** → **Add stack** → **Web editor**.
2. Paste [portainer-stack.yml](portainer-stack.yml) and deploy.
3. Open `http://<host>:8080`. The page should say "Panel 0.1.0 is running".

The image `niceron/nicecontrolcenter:latest` is rebuilt on every change to `main`. Watchtower picks it up like any other container.

### Volumes

| Path in the container | What's in it |
|---|---|
| `/data/server` | the Minecraft server folder: world, mods, config, logs |
| `/data/backups` | backups |
| `/data/map` | map tiles for the map subdomain |

The panel runs as uid 1000. If you mount an existing server folder, give that user access first (`chown -R 1000:1000 <folder>`).

### Environment variables

| Variable | Default | |
|---|---|---|
| `PANEL_PORT` | `8080` | port the panel listens on inside the container |

### Reverse proxy

Point the panel's subdomain (for example `panel.example.com`) at port 8080. The map gets its own subdomain on the same port later.

## Building locally

```
cd panel
./gradlew shadowJar
java -jar build/libs/nice-control-center-panel-*.jar
```

Requires JDK 25. The panel compiles a few Minecraft-free classes and the dashboard's style straight from `../mod`, so build from a full checkout.

Docker image, from the repository root:

```
docker build -f panel/Dockerfile -t niceron/nicecontrolcenter .
```
