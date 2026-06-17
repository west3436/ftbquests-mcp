# FTB Quests MCP Server — Design Specification

- **Date:** 2026-06-17
- **Status:** Approved design (pre-implementation)
- **Authors:** west3436 + Claude
- **Scope of v1:** Quest *design* (read registries; create/read/edit/delete all quest structure). No player-progress management.
- **Target platform (v1):** Minecraft **1.21.1**, multi-loader via **Architectury** (**NeoForge + Fabric**). 1.20.1/Forge is a later branch.

---

## 1. Goal

Let an AI agent (Claude Code) fully author and edit FTB Quests content on a **live, running** Minecraft server. The agent must be able to:

1. Enumerate all available building blocks — every registered **item/block/fluid/entity/biome/dimension/etc.**, and every registered **task type and reward type** (including those added by other mods or KubeJS in the pack).
2. **Read** the existing quest structure (chapter groups, chapters, quests, tasks, rewards, reward tables, dependencies, quest links, layout).
3. **Create and edit** every quest aspect, with changes **hot-applied and synced to connected clients** exactly as the in-game editor does — so an admin SSH'd into the server box and simultaneously playing sees edits appear live.

### Primary usage scenario

A server admin is SSH'd into the server host, running Claude Code there, **while** connected to the same server in-game with FTB Quests edit/admin rights. The companion runs co-located with the server; the mod's API is bound to loopback. Edits made by the agent broadcast to all clients, including the admin's own game client, in real time.

---

## 2. Non-goals (v1)

- **Player/team progress management** (inspect/reset/complete quests per player). Read/write of `TeamData` progression is out of scope.
- **1.20.1 / Forge** support. The 1.20.1 branch uses NBT serialization + Architectury `SimpleNetworkManager` (a materially different code path) and is deferred to a follow-on branch (see §13).
- **A mod-native MCP server** (the rejected "Architecture B"). The mod exposes plain HTTP+JSON; MCP lives in the companion.
- **Direct command execution.** The bridge never runs arbitrary server commands. The only command surface is the `CommandReward` *data* object, which runs on quest claim and is flagged for user confirmation in the skill.

---

## 3. Background: verified FTB Quests internals

These facts were verified against the `1.21.1/main` branch of `github.com/FTBTeam/FTB-Quests` (cross-checked vs `1.20.1/main`). Base package: `dev.ftb.mods.ftbquests`. They are the foundation the implementation reuses; an implementer should not need to re-derive them.

### 3.1 Repository / module layout

Multi-loader via Architectury + Gradle. For MC 1.21.1 the modules are `common`, `fabric`, `neoforge` (1.20.1 uses `forge` instead of `neoforge`). All shared logic (data model, task/reward types, serialization) lives in `common/`. Java 21, official Mojang mappings. Sibling deps: **FTB Library** (SNBT + config-group classes live here, *not* in FTB Quests) and **FTB Teams**.

### 3.2 Data model

```
QuestObjectBase (abstract; public final long id; List<String> tags; ItemStack rawIcon)
├── QuestObject (abstract)
│   ├── BaseQuestFile (abstract, implements api.QuestFile)
│   │   └── ServerQuestFile
│   ├── ChapterGroup        (children: List<Chapter>)
│   ├── Chapter (final)      (children: List<Quest>, List<QuestLink>, List<ChapterImage>; String filename)
│   ├── Quest (final)        (children: List<Task>, List<Reward>; deps List<QuestObject>; double x,y,size; String shape)
│   └── QuestLink            (references target by long linkId, NOT an object pointer)
├── Reward (abstract)        [pkg quest.reward]  ← extends QuestObjectBase directly
└── RewardTable             [pkg quest.loot]    ← extends QuestObjectBase directly (children: List<WeightedReward>)
```

`Task` (pkg `quest.task`) extends `QuestObject`. Note `Reward`/`RewardTable` extend `QuestObjectBase` *directly* (not `QuestObject`). Type discriminator: `QuestObjectType getObjectType()` → enum `{NULL, FILE, CHAPTER, QUEST, TASK, REWARD, REWARD_TABLE, CHAPTER_GROUP, QUEST_LINK, IMAGE}`. Every object is also indexed in `BaseQuestFile.questObjectMap` (a `Long2ObjectOpenHashMap<QuestObjectBase>`) keyed by `long id`.

### 3.3 IDs

Canonical id is a **`long`** (not a String). Generated as a random positive long (`0L`/`1L` reserved; collisions rejected). Serialized/displayed as **16-char uppercase hex** via `getCodeString(long)` = `String.format("%016X", id)`; parsed back with `parseCodeString`/`parseHexId` (tolerates a leading `#`). Dependencies are stored in memory as resolved `List<QuestObject>` but **written as hex code strings** under key `"dependencies"`, resolved via `file.getID(string)`. `getID(String)` also supports **`#tag`** references (matched against object tags). `QuestLink` stores its target as `long linkId`.

### 3.4 On-disk storage

- **Quest definitions:** `config/ftbquests/quests/` (config folder, *not* world): `data.snbt` (incl. int `version`), `chapters/<filename>.snbt` (one per chapter, with nested quests + links), `reward_tables/<filename>.snbt` (one per table), `chapter_groups.snbt`, `lang/` (translations). `getFilename()` defaults to the hex id.
- **Progression:** in the world save under `<world>/ftbquests/` (out of scope for v1).
- Format is **SNBT**; helpers (`SNBT`, `SNBTCompoundTag`) live in **FTB Library**.

Per-object serialization (1.21.x signatures):
```java
void writeData(Json5Object json, HolderLookup.Provider provider)
void readData (Json5Object json, HolderLookup.Provider provider)
void writeNetData(RegistryFriendlyByteBuf buf)
void readNetData (RegistryFriendlyByteBuf buf)
```
File orchestration: `BaseQuestFile.writeDataFull(Path, provider)` / `readDataFull(Path, provider)`.

> **1.21 nuance — Json5.** On the 1.21.x branch, `readData`/`writeData` operate on `Json5Object` (from the bridge's perspective, ordinary JSON). On 1.20.1 they operate on `CompoundTag` (NBT). This is the main reason v1 anchors on 1.21.1.

### 3.5 Task & reward type registries (runtime enumeration)

Not a vanilla `Registry<T>` — plain insertion-ordered maps on public interfaces:

- `dev.ftb.mods.ftbquests.quest.task.TaskTypes.TYPES` → `Map<ResourceLocation, TaskType>`
- `dev.ftb.mods.ftbquests.quest.reward.RewardTypes.TYPES` → `Map<ResourceLocation, RewardType>`

Every registrant (built-in, modded, KubeJS) goes through `register(...)`, so after load these maps contain **every** registered type. Iterate `.entrySet()`. `TaskType` exposes `getTypeId()` (ResourceLocation) and `getTypeForNBT()` (serialized string form). Instance → type via `Task.getType()` / `Reward.getType()`.

**Built-in task types (14):** `item, custom, xp, dimension, stat, kill, location, checkmark, advancement, observation, biome, structure, gamestage, fluid`. (`EnergyTask` exists but registers conditionally on TeamReborn Energy.)

**Built-in reward types (13):** `item, choice, all_table, random, loot, command, custom, xp, xp_levels, advancement, toast, gamestage, currency` (`currency` has `availableByDefault=false`).

### 3.6 Live editing API (the code path the bridge reuses)

Server entry point: `ServerQuestFile.getInstance()` (1.21) / `.INSTANCE` (1.20.1); holds `public final MinecraftServer server`. Guard with `ServerQuestFile.exists()` / `ifExists(...)`.

Object lifecycle methods (mostly on `BaseQuestFile`):
```java
QuestObjectBase create(long id, QuestObjectType type, long parent, Json5Object extraData)
long newID()
void refreshIDMap(); void clearCachedData(); void markDirty();   // markDirty only sets a flag
void saveNow();                                                   // immediate disk write
QuestObjectBase getBase(long id); QuestObject get(long id);
Chapter getChapterOrThrow(long id); Quest getQuest(long id); /* etc. */
void deleteObject(long id);   // ServerQuestFile override: deletes, refreshes, markDirty, self-broadcasts DeleteObjectResponseMessage
```

`create(...)` maps `QuestObjectType` → concrete class. Conventions: top-level objects use `parent == 1L`; for a CHAPTER the chapter group is selected via `extraData["group"]` (defaults to `0L`); for a TASK the parent is the quest id and `extraData["type"]` is the task type id; for a REWARD the parent is the quest id and `extraData["type"]` is the reward type id. New objects attach to their parent in their constructors.

**Canonical server-side sequences** (verbatim from the packet handlers, minus the player/`canEdit` check — the bridge supplies the `MinecraftServer` directly and passes a `null` creator so no client is forced to open an edit screen):

*Create:*
```java
var f = ServerQuestFile.getInstance();
var o = f.create(f.newID(), type, parent, extraJson);
o.readData(propertiesJson, server.registryAccess());
o.onCreated();
f.refreshIDMap(); f.clearCachedData(); f.markDirty();
f.getTranslationManager().processInitialTranslation(extraJson, o);
Server2PlayNetworking.sendToAllPlayers(server, CreateObjectResponseMessage.create(o, extra, null));
```

*Edit:*
```java
var f = ServerQuestFile.getInstance();
var o = f.getBase(id);
o.readData(mergedPropertiesJson, server.registryAccess());
f.clearCachedData(); f.markDirty();
Server2PlayNetworking.sendToAllPlayers(server, new EditObjectResponseMessage(o));
o.editedFromGUIOnServer();
```

*Delete:* `ServerQuestFile.getInstance().deleteObject(id);` (self-broadcasts).

The broadcast `*ResponseMessage` is what hot-syncs every connected client. Property edits flow through `readData`/`writeData`; the editor UI's field metadata comes from `fillConfigGroup(EditableConfigGroup)`.

### 3.7 Permissions

`NetUtils.canEdit(player)` checks per-player runtime `TeamData.getCanEdit(player)` (default false), toggled by the permission-gated `/ftbquests editing_mode` command. Authorization to *enable* edit mode = single-player, OR `Permissions.COMMANDS_GAMEMASTER` (op/gamemaster), OR the `ftbquests.editor` permission node. **This check is player-centric and cannot be satisfied by a non-player caller.** Therefore the bridge bypasses `canEdit` entirely and enforces its own auth at the HTTP layer (see §8).

### 3.8 Threading

Packet `handle(...)` methods run on the **server main thread**, and the quest-edit APIs assume it (no internal locking; `saveNow()` does synchronous disk I/O). The bridge's HTTP handler threads **must** marshal all quest work onto the main thread via `server.execute(Runnable)` / `server.submit(Callable)`.

---

## 4. Architecture decision

**Chosen: Architecture A** — the mod exposes a localhost HTTP+JSON "bridge"; the companion is a Claude Code plugin bundling a thin MCP server (MCP→HTTP adapter) + a domain skill.

Rationale: it decouples the slow-moving in-JVM bridge from the fast-iterating MCP/skill layer. Tool schemas, descriptions, and skill prose — where most refinement happens — change without recompiling a three-loader mod or restarting the server. HTTP is curl-debuggable; local auth is a shared token file. The alternatives (B: mod implements MCP natively in Java; C: no mod, edit SNBT on disk + RCON reload) were rejected — B carries a heavy multi-loader protocol-maintenance tax and couples tool changes to mod releases; C cannot meet the live-sync, live-registry, or per-type-schema requirements.

### 4.1 High-level diagram

```
┌────────────────────────────────────────────┐        ┌──────────────────────────────┐
│ Claude Code (on the server host, via SSH)   │        │ Minecraft Dedicated Server JVM│
│                                              │        │                              │
│  Agent ──▶ skill: ftbquests                  │        │  ftbquests-bridge mod        │
│        └─▶ MCP server (stdio, Node/TS) ──────┼─HTTP──▶│   HttpServer @127.0.0.1:PORT │
│              (MCP tools → HTTP, Bearer token)│  JSON  │     │ auth → server.submit()   │
│                                              │        │     ▼                        │
└────────────────────────────────────────────┘        │   ServerQuestFile + FTBQuests │
                                                        │     │ create/edit/delete       │
   creds file: config/ftbquests-bridge/runtime.json ◀───┤     ▼ broadcast *ResponseMsg  │
                                                        │   ──────────────────────────▶ all connected clients
                                                        └──────────────────────────────┘
```

---

## 5. Component 1 — the mod (`ftbquests-bridge`)

### 5.1 Module layout

```
ftbquests-bridge/
  common/    # ALL logic: HTTP server, router, JSON mapping, quest ops, registry enumeration, schema introspection
  neoforge/  # entrypoint + server-lifecycle glue
  fabric/    # entrypoint + server-lifecycle glue
```
Build: Architectury + Loom, Java 21, Mojang mappings. Deps: FTB Quests + FTB Library (`modImplementation`), Architectury API. The HTTP server uses the JDK's built-in `com.sun.net.httpserver.HttpServer` — **no third-party HTTP dependency.**

### 5.2 Lifecycle

Mirror `FTBQuestsEventHandler`: on the loader's "server started" event (after `ServerQuestFile` is loaded), construct and start the `HttpServer`; on "server stopped", stop it gracefully. All endpoints check `ServerQuestFile.exists()` and return **503** if quests aren't loaded yet.

On startup the mod writes a **runtime creds file** `config/ftbquests-bridge/runtime.json`:
```json
{ "port": 25599, "token": "<random-32-byte-hex>", "protocolVersion": 1, "boundAddress": "127.0.0.1" }
```
Co-located companion reads this for zero-config local auth. File permissions should be best-effort restricted to the owner.

### 5.3 Request pipeline

1. **Auth:** require `Authorization: Bearer <token>`; constant-time compare against the runtime token. Reject any non-loopback remote address with **403** unless `allowRemote` is true.
2. **Parse** JSON body (reject malformed → **400**).
3. **Marshal to server thread:** wrap the quest operation in `server.submit(callable)` → `CompletableFuture`; block the handler thread up to a configurable timeout (default 10s). Timeout → **504**. This serializes concurrent calls on the server tick and guarantees thread safety. (Pure registry reads may be served from a cache built on first access; still constructed on the main thread.)
4. **Serialize** the result to JSON and return.

### 5.4 Config — `config/ftbquests-bridge.json`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch. |
| `bindAddress` | `127.0.0.1` | Interface to bind. |
| `port` | `25599` | TCP port (0 = ephemeral, written to runtime.json). |
| `allowRemote` | `false` | Permit non-loopback peers (loud warning logged when true). |
| `token` | *(auto)* | Bearer token; auto-generated if absent. |
| `saveMode` | `immediate` | `immediate` = `saveNow()` after each mutation; `lazy` = `markDirty()` only (rely on FTB Quests' own save). |
| `requestTimeoutMs` | `10000` | Server-thread marshalling timeout. |

### 5.5 HTTP API contract

All bodies are JSON. IDs in paths are 16-char hex strings. Errors return `{ "error": { "code": <http>, "type": "...", "message": "..." } }`.

**Discovery / registries**

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | `{ ok, questsLoaded, ftbQuestsVersion, mcVersion, loader, protocolVersion }` |
| `GET` | `/registry/{kind}?query=&limit=&offset=` | Paginated registry search. `kind` ∈ `items, blocks, fluids, entity_types, biomes, dimensions, structures, advancements, stats, mob_effects, ...`. Each entry `{ id, displayName }`. |
| `GET` | `/task-types` | `[{ typeId, displayName, namespace }]` from `TaskTypes.TYPES`. |
| `GET` | `/reward-types` | `[{ typeId, displayName, namespace, availableByDefault }]` from `RewardTypes.TYPES`. |
| `GET` | `/task-types/{id}/schema` | Field schema for a task type (see §7). |
| `GET` | `/reward-types/{id}/schema` | Field schema for a reward type. |

**Read quest structure**

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/quests` | Map: `chapterGroups[] → chapters[] {id,title,filename,questCount}`, `rewardTables[] {id,title}`. |
| `GET` | `/quests/chapter/{id}` | Full chapter: quests (`id,title,x,y,shape,size,dependencies[],tasks[summary],rewards[summary]`), questLinks, images. |
| `GET` | `/quests/object/{id}` | Any object: `{ id, type, parent, children[], title, icon, data: <writeData json> }`. |
| `GET` | `/quests/search?q=&type=` | Search quests/objects by title/tag/type. |
| `GET` | `/reward-tables/{id}` | Reward table with `weightedRewards[]`. |

**Write**

| Method | Path | Body | Purpose |
|---|---|---|---|
| `POST` | `/quests/object` | `{ type, parent, properties{}, extra{} }` | Create. `extra` carries `group`/`type`/initial-translation fields. Returns `{ id, data }`. |
| `PATCH` | `/quests/object/{id}` | `{ properties{} }` | Partial edit (merge into current `writeData` json → `readData`). Returns `{ id, data }`. |
| `DELETE` | `/quests/object/{id}` | — | Delete (self-broadcasts). |
| `POST` | `/quests/object/{id}/move` | `{ x, y }` | Convenience reposition (wraps PATCH). |
| `POST` | `/quests/dependencies` | `{ questId, dependsOnId, add }` | Add/remove a dependency. |
| `POST` | `/save` | — | `saveNow()`. |

Every mutating endpoint returns after the broadcast has been queued so the caller knows clients are syncing.

---

## 6. Component 2 — the companion plugin (`ftbquests`)

A Claude Code plugin shipped as the install-time companion to the mod.

```
ftbquests-plugin/
  .claude-plugin/plugin.json        # plugin manifest
  .mcp.json                         # registers the MCP server (stdio)
  mcp/                              # the MCP server (TypeScript/Node, @modelcontextprotocol/sdk)
    src/ ... package.json
  skills/ftbquests/SKILL.md         # domain knowledge + workflows
  skills/ftbquests/references/*.md  # curated per-type schemas + examples
  commands/                         # optional: /ftbq-status, /ftbq-new-chapter
```

### 6.1 MCP server (thin adapter)

- **Language:** TypeScript/Node + official `@modelcontextprotocol/sdk`, `npx`-launchable. (Python + `uvx` is an acceptable alternative; Node chosen as the common Claude Code plugin idiom.)
- **Bridge discovery:** read `FTBQUESTS_SERVER_DIR` (locate `config/ftbquests-bridge/runtime.json`) or explicit `FTBQUESTS_BRIDGE_URL` + `FTBQUESTS_BRIDGE_TOKEN` env. Verify `/health` and `protocolVersion` compatibility on connect.
- **Tools (≈1:1 with endpoints, rich descriptions/JSON-schemas):** `ftbq_health`, `ftbq_search_registry(kind, query, limit, offset)`, `ftbq_list_task_types`, `ftbq_list_reward_types`, `ftbq_get_type_schema(kind, typeId)`, `ftbq_get_quest_map`, `ftbq_get_chapter(id)`, `ftbq_get_object(id)`, `ftbq_search_quests(q, type)`, `ftbq_create_object(type, parent, properties, extra)`, `ftbq_edit_object(id, properties)`, `ftbq_delete_object(id)`, `ftbq_set_dependency(questId, dependsOnId, add)`, `ftbq_save()`.
- Errors from the bridge are surfaced verbatim (type + message) so the agent can self-correct.

### 6.2 Skill (`ftbquests`)

Domain content so the agent uses the tools correctly:

- **Model & ids:** the hierarchy from §3.2; ids are 16-char hex; parent conventions (top-level `parent=1L`; chapter group via `extra.group`; task/reward parent = quest id with `extra.type` = the type id).
- **Composition workflow:** create chapter → create quests → add tasks/rewards → set dependencies → position on the grid (x/y).
- **Curated per-type reference** (`references/`): the 14 task types and 13 reward types with field meanings + worked examples (e.g., an `item` task with item id + count; a `checkmark` task; a `command` reward).
- **Workflows:** "build a progression chapter," "import an item list as collection tasks," "wire a dependency chain," "create and attach a reward table."
- **Operational reminders:** call `ftbq_save` after a batch; edits appear **live** to the connected admin; find item ids via `ftbq_search_registry`.
- **Safety:** `CommandReward` embeds commands that run on claim — confirm with the user before creating them.

### 6.3 Optional slash commands

`/ftbq-status` (calls `ftbq_health` + quick map), `/ftbq-new-chapter` (guided chapter scaffold). Minimal; not required for v1.

---

## 7. Schema strategy (type field discovery)

Layered so it is both pack-aware and robust:

1. **Primary (all types, incl. modded/KubeJS):** instantiate a throwaway object of the type against a fake parent quest (via `TaskType`/`RewardType` create helpers), call `writeData(json, provider)`, and expose the resulting key/value set as `defaults`, inferring field types from the Json5 value kinds. Requires no GUI.
2. **Enhancement (best-effort):** also invoke `fillConfigGroup(...)` for richer metadata (labels, enums, ranges). ⚠️ **Risk:** `EditableConfigGroup` is in FTB Library's `client.config` package and may be client-only — calling it on a dedicated server could throw/NoClassDefFound. **Mitigation:** attempt it guarded (try/catch around a reflective call); on failure, fall back to (1) plus curated docs. Resolve via a spike (see §11).
3. **Curated:** the skill ships stable, human-readable schemas + examples for the built-in 14 task / 13 reward types regardless.

---

## 8. Security

- **Loopback-only by default** (`bindAddress=127.0.0.1`); non-loopback peers rejected unless `allowRemote=true`.
- **Bearer token** auto-generated into the runtime creds file; constant-time comparison; companion reads the same file (co-located).
- **`allowRemote`** opt-in logs a prominent warning; remote use is the admin's explicit choice and still requires the token. (TLS/stronger auth for remote is future work — see §13.)
- **No arbitrary command execution.** The bridge has no "run command" endpoint. The only command surface is creating a `CommandReward` data object; the skill requires user confirmation before doing so.
- **Authorization model:** because FTB Quests' `canEdit` is player-centric and unusable by a non-player caller, trust is established at the HTTP layer (loopback + token). This intentionally bypasses the in-game per-player edit toggle.

---

## 9. Error handling

| Condition | HTTP | `error.type` |
|---|---|---|
| Quests not loaded yet | 503 | `quests_not_loaded` |
| Missing/invalid token | 401 | `unauthorized` |
| Non-loopback peer, remote disabled | 403 | `remote_disabled` |
| Unknown object id | 404 | `not_found` |
| Bad type/parent/malformed JSON, `readData` failure | 400 | `bad_request` (carries FTB Quests exception message) |
| Server-thread timeout | 504 | `server_busy` |
| Unexpected server-side exception | 500 | `internal` |

The MCP server maps these to clear tool errors and includes the FTB Quests message so the agent can correct its input.

---

## 10. Testing

- **Mod:**
  - Unit tests for JSON ↔ quest-object mapping (create/edit payload construction, partial-merge logic, registry/type serialization).
  - **Headless integration test:** boot a dedicated server with FTB Quests + the bridge (gametest harness or scripted server boot), hit each endpoint, assert objects are created/edited/deleted in `ServerQuestFile` and persisted to `config/ftbquests/quests/`.
  - **Manual hot-sync check:** live server + a connected client; confirm agent edits broadcast and render live.
- **MCP server:** unit/contract tests against a mock bridge (HTTP fixtures) covering each tool, error mapping, and `protocolVersion` negotiation.
- **Skill:** validated via example end-to-end transcripts (build a chapter; import items as tasks).

---

## 11. Risks & spikes (called out honestly)

1. **`fillConfigGroup` server-safety** (§7). `EditableConfigGroup` may be client-only. *Spike:* attempt a guarded server-side call on a dedicated server; if it fails, the primary `writeData`-defaults path + curated docs fully cover the requirement.
2. **Titles/descriptions via `TranslationManager`.** The 1.21 create handler routes initial text through `getTranslationManager().processInitialTranslation(extra, object)`, so titles may need to travel via `extra` rather than `properties`, and the *edit* path for text needs verification. *Spike:* confirm how title/subtitle/description round-trip through `readData`/`writeData` vs the translation manager, and expose the correct field location in the API + skill.
3. **Registry size.** Item/block registries are large (thousands, more in modpacks). Mandatory pagination + server-side `query` filtering; the MCP tools default to small `limit`s.

---

## 12. Versioning & compatibility

`/health` advertises an integer `protocolVersion`. The MCP server checks it on connect and refuses (with a clear message) on mismatch. This keeps the two artifacts loosely coupled but safe to release independently. The mod also reports `ftbQuestsVersion`, `mcVersion`, and `loader`.

---

## 13. Future work (post-v1)

- **1.20.1 / Forge branch** — NBT serialization (`CompoundTag`) + Architectury `SimpleNetworkManager`; `ServerQuestFile.INSTANCE` field instead of `getInstance()`. Same architecture, separate serialization adapter.
- **Player/team progress** management tools.
- **Secured remote access** (TLS, OAuth, audit log) beyond the loopback+token default.
- **Reward-table loot editing** beyond basic CRUD (weighting helpers).
- **Bulk/transactional edits** (apply many changes, single save/broadcast).

---

## Appendix A — built-in type quick reference

**Task types (14):** `item, custom, xp, dimension, stat, kill, location, checkmark, advancement, observation, biome, structure, gamestage, fluid` (+ conditional `energy`).

**Reward types (13):** `item, choice, all_table, random, loot, command, custom, xp, xp_levels, advancement, toast, gamestage, currency` (`currency.availableByDefault=false`).

## Appendix B — key API surface reused

```java
ServerQuestFile.getInstance() / .exists() / .ifExists(c)   // server entry (1.21)
.server                                                    // MinecraftServer
BaseQuestFile.create(long id, QuestObjectType, long parent, Json5Object extra)
BaseQuestFile.newID(); .getBase(long); .refreshIDMap(); .clearCachedData(); .markDirty()
ServerQuestFile.saveNow(); .deleteObject(long)             // delete self-broadcasts
QuestObjectBase.readData/writeData(Json5Object, HolderLookup.Provider)
QuestObjectBase.onCreated(); .editedFromGUIOnServer(); .fillConfigGroup(EditableConfigGroup)
TaskTypes.TYPES / RewardTypes.TYPES                        // Map<ResourceLocation, *Type>
CreateObjectResponseMessage.create(obj, extra, creatorOrNull)
new EditObjectResponseMessage(obj); new DeleteObjectResponseMessage(id)
Server2PlayNetworking.sendToAllPlayers(server, msg)        // hot-sync broadcast
server.submit(callable) / server.execute(runnable)         // marshal to main thread
```
