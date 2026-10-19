# ftbquests-mcp

> Author and edit **FTB Quests** content on a **live** Minecraft server — straight from Claude Code.

Edits are applied through the server's own quest APIs and broadcast to connected players in real time, so an admin can rewrite the quest book while playing. The project has two halves:

- **`ftbquests-bridge`** — a Minecraft mod exposing a loopback HTTP+JSON API inside the server (the jars on the [Releases](https://github.com/west3436/ftbquests-mcp/releases) page).
- **`ftbquests`** — a Claude Code plugin: an MCP server with `ftbq_*` tools, the `ftbquests` skill, and the `/ftbq-status` and `/ftbq-new-chapter` commands.

## Requirements

The bridge ships one source tree per Minecraft line; pick the one matching your server.

| Server | Bridge jar | FTB Quests | Loader deps |
|---|---|---|---|
| **1.20.1 Forge** | `ftbquests-bridge-forge-1.20.1-<version>.jar` from the [1.20.1 release](https://github.com/west3436/ftbquests-mcp/releases/tag/mc1.20.1-v1.2.0) | **2001.4.11+** (2001.x line) | + FTB Library, FTB Teams, Architectury API |
| **1.21.1 NeoForge / Fabric** | `ftbquests-bridge-neoforge-1.21.1-<version>.jar` / `-fabric-1.21.1-<version>.jar` from the [latest release](https://github.com/west3436/ftbquests-mcp/releases/latest) | **2101.1.31+** (2101.x line) | + FTB Library, Architectury API |

Each jar declares its FTB Quests version range in its mod metadata, so the loader refuses to start rather than failing at runtime when the installed FTB Quests is too old.

You also need **Node 18+** on the machine where Claude Code runs.

## Install

**1. Install the bridge mod.** Download the jar for your Minecraft version and loader from the release linked in the table above and drop it into the server's `mods/` folder (alongside FTB Quests, FTB Library, and — on 1.20.1 — FTB Teams, plus Architectury API), then start the server once. It logs `FTB Quests Bridge listening on 127.0.0.1:25599` and writes `config/ftbquests-bridge.json` (settings: port, bind address, save mode) plus `config/ftbquests-bridge/runtime.json` (bound port and a generated bearer token) — that token is what the plugin authenticates with.

**2. Install the Claude Code plugin.** In Claude Code:

```
/plugin marketplace add west3436/ftbquests-mcp
/plugin install ftbquests@ftbquests-mcp
```

**3. Build the MCP server and point it at your server.** In the installed plugin's `mcp-server/` folder run `npm install && npm run build`, set `FTBQUESTS_SERVER_DIR` to your Minecraft instance directory, and restart Claude Code. Full details (env vars, the SSH'd-admin setup, troubleshooting) are in the **[plugin README](ftbquests-plugin/README.md)**.

**4. Verify** with `/ftbq-status` — it should report `questsLoaded: true` and list your chapters.

## License

MIT — see [LICENSE](LICENSE).
