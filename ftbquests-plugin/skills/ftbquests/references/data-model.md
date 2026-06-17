# FTB Quests data model

## Object hierarchy

```
QuestFile (the root; you never create/delete it)
└── ChapterGroup            container; ordered list of chapters
    └── Chapter             container; holds quests, quest links, images
        ├── Quest           node on the chapter grid; holds tasks + rewards + dependencies
        │   ├── Task        a completion condition (item, kill, advancement, ...)
        │   └── Reward      granted on completion/claim (item, xp, command, ...)
        └── QuestLink       a visual reference to a quest defined elsewhere
RewardTable                 top-level; a weighted pool used by loot/random rewards
```

**Containers vs leaves.** `CHAPTER_GROUP`, `CHAPTER`, `QUEST`, `REWARD_TABLE` are containers. `TASK`, `REWARD`, `QUEST_LINK` are leaves attached to a parent.

## IDs

- Every object has a **16-char uppercase hex** id (e.g. `1A2B3C4D5E6F7080`). Pass and receive ids in that form.
- The **top-level container id is `0000000000000001`** — use it as `parent` when creating a `CHAPTER_GROUP`, a top-level `CHAPTER`, or a `REWARD_TABLE`.
- The bridge generates the id on create and returns it; never invent ids.

## Creating objects — parent and `extra`

`ftbq_create_object(type, parent, properties, extra)`:

| type | parent | required `extra` |
|---|---|---|
| `CHAPTER_GROUP` | `0000000000000001` | — |
| `CHAPTER` | `0000000000000001` | `{"group": "<chapterGroupId>"}` |
| `QUEST` | `<chapterId>` | — |
| `QUEST_LINK` | `<chapterId>` | (target via properties `linkId`) |
| `TASK` | `<questId>` | `{"type": "ftbquests:item"}` (the task type id) |
| `REWARD` | `<questId>` | `{"type": "ftbquests:item"}` (the reward type id) |
| `REWARD_TABLE` | `0000000000000001` | — |

`properties` holds the object's own fields (title, icon, task/reward-specific fields). Discover them with `ftbq_get_type_schema` (tasks/rewards) and by reading an existing object of the same kind via `ftbq_get_object`.

## Quest layout fields

A `QUEST` has grid-layout fields you can set via `properties` (or `ftbq_move_object` for position): `x`, `y` (grid coordinates), `size`, `shape` (e.g. `circle`, `square`, `diamond`, `hexagon`, `pentagon`, `gear`, `rsquare`). Position quests after creating them so the chapter reads cleanly.

## Dependencies

A quest's prerequisites are stored as a list of hex ids under the `dependencies` field. Prefer `ftbq_set_dependency(questId, dependsOnId, add)` over hand-editing the array. Tag references of the form `#sometag` are also valid dependency entries (match any object carrying that tag).

## Titles & descriptions

Quest objects support a title and (for quests) a description/subtitle. The exact place these belong — directly in `properties` vs in `extra` at create time (FTB Quests routes initial text through its translation manager) — is confirmed during mod implementation; **read back the object with `ftbq_get_object` after create** to see where the text actually landed, and edit via the same field. When in doubt, `ftbq_get_object` is the ground truth for any field's current shape and location.
