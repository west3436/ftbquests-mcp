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

On FTB Quests 2101.x, title/subtitle/description **text is not stored in the object's serialized data** (`writeData`/`readData` NBT, which holds only icon, tags, and type-specific config). It lives in FTB's **TranslationManager** (server-side lang files), keyed by object id.

What this means through the bridge today:

- **Reads work.** `ftbq_get_quest_map` / `ftbq_get_object` return the current title (the server-safe raw title).
- **Writes are not wired yet.** Passing `title`/`description` in `properties` is **silently ignored** — it is not part of the NBT write path, so it does not persist. (Writing text requires routing it through the TranslationManager via `setRawTitle`; that is a known pending follow-up.)

So do **not** rely on setting titles/descriptions through the bridge yet: the `title:` fields shown in `workflows.md` express intent but won't persist on a live server today. If a quest needs a title now, set it in-game and tell the user that bridge-side title authoring is pending. Always **read back with `ftbq_get_object`** to confirm what actually landed.
