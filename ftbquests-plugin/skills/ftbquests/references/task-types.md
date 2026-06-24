# Task types

A task is a completion condition on a quest. Create with `ftbq_create_object` type `TASK`, `parent` = the quest id, `extra.type` = the task type id below. Use `ftbq_list_task_types` to see everything registered on the live server (packs and KubeJS can add types and change availability).

> **`ftbq_get_type_schema` is currently broken** — it 500s on any namespaced type id (i.e. all of them); see the plugin README's "Known issues / limitations". Use the field names in the table below and copy the exact shape from a working sibling task via `ftbq_get_object` instead.

## Built-in task types

| typeId | purpose | key fields (verify via schema) |
|---|---|---|
| `ftbquests:item` | submit or hold item(s) | `item` (item id / stack), `count` |
| `ftbquests:checkmark` | manual checkbox the player ticks | `title` only |
| `ftbquests:kill` | kill N of an entity | `entity`, `value` |
| `ftbquests:dimension` | enter a dimension | `dimension` |
| `ftbquests:advancement` | earn a vanilla advancement | `advancement` |
| `ftbquests:stat` | reach a statistic value | `stat`, `value` |
| `ftbquests:observation` | look at / observe a target | observe target fields |
| `ftbquests:location` | reach an area | `dimension`, position + size |
| `ftbquests:biome` | enter a biome | `biome` |
| `ftbquests:structure` | enter a structure | `structure` |
| `ftbquests:fluid` | collect a fluid amount | `fluid`, `amount` |
| `ftbquests:xp` | accumulate XP | `value`, points vs levels flag |
| `ftbquests:gamestage` | require a game stage | `stage` (needs a stages mod) |
| `ftbquests:custom` | script-driven completion | defined by KubeJS / the providing mod |

`ftbquests:energy` exists but is registered only when a compatible energy mod (TeamReborn Energy) is present — it will appear in `ftbq_list_task_types` when available.

## Finding ids for fields

Use `ftbq_search_registry` with a `kind` (supports `query`, `limit`, `offset`). Each result is `{id, displayName}`; `displayName` is a localized name when one resolves server-side and otherwise falls back to the `id` — either way it confirms you picked the right id.

| `kind` | source | `displayName` |
|---|---|---|
| `items`, `blocks`, `entity_types`, `mob_effects` | built-in registries | localized (e.g. `"Diamond"`) |
| `advancements` | the server's loaded advancements | advancement title (e.g. `"Diamond!"`); recipe/hidden ones fall back to the id |
| `fluids` | built-in registry | id |
| `stats` | `minecraft:custom` stat ids (what `ftbquests:stat` accepts) | id |
| `biomes`, `structures` | the server's datapack registries | id |
| `dimensions` | the world's currently-loaded dimension ids | id |

Tag-valued forms that `ftbquests:biome` and `ftbquests:structure` also accept (e.g. `#minecraft:is_forest`, `#minecraft:village`) are **not** enumerated — pass a known tag id directly. For anything else, copy an id from an existing task via `ftbq_get_object`.
