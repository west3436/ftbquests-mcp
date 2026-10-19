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

Two things differ between the FTB Quests lines: **where** the text is stored, and — on 2001.x — **what shape** each field has. Both are driven by the mod version, not by the bridge: the `-forge` bridge is the 1.20.1 build and `ftbq_health` reports `ftbQuestsVersion` there, while the 1.21.1 bridges report no version field (infer the line from which jar you installed).

**FTB Quests 2001.x (Minecraft 1.20.1, bridge `-forge` 1.2.x).** Text is ordinary object NBT, so it all round-trips through `properties` on `ftbq_create_object` / `ftbq_edit_object` and comes back from `ftbq_get_object`. No separate lang file. **The shape is per object type** — a mistyped field is silently ignored by the object's own `readData`, so on an edit it *erases* the stored value:

| object type | `title` | `subtitle` | `description` |
|---|---|---|---|
| `CHAPTER` | string | **list of strings** (one per line) | **does not exist** |
| `QUEST` | string | string | **list of strings** (one per paragraph) |
| everything else | string | — | — |

The bridge repairs the obvious mistakes and says so in a `warnings` array: a string sent as a chapter subtitle is wrapped into a one-element list, a list sent as a quest subtitle is joined with newlines, and a chapter `description` is dropped with a warning rather than accepted and quietly lost. Prefer sending the correct shape and treat an empty `warnings` array as confirmation that nothing was coerced.

**FTB Quests 2101.x (Minecraft 1.21.1, bridge 1.1.x).** Title/subtitle/description **text is not stored in the object's serialized data** (which holds only icon, tags, and type-specific config). It lives in FTB's **TranslationManager** (server-side lang files), keyed by object id:

- **Reads work.** `ftbq_get_quest_map` / `ftbq_get_object` return the current title (the server-safe raw title).
- **Title writes work.** Passing `title` in `properties` persists — the bridge routes it through the TranslationManager via `setRawTitle`. The create/edit response echoes the stored `title`.
- **`subtitle`/`description` writes are not wired.** Passing them is **not silently dropped**: the create/edit response includes a `warnings` array noting they were ignored. Set them in-game.

Always **read back with `ftbq_get_object`** to confirm what actually landed, and check the response for a `warnings` array.
