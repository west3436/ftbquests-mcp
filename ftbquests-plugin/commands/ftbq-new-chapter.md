---
description: Scaffold a new FTB Quests chapter (guided).
argument-hint: <chapter title> [in group <group id>]
---

Create a new chapter titled "$ARGUMENTS".

1. If no group id is given, call `ftbq_get_quest_map` and ask which chapter group to use (or offer to create one with `ftbq_create_object` type `CHAPTER_GROUP`, parent `0000000000000001`).
2. Create the chapter: `ftbq_create_object` type `CHAPTER`, parent `0000000000000001`, `extra.group` = the chosen group id, `properties.title` = the title.
3. Read it back with `ftbq_get_object` to confirm the title landed, report the new chapter id, and call `ftbq_save`.
