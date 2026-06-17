# Task types

A task is a completion condition on a quest. Create with `ftbq_create_object` type `TASK`, `parent` = the quest id, `extra.type` = the task type id below. **Confirm the exact field names/defaults with `ftbq_get_type_schema task <typeId>`** before creating — packs and KubeJS can add types and change availability. Use `ftbq_list_task_types` to see everything registered on the live server.

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

- Items/blocks/fluids/entities: `ftbq_search_registry` with `kind` = `items` | `blocks` | `fluids` | `entity_types`.
- Advancements/biomes/structures/dimensions: query the corresponding registry kind if the bridge exposes it, or copy the id from an existing task via `ftbq_get_object`.
