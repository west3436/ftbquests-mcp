---
description: Show FTB Quests bridge status and a quest overview.
---

Call `ftbq_health`, then `ftbq_get_quest_map`. Summarize:
- Is the quest file loaded? The bridge protocol version and loader.
- The chapter groups → chapters with quest counts, and the reward tables.

If `ftbq_health` errors (bridge unreachable), tell the user to confirm the server is running with the `ftbquests-bridge` mod installed and that `FTBQUESTS_SERVER_DIR` (or `FTBQUESTS_BRIDGE_URL`/`FTBQUESTS_BRIDGE_TOKEN`) points at it.
