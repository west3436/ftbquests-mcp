# ftbquests-mcp

> Author and edit **FTB Quests** content on a **live** Minecraft server — straight from Claude Code.

Edits are applied through the server's own quest APIs and broadcast to connected players in real time, so an admin can rewrite the quest book while playing. The project has two halves:

- **`ftbquests-bridge`** — a Minecraft mod exposing a loopback HTTP+JSON API inside the server (the jars on the [Releases](https://github.com/west3436/ftbquests-mcp/releases) page).
- **`ftbquests`** — a Claude Code plugin: an MCP server with `ftbq_*` tools, the `ftbquests` skill, and the `/ftbq-status` and `/ftbq-new-chapter` commands.

## Requirements

- A Minecraft **1.21.1** server (**NeoForge** or **Fabric**) running **FTB Quests** + **FTB Library** + **Architectury API**.
- **Node 18+** on the machine where Claude Code runs.

## Install

**1. Install the bridge mod.** Download the jar for your loader from the [latest release](https://github.com/west3436/ftbquests-mcp/releases/latest) and drop it into the server's `mods/` folder (alongside FTB Quests, FTB Library, and Architectury API), then start the server once:

- Fabric → `ftbquests-bridge-fabric-1.0.0.jar`
- NeoForge → `ftbquests-bridge-neoforge-1.0.0.jar`

**2. Install the Claude Code plugin.** In Claude Code:

```
/plugin marketplace add west3436/ftbquests-mcp
/plugin install ftbquests@ftbquests-local
```

**3. Build the MCP server and point it at your server.** In the installed plugin's `mcp-server/` folder run `npm install && npm run build`, set `FTBQUESTS_SERVER_DIR` to your Minecraft instance directory, and restart Claude Code. Full details (env vars, the SSH'd-admin setup, troubleshooting) are in the **[plugin README](ftbquests-plugin/README.md)**.

**4. Verify** with `/ftbq-status` — it should report `questsLoaded: true` and list your chapters.

## License

MIT — see [LICENSE](LICENSE).
