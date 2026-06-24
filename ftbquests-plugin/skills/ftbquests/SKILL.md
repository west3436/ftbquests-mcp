---
name: ftbquests
description: Use when authoring or editing FTB Quests content on a running Minecraft server via the ftbquests-bridge MCP tools — creating/editing chapters, quests, tasks, rewards, reward tables, dependencies, and querying blocks/items/quest types.
---

# Authoring FTB Quests

You edit a **live** server through the `ftbq_*` MCP tools. Every create/edit/delete is broadcast to connected players immediately — an admin watching in-game sees your changes in real time.

## Orientation (do this first)
1. `ftbq_health` — confirm `questsLoaded: true`.
2. `ftbq_get_quest_map` — see chapter groups → chapters and reward tables.
3. Drill in with `ftbq_get_chapter` / `ftbq_get_object`.

> **If the `ftbq_*` tools aren't available at all**, the `ftbquests-bridge` MCP server didn't start — almost always because `FTBQUESTS_SERVER_DIR` (or `FTBQUESTS_BRIDGE_URL`+`FTBQUESTS_BRIDGE_TOKEN`) wasn't set when Claude Code launched. The variable is read once at startup, so tell the user to set it (a `.claude/settings.local.json` `env` block is easiest) and **fully restart Claude Code**; setting it mid-session won't bring the tools back. See the plugin README's "Troubleshooting: `ftbq_*` tools not loading".

## Core model (see references/data-model.md)
- Hierarchy: **chapter group → chapter → quest → (tasks + rewards)**; plus **reward tables** and **quest links**.
- **IDs are 16-char uppercase hex** strings. Top-level container id is `0000000000000001`.
- Create with `ftbq_create_object`: `type` ∈ CHAPTER_GROUP | CHAPTER | QUEST | QUEST_LINK | TASK | REWARD | REWARD_TABLE.
  - CHAPTER: put the group id in `extra.group`.
  - QUEST: `parent` = chapter id.
  - TASK / REWARD: `parent` = quest id, and `extra.type` = the type id (e.g. `ftbquests:item`).

## Before creating a task or reward
Learn the type's fields from the curated `references/task-types.md` / `references/reward-types.md` field lists, and copy the exact shape from a working sibling object via `ftbq_get_object`. To find item/block/entity ids, use `ftbq_search_registry` — results are `{id, displayName}` with a localized `displayName`.

> **`ftbq_get_type_schema` is currently broken.** It returns a 500 (`Non [a-z0-9/._-] character in path of location: ...%3A...`) for any namespaced type id such as `ftbquests:item` — which is all of them — because the bridge doesn't URL-decode the type id in the request path. Rely on the `references/` field lists until it's fixed (see the README's "Known issues / limitations"). When it works again, its `defaults` map — keys = serialized field names, read live from the server (pack-aware) — is the source of truth; there is **no** annotated `fields` list (FTB's per-field metadata is client-only, so `fields` is always empty).

## Discipline
- Build in order: chapter group → chapter → quests → tasks/rewards → dependencies (`ftbq_set_dependency`) → positions (`ftbq_move_object`).
- After a batch of edits, call `ftbq_save`.
- Deleting a `CHAPTER_GROUP` does **not** delete its chapters — they fall back to the default group and lose their title. Delete the `CHAPTER` id directly to remove it and cascade to its quests/tasks/rewards (see `references/data-model.md`).
- Errors come back as `{error:{type,status,message}}` — read `message` and correct your input (e.g. wrong `parent`, unknown `type`, bad field). Common types: `bad_request`, `not_found`, `quests_not_loaded`, `server_busy`.

## Safety
A **command reward** (`ftbquests:command`) runs a server command when a player claims it. **Confirm with the user before creating a command reward** and show them the exact command.

See: `references/data-model.md`, `references/task-types.md`, `references/reward-types.md`, `references/workflows.md`.
