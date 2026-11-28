# [ftbquests-mcp](https://modrinth.com/mod/ftb-quests-mcp)
![Modrinth Version](https://img.shields.io/modrinth/v/ftb-quests-mcp)

> Author and edit **FTB Quests** content on a **live** Minecraft server — straight from Claude Code.

Edits are applied through the server's own quest APIs and broadcast to connected players in real time, so an admin can rewrite the quest book while playing. The project has two halves:

- **`ftbquests-bridge`** — a Minecraft mod exposing a loopback HTTP+JSON API inside the server (the jars on the [Releases](https://github.com/west3436/ftbquests-mcp/releases) page).
- **`ftbquests`** — a Claude Code plugin: an MCP server with `ftbq_*` tools, the `ftbquests` skill, and the `/ftbq-status` and `/ftbq-new-chapter` commands.

## Requirements

- A Minecraft **1.21.1** server (**NeoForge** or **Fabric**) running **FTB Quests 2101.1.31 or newer** + **FTB Library** + **Architectury API**. (FTB Quests 2101.1.30 and older have an incompatible API; the bridge declares the dependency, so the loader refuses to start rather than failing at runtime.)
- **Node 18+** on the machine where Claude Code runs.

## Install

**1. Install the bridge mod.** Download the jar for your loader from the [latest release](https://github.com/west3436/ftbquests-mcp/releases/latest) and drop it into the server's `mods/` folder (alongside FTB Quests, FTB Library, and Architectury API), then start the server once:

- Fabric → `ftbquests-bridge-fabric-1.21.1-1.1.0.jar`
- NeoForge → `ftbquests-bridge-neoforge-1.21.1-1.1.0.jar`

Running Minecraft **1.20.1** (Forge or Fabric)? Use `ftbquests-bridge-forge-1.20.1-1.3.0.jar` or `ftbquests-bridge-fabric-1.20.1-1.3.0.jar` from the [1.20.1 release](https://github.com/west3436/ftbquests-mcp/releases/tag/mc1.20.1-v1.3.0) instead; its requirements are on the [`1.20.1` branch](https://github.com/west3436/ftbquests-mcp/tree/1.20.1#requirements).

**2. Install the Claude Code plugin.** In Claude Code:

```
/plugin marketplace add west3436/ftbquests-mcp
/plugin install ftbquests@ftbquests-mcp
```

**3. Build the MCP server and point it at your server.** In the installed plugin's `mcp-server/` folder run `npm install && npm run build`, set `FTBQUESTS_SERVER_DIR` to your Minecraft instance directory, and restart Claude Code. Full details (env vars, the SSH'd-admin setup, troubleshooting) are in the **[plugin README](ftbquests-plugin/README.md)**.

**4. Verify** with `/ftbq-status` — it should report `questsLoaded: true` and list your chapters.

## Quest-book screenshots

On a **game client** (1.21.1 NeoForge/Fabric, or 1.20.1 Forge/Fabric), the bridge can also screenshot the real quest book, so Claude can look at what it built instead of reasoning about grid coordinates blind: `ftbq_render_chapter` (the whole chapter, or zoomed in) and `ftbq_render_quest` (a quest's detail panel). Put the bridge jar for your loader in the client's `mods/`, point `FTBQUESTS_SERVER_DIR` at the client instance, and load a world — in practice, authoring in singleplayer or on a LAN world you host. A dedicated server has no screen to capture, so the screenshot tools only appear when the bridge runs in a client. They need bridge v1.3.0 or newer on 1.20.1; on 1.21.1 they arrive in the next release after v1.1.0. Details are in the [plugin README](ftbquests-plugin/README.md#quest-book-screenshots).

Screenshot support was contributed by [RedyRT](https://github.com/RedyRT) in [ftbquests-visual-mcp](https://github.com/RedyRT/ftbquests-visual-mcp).

## License

MIT — see [LICENSE](LICENSE).
