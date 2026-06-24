# ftbquests — Claude Code plugin

Author and edit **FTB Quests** content on a **live** Minecraft server from Claude Code. Changes are applied through the server's own quest APIs and broadcast to connected players in real time — so an admin can edit quests while playing.

This plugin is the companion to the **`ftbquests-bridge`** mod (a loopback HTTP+JSON API inside the Minecraft server). The plugin ships:

- an MCP server (`mcp-server/`) exposing `ftbq_*` tools that adapt to the bridge's HTTP API,
- the `ftbquests` skill teaching the data model and authoring workflows,
- slash commands `/ftbq-status` and `/ftbq-new-chapter`.

## Requirements

- A Minecraft **1.21.1** server (NeoForge or Fabric) running **FTB Quests** + **FTB Library** + **Architectury API** and the **`ftbquests-bridge`** mod.
- **Node 18+** on the machine where Claude Code runs.

## Setup

1. **Install the mod.** Put `ftbquests-bridge` in the server's `mods/` alongside FTB Quests, FTB Library, and Architectury API. Start the server once — it generates `config/ftbquests-bridge/runtime.json` (the bridge port + auth token) and binds the API to `127.0.0.1` by default.
2. **Build the MCP server.**
   ```bash
   cd mcp-server
   npm install
   npm run build
   ```
3. **Install this plugin** in Claude Code.
4. **Point the plugin at the server — this step is required, or no `ftbq_*` tools appear.** The MCP server reads its target from the environment **at Claude Code startup**; if it finds nothing it exits immediately and registers zero tools (see [Troubleshooting](#troubleshooting-ftbq_-tools-not-loading)). Provide **one** of:
   - `FTBQUESTS_SERVER_DIR` → the server directory containing `config/ftbquests-bridge/runtime.json` (recommended — the plugin reads the port + token from `runtime.json` for you), **or**
   - `FTBQUESTS_BRIDGE_URL` + `FTBQUESTS_BRIDGE_TOKEN` → the bridge URL and bearer token directly.

   Set it where Claude Code will see it. Easiest is a per-project `env` block in `.claude/settings.local.json`:
   ```json
   {
     "env": {
       "FTBQUESTS_SERVER_DIR": "C:/path/to/minecraft/instance"
     }
   }
   ```
   (or export it as an OS environment variable before launching Claude Code). **Then fully restart Claude Code** — MCP servers are spawned once at startup, so a value set mid-session has no effect until you relaunch.
5. **Verify** with `/ftbq-status` — it should report `questsLoaded: true` and list your chapters. If the command reports the tools are missing, see [Troubleshooting](#troubleshooting-ftbq_-tools-not-loading).

### The SSH'd-admin scenario

Run Claude Code **on the server host** (e.g. over SSH) while connected to the same server in-game. The bridge is loopback-only, so co-location means the API is reachable and the credentials file is local — and your edits appear live in your own quest book.

## Configuration (mod side: `config/ftbquests-bridge.json`)

| key | default | meaning |
|---|---|---|
| `bindAddress` | `127.0.0.1` | interface to bind |
| `port` | `25599` | API port |
| `allowRemote` | `false` | permit non-loopback clients (still requires the token) |
| `saveMode` | `immediate` | save to disk after each edit, or `lazy` |

## Security

- The API is **loopback-only by default** and always requires the bearer token from `runtime.json`.
- Setting `allowRemote: true` lets non-loopback clients connect **with the token** — use only on a trusted network; it is logged loudly.
- **Command rewards** (`ftbquests:command`) embed a server command that runs when a player claims them. The skill will confirm with you before creating one.

## Troubleshooting: `ftbq_*` tools not loading

**Symptom:** None of the `ftbq_*` tools are available — `/ftbq-status` can't run, the tools don't appear in the tool list, and (in newer clients) `/mcp` shows `ftbquests-bridge` as failed/disconnected. There may be **no visible error**: the plugin's skill and slash commands still load (they're static files), so the plugin *looks* installed while the MCP server is dead.

**Cause:** The MCP server (`mcp-server/dist/index.js`) resolves the bridge target from the environment **at startup**. If neither `FTBQUESTS_SERVER_DIR` nor `FTBQUESTS_BRIDGE_URL`+`FTBQUESTS_BRIDGE_TOKEN` is set, it prints `[ftbquests] fatal: Cannot locate FTB Quests bridge...` to stderr and exits, registering no tools. Because the variable is read at spawn time, **setting it after Claude Code has started does nothing** until you restart.

**Fix:**
1. Set `FTBQUESTS_SERVER_DIR` (or the URL+token pair) — see [Setup step 4](#setup). The `.claude/settings.local.json` `env` block is the most reliable per-project method.
2. **Fully quit and relaunch Claude Code.**
3. Re-run `/ftbq-status`; it should now report `questsLoaded: true`.

**Quick self-check** (does the server start with your env?) — from `mcp-server/`:
```bash
FTBQUESTS_SERVER_DIR="C:/path/to/minecraft/instance" node dist/index.js </dev/null
# expect: [ftbquests] connected to bridge http://127.0.0.1:25599 (protocol 1)
# if you instead see "[ftbquests] fatal: Cannot locate ..." the variable isn't set/visible.
```
If the server connects here but the tools still don't appear in Claude Code, the variable isn't reaching Claude Code's environment — move it into `.claude/settings.local.json` `env` and restart. Also confirm `dist/` is built (`npm run build`) and the Minecraft server with the `ftbquests-bridge` mod is actually running.

## Known issues / limitations

Confirmed against a live NeoForge 1.21.1 server (FTB Quests 2101.x) and verified by reading state back through the bridge. These affect the tools listed below but not the rest of the authoring flow.

### `ftbq_get_type_schema` errors on any namespaced type id

**Symptom:** Calling `ftbq_get_type_schema` with a normal type id such as `ftbquests:item` returns:

> `{"error":{"type":"internal","status":500,"message":"Non [a-z0-9/._-] character in path of location: minecraft:ftbquests%3Aitem"}}`

Every FTB task/reward type id is namespaced (contains a `:`), so the tool currently fails for **all** of them.

**Cause:** The MCP server percent-encodes the id into the request path (`/task-types/ftbquests%3Aitem/schema`), but the bridge builds a `ResourceLocation` from the **un-decoded** path segment, so the literal `%` is rejected. The fix is bridge-side: URL-decode the path parameter before parsing.

**Workaround:** Use the field lists in `skills/ftbquests/references/task-types.md` and `reward-types.md` (e.g. an item task/reward takes `item` + `count`), and copy field shapes from a working sibling via `ftbq_get_object`. Resolve item/block/entity ids with `ftbq_search_registry`.

### Deleting a `CHAPTER_GROUP` does not cascade to its chapters

**Symptom:** `ftbq_delete_object <chapterGroupId>` returns `ok:true`, but the group's chapters are **not** deleted — they are silently reparented into the default group `0000000000000000`, and each reparented chapter **loses its title** (reads back as `""`, even though it round-tripped before the delete).

**Workaround:** To remove a chapter and everything under it, call `ftbq_delete_object <chapterId>` directly — that **does** cascade to the chapter's quests, tasks, and rewards. Delete chapters before (or instead of) deleting their group.

## MCP registration note

This plugin declares its MCP server in `.mcp.json` at the plugin root. If your Claude Code version expects MCP servers declared inside `.claude-plugin/plugin.json` instead, move the `mcpServers` block there. The MCP server must be built (`npm run build`) before first use.

## Tools

`ftbq_health`, `ftbq_search_registry`, `ftbq_list_task_types`, `ftbq_list_reward_types`, `ftbq_get_type_schema`, `ftbq_get_quest_map`, `ftbq_get_chapter`, `ftbq_get_object`, `ftbq_search_quests`, `ftbq_create_object`, `ftbq_edit_object`, `ftbq_delete_object`, `ftbq_set_dependency`, `ftbq_move_object`, `ftbq_save`.
