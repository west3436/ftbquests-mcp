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
Call `ftbq_get_type_schema` for that type to learn its fields. The schema returns `defaults` — a sample object of the type's serialized field names + baseline values, read live from the server (pack-aware). There is **no** annotated `fields` list: FTB's per-field metadata is client-only and unavailable on a dedicated server, so `fields` is always empty. Treat `defaults` (its keys are the field names) plus the curated `references/` notes as the source of truth. To find item/block/entity ids, use `ftbq_search_registry` — results are `{id, displayName}` with a localized `displayName`.

## Discipline
- Build in order: chapter group → chapter → quests → tasks/rewards → dependencies (`ftbq_set_dependency`) → positions (`ftbq_move_object`).
- After a batch of edits, call `ftbq_save`.
- Errors come back as `{error:{type,status,message}}` — read `message` and correct your input (e.g. wrong `parent`, unknown `type`, bad field). Common types: `bad_request`, `not_found`, `quests_not_loaded`, `server_busy`.

## Safety
A **command reward** (`ftbquests:command`) runs a server command when a player claims it. **Confirm with the user before creating a command reward** and show them the exact command.

See: `references/data-model.md`, `references/task-types.md`, `references/reward-types.md`, `references/workflows.md`.
