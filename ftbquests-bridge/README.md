# ftbquests-bridge — Minecraft 1.20.1 (Forge + Fabric)

The 1.20.1 build of the `ftbquests-bridge` mod: the loopback HTTP+JSON API that the
`ftbquests` Claude Code plugin talks to. Same protocol (v1) and same routes as the
1.21.1 bridge on the [`1.21.1` branch](https://github.com/west3436/ftbquests-mcp/tree/1.21.1/ftbquests-bridge);
only the FTB Quests backend is rewritten against the 1.20.1 API.

- **Minecraft 1.20.1 · Java 17**
- **Forge 47.4.1** or **Fabric Loader 0.16 + Fabric API**
- **FTB Quests 2001.4.11+** (2001.x line), FTB Library 2001.2.4, FTB Teams 2001.3.0,
  Architectury 9.2.14

1.20.1 port by **mmyddd**; original mod by **west3436**.

## Layout

An [Architectury](https://docs.architectury.dev/) multi-loader project with the same layout as
the 1.21.1 tree, so `git diff 1.21.1 1.20.1 -- ftbquests-bridge/common` shows only real
version differences, and fixes can be cherry-picked between the branches:

- `common/` — everything: HTTP server, router, auth, config, JSON helpers, the FTB Quests backend.
- `forge/` — the `@Mod` entry point and `mods.toml`.
- `fabric/` — the `ModInitializer` entry point and `fabric.mod.json`.

Each loader module only forwards server start/stop to `FtbQuestsBridge`; Forge-only and
Fabric-only APIs (config folder, mod versions, loader name) go through Architectury's `Platform`.

## Build

```bash
./gradlew build          # -> forge/build/libs/ftbquests-bridge-forge-<version>.jar
                         #    fabric/build/libs/ftbquests-bridge-fabric-<version>.jar
./gradlew :common:test   # JUnit 5; no Minecraft bootstrap required
```

Gradle 9.6.1 must run on a **JDK 21** (Architectury Loom 1.17 requires it); the mod itself is
compiled for Java 17. The JDK path is deliberately **not** pinned in `gradle.properties`; point
`JAVA_HOME` at a JDK 21 or pass `-Dorg.gradle.java.home=/path/to/jdk21`.

## Run a dev server

```bash
./gradlew :forge:runServer --args=nogui     # world in forge/run/
./gradlew :fabric:runServer --args=nogui    # world in fabric/run/
```

The first run stops at the EULA prompt: create `<loader>/run/eula.txt` containing `eula=true`
and start it again (`run/` is git-ignored). Once the server has started, the bridge writes
`config/ftbquests-bridge.json` (settings) and `config/ftbquests-bridge/runtime.json` (bound port
and bearer token) under the run directory and listens on `127.0.0.1:25599`. Ping it with the
token from `runtime.json`:

```bash
TOKEN=$(grep -o '"token": *"[^"]*"' forge/run/config/ftbquests-bridge/runtime.json | cut -d'"' -f4)
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25599/health
# {"ok":true,"questsLoaded":true,"protocolVersion":1,"loader":"forge",
#  "minecraftVersion":"1.20.1","ftbQuestsVersion":"2001.4.11"}
```

The FTB jars are on the dev runtime classpath only; they are **not** bundled into the produced
jars, which declare FTB Quests as a required dependency instead.

## What differs from the 1.21.1 tree

The 1.20.1 tree branched from the 1.21.1 bridge at v1.1.0. The HTTP server, router, auth, config,
JSON helpers and `QuestBackend` interface are Minecraft-free and match that version. Later
1.21.1-only additions (quest-book screenshots, `readOnly`, port fallback) are not ported yet.

| File | 1.21.1 | 1.20.1 |
|---|---|---|
| `FtbQuestsBackend` | `holderLookup()`, 2-arg `writeData`, translation routing, `deleteObjects(List)` | single-arg `writeData(CompoundTag)`, `BaseS2CMessage.sendToAll(server)`, `deleteObject(long)` per id |
| `TranslationFields` | splits title/subtitle/description into `TranslationManager` writes | replaced by `TextFieldShapes`, which only coerces the per-type NBT text shapes (a chapter subtitle is a string list, a quest's is a string); titles need no translation routing because 2001.x stores them as plain NBT |
| `TypeSchemas` | `ResourceLocation.parse` | `new ResourceLocation(id)`; bare ids are normalized to `ftbquests:` on **both** trees, it is not a 2001.x quirk |
| `RegistryReader` | `AdvancementHolder.id()` | `Advancement.getId()` over `getAllAdvancements()` (1.20.1 keeps the id on the advancement; `AdvancementHolder` arrives in 1.20.2), and a nullable `getDisplay()` |
| `QuestTree` | `Path`-based `chapterFilename` | `String`-based (2001.x `getPath()` returns `Optional<String>`) |
| `MinecraftServerExecutor` | awaited `future.get`, no cancellation | takes the `MinecraftServer` explicitly and interrupts the queued task on timeout (`future.cancel(true)`) — a 504 means "outcome unknown", not "nothing changed" |
| `FtbQuestsBridge` | starts the API on server start | the same, plus a guard against a duplicate server-start event and separate handling for an unreadable config, a busy port, and an unwritable `runtime.json` |
| Loader modules | `neoforge/` + `fabric/` | `forge/` + `fabric/` |

Full-file line references for the 2001.4.11 API quirks are in the code comments.

## License

MIT — see [../LICENSE](../LICENSE).
