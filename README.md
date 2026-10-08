# Nice Control Center

A web control center for Fabric servers. It comes in two forms that share the same dashboard:

| | Folder | Where | Runs as |
|---|---|---|---|
| ⛏️ **Fabric mod** | [`mod/`](mod) | inside the server, dashboard on its own port | server-side mod for Minecraft 26.1–26.3, published as GitHub releases (one jar per version) |
| 🐳 **Panel** | [`panel/`](panel) | Docker / Portainer, runs the server itself | Docker image `niceron/nicecontrolcenter` |

**The mod** is the live performance monitor: what's slowing the server down and why, data pack and mod costs in ms per tick, errors traced to their source, console, updates and editors for server.properties and data pack settings. It works on its own on any Fabric server. See [mod/README.md](mod/README.md).

**The panel** is a Docker container that runs the Minecraft server and manages it from outside:
- start/stop, crash detection with automatic restarts, JVM flags
- scheduled tasks with countdown restarts, backups
- user accounts with roles and an audit log
- a file explorer, config editor, SFTP and log cleanup
- Fabric/Minecraft updates with rollback
- a Discord bridge, a public web map, pregeneration and a world trimmer

It installs the mod as its in-game helper (for profiling, live overlays, Discord linking and pregeneration) and shows the mod's dashboard. Everything else works without it. See [panel/SETUP.md](panel/SETUP.md).

```
.
├── mod/                     Fabric mod (Java)
│   ├── src/main/java        shared by every Minecraft version; a few classes are also compiled into the panel
│   ├── src/mc/<version>     the few classes that differ per Minecraft version
│   ├── versions/            Minecraft and Fabric API versions per build target
│   ├── src/main/resources   dashboard web files, shared with the panel
│   └── tools/               release metadata and scripts
├── panel/                   Docker panel (Java)
│   ├── Dockerfile           build from the repository root: docker build -f panel/Dockerfile .
│   ├── portainer-stack.yml  paste into Portainer
│   └── SETUP.md
└── .github/workflows/ci.yml one workflow for both, each part runs when its files changed
```
