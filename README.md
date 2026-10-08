# Nice Control Center

A web control center for Fabric servers. It comes in two forms that share the same dashboard:

| | Folder | Where | Runs as |
|---|---|---|---|
| ⛏️ **Fabric mod** | [`mod/`](mod) | inside the server, dashboard on its own port | server-side mod for Minecraft 26.3, published as GitHub releases |
| 🐳 **Panel** | [`panel/`](panel) | Docker / Portainer, runs the server itself | Docker image `niceron/nicecontrolcenter` *(in development)* |

**The mod** is the live performance monitor: what's slowing the server down and why, data pack and mod costs in ms per tick, errors traced to their source, console, updates and editors for server.properties and data pack settings. It works on its own on any Fabric server. See [mod/README.md](mod/README.md).

**The panel** is a Docker container that runs the Minecraft server and manages it from outside: start/stop, crash detection and auto-restart, JVM flags, scheduled actions and restarts, backups, user accounts, log cleanup, a config editor, Fabric/Minecraft version updates, a world trimmer and a public map. It installs the mod as its companion and shows the mod's dashboard while the server runs. See [panel/SETUP.md](panel/SETUP.md).

```
.
├── mod/                     Fabric mod (Java)
│   ├── src/main/java        also compiled into the panel (Minecraft-free classes only)
│   ├── src/main/resources   dashboard web files, shared with the panel
│   └── tools/               release metadata and scripts
├── panel/                   Docker panel (Java)
│   ├── Dockerfile           build from the repository root: docker build -f panel/Dockerfile .
│   ├── portainer-stack.yml  paste into Portainer
│   └── SETUP.md
└── .github/workflows/ci.yml one workflow for both, each part runs when its files changed
```
