# Workflows

Concrete tool-call sequences. IDs shown as `<...>` are returned by the preceding create call. Always finish a batch with `ftbq_save`. Remember every change is broadcast live to connected players.

## A. Build a progression chapter

A 3-quest line: gather wood → craft a pickaxe → mine iron, each unlocking the next.

1. `ftbq_create_object` `{ type:"CHAPTER_GROUP", parent:"0000000000000001", properties:{ title:"Getting Started" } }` → `<groupId>`
2. `ftbq_create_object` `{ type:"CHAPTER", parent:"0000000000000001", properties:{ title:"Day One" }, extra:{ group:"<groupId>" } }` → `<chapterId>`
3. Quest 1: `ftbq_create_object` `{ type:"QUEST", parent:"<chapterId>", properties:{ title:"Gather Wood", x:0, y:0 } }` → `<q1>`
   - Task: `ftbq_get_type_schema task ftbquests:item`, then `ftbq_create_object` `{ type:"TASK", parent:"<q1>", extra:{ type:"ftbquests:item" }, properties:{ item:"minecraft:oak_log", count:16 } }`
   - Reward: `ftbq_create_object` `{ type:"REWARD", parent:"<q1>", extra:{ type:"ftbquests:item" }, properties:{ item:"minecraft:apple", count:3 } }`
4. Quest 2 `<q2>` "Craft a Pickaxe" at `x:2,y:0` — item task `minecraft:stone_pickaxe`, xp reward.
5. Quest 3 `<q3>` "Mine Iron" at `x:4,y:0` — item task `minecraft:raw_iron` count 5.
6. Wire the line: `ftbq_set_dependency { questId:"<q2>", dependsOnId:"<q1>", add:true }`, then `{ questId:"<q3>", dependsOnId:"<q2>", add:true }`.
7. `ftbq_save`.

## B. Import an item list as collection tasks

Given a list of item names, build one quest whose tasks collect each.

1. For each name, `ftbq_search_registry { kind:"items", query:"<name>" }` and pick the right `id` (prefer exact `minecraft:<name>` or the modded id).
2. `ftbq_create_object` `{ type:"QUEST", parent:"<chapterId>", properties:{ title:"Resource Run", x:.., y:.. } }` → `<q>`
3. For each resolved id, `ftbq_create_object` `{ type:"TASK", parent:"<q>", extra:{ type:"ftbquests:item" }, properties:{ item:"<id>", count:<n> } }`.
4. `ftbq_save`. Report which names failed to resolve so the user can correct them.

## C. Create and attach a reward table

1. `ftbq_create_object` `{ type:"REWARD_TABLE", parent:"0000000000000001", properties:{ title:"Common Loot" } }` → `<tableId>`
2. Read `ftbq_get_object <tableId>` to see the table's data shape, then add weighted entries by editing it (`ftbq_edit_object <tableId> { ... weightedRewards ... }`) following that shape. (Reward-table entry editing beyond basic fields is a v1 edge — verify the shape from the live object before editing.)
3. On a quest, add a reward that draws from it: `ftbq_create_object` `{ type:"REWARD", parent:"<questId>", extra:{ type:"ftbquests:random" }, properties:{ table:"<tableId>" } }`.
4. `ftbq_save`.

## Tips
- If a create returns `bad_request`, re-check `parent` (right container?), `extra.type`/`extra.group`, and field names against `ftbq_get_type_schema` / a working sibling object.
- Reposition with `ftbq_move_object` once the structure exists, rather than guessing coordinates up front.
- Read back anything you create with `ftbq_get_object` to confirm fields landed where you expect (especially titles/descriptions).
