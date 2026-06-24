---
description: Show FTB Quests bridge status and a quest overview.
---

Call `ftbq_health`, then `ftbq_get_quest_map`. Summarize:
- Is the quest file loaded? The bridge protocol version and loader.
- The chapter groups → chapters with quest counts, and the reward tables.

If the `ftbq_*` tools are **not available at all** (e.g. `ftbq_health` isn't a known tool / the `ftbquests-bridge` MCP server isn't connected), the MCP server failed to start. The most common cause on first use is that **`FTBQUESTS_SERVER_DIR` (or `FTBQUESTS_BRIDGE_URL`+`FTBQUESTS_BRIDGE_TOKEN`) was not set when Claude Code started.** Tell the user to set it (a `.claude/settings.local.json` `env` block is easiest) and then **fully restart Claude Code** — the variable is read once at startup, so setting it mid-session won't help. See the plugin README's "Troubleshooting: `ftbq_*` tools not loading".

If `ftbq_health` runs but errors (bridge unreachable), tell the user to confirm the server is running with the `ftbquests-bridge` mod installed and that `FTBQUESTS_SERVER_DIR` (or `FTBQUESTS_BRIDGE_URL`/`FTBQUESTS_BRIDGE_TOKEN`) points at it.
