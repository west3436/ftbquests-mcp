# Reward types

A reward is granted when a quest is completed/claimed. Create with `ftbq_create_object` type `REWARD`, `parent` = the quest id, `extra.type` = the reward type id below. Use `ftbq_list_reward_types` to see everything registered live.

> **`ftbq_get_type_schema` is currently broken** — it 500s on any namespaced type id (i.e. all of them); see the plugin README's "Known issues / limitations". Use the field names in the table below and copy the exact shape from a working sibling reward via `ftbq_get_object` instead.

## Built-in reward types

| typeId | purpose | key fields (verify via schema) |
|---|---|---|
| `ftbquests:item` | give item(s) | `item`, `count` |
| `ftbquests:xp` | give XP points | `xp` |
| `ftbquests:xp_levels` | give XP levels | `xp_levels` |
| `ftbquests:choice` | player picks one of several item rewards | list of choices |
| `ftbquests:loot` | roll a reward (loot) table | `table` (reward table id) |
| `ftbquests:random` | random entry from a reward table | `table` |
| `ftbquests:all_table` | grant every entry of a reward table | `table` |
| `ftbquests:advancement` | grant a vanilla advancement | `advancement` |
| `ftbquests:toast` | show a cosmetic toast | text fields |
| `ftbquests:gamestage` | grant a game stage | `stage` |
| `ftbquests:currency` | grant currency | amount (not available by default — integration only) |
| `ftbquests:custom` | script-driven | defined by KubeJS / the providing mod |
| `ftbquests:command` | ⚠️ **runs a server command on claim** | `command` |

## ⚠️ Command rewards

`ftbquests:command` stores a server command that executes when a player claims the reward (e.g. `/give @p minecraft:diamond 5`, or anything an op could run). **Always confirm with the user before creating a command reward, and show them the exact command string.** Do not create one speculatively.

## Reward tables

`loot`, `random`, and `all_table` rewards reference a `REWARD_TABLE` by id. Create the table first (`ftbq_create_object` type `REWARD_TABLE`, parent `0000000000000001`), populate its weighted entries, then point the reward's `table` field at it. See `workflows.md`.
