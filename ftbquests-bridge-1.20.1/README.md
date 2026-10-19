# ftbquests-bridge — Minecraft 1.20.1 / Forge

Forge port of the `ftbquests-bridge` mod: the loopback HTTP+JSON API that the
`ftbquests` Claude Code plugin talks to. Same protocol (v1) and same routes as the
1.21.1 Architectury tree in `../ftbquests-bridge/`; only the FTB Quests backend is
rewritten against the 1.20.1 API.

- **Minecraft 1.20.1 · Forge 47.4.1 · Java 17**
- **FTB Quests 2001.4.11** (2001.x line), FTB Library 2001.2.4, FTB Teams 2001.3.0,
  Architectury 9.2.14

Port by **mmyddd**; original mod by **west3436**.

## Build

```bash
./gradlew build          # -> build/libs/ftbquests-bridge-forge-1.2.0.jar
./gradlew test           # JUnit 5; no Minecraft bootstrap required
```

Gradle 8.14 on a JDK 17 (`JAVA_HOME`), which is also what CI uses. The JDK path is
deliberately **not** pinned in `gradle.properties`; if yours is not on `PATH`
or `JAVA_HOME`, pass `-Dorg.gradle.java.home=/path/to/jdk17` on the command line.

## Run a dev server

```bash
./gradlew runServer      # 1.20.1 + Forge + FTB Quests, world in run/server/
```

The first run stops at the EULA prompt: create `run/server/eula.txt` containing
`eula=true` and start it again (`run/` is git-ignored, so it is never committed).
On `ServerStartedEvent` the
bridge writes `run/server/config/ftbquests-bridge.json` (settings) and
`run/server/config/ftbquests-bridge/runtime.json` (bound port + bearer token), and
listens on `127.0.0.1:25599`. Two log lines are relevant: `HTTP API on
<addr>:<port> (allowRemote=<bool>)`, and — when `allowRemote` is turned on — a
warning that non-loopback clients may now connect with the token. Ping it with the
token from `runtime.json`:

```bash
TOKEN=$(grep -o '"token": *"[^"]*"' run/server/config/ftbquests-bridge/runtime.json | cut -d'"' -f4)
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25599/health
# {"ok":true,"questsLoaded":true,"protocolVersion":1,"loader":"forge",
#  "minecraftVersion":"1.20.1","ftbQuestsVersion":"2001.4.11"}
```

The FTB jars are `modRuntimeOnly` for that run only — they are **not** bundled into
the produced jar, which keeps a normal `modCompileOnly` dependency on FTB Quests.

## What changed from the 1.21.1 tree

Everything outside `backend/ftbq/` is identical to
`../ftbquests-bridge/common/src/main/java/com/ftbqbridge/` — the HTTP server, router,
auth, config, JSON helpers and `QuestBackend` interface are Minecraft-free.

| File | 1.21.1 | 1.20.1 |
|---|---|---|
| `FtbQuestsBackend` | `holderLookup()`, 2-arg `writeData`, translation routing, `deleteObjects(List)` | single-arg `writeData(CompoundTag)`, `BaseS2CMessage.sendToAll(server)`, `deleteObject(long)` per id |
| `TranslationFields` | splits title/subtitle/description into `TranslationManager` writes | replaced by `TextFieldShapes`, which only coerces the per-type NBT text shapes (a chapter subtitle is a string list, a quest's is a string); titles need no translation routing because 2001.x stores them as plain NBT |
| `TypeSchemas` | `ResourceLocation.parse` | `new ResourceLocation(id)`; bare ids are normalized to `ftbquests:` on **both** trees, it is not a 2001.x quirk |
| `RegistryReader` | `AdvancementHolder.id()` | `Advancement.getId()` over `getAllAdvancements()` (1.20.1 keeps the id on the advancement; `AdvancementHolder` arrives in 1.20.2), and a nullable `getDisplay()` |
| `QuestTree` | `Path`-based `chapterFilename` | `String`-based (2001.x `getPath()` returns `Optional<String>`) |
| `MinecraftServerExecutor` | awaited `future.get`, no cancellation | rewritten: takes the `MinecraftServer` explicitly and interrupts the queued task on timeout (`future.cancel(true)`) — a 504 means "outcome unknown", not "nothing changed" |
| Entrypoint | Architectury `@ExpectPlatform` dispatch with a Fabric module and a NeoForge module | rewritten as a single `FtbQuestsBridgeForge` `@Mod` class listening on the Forge event bus |

Behaviour carried over unchanged: the bridge still binds loopback by default, still
writes `runtime.json`, and still warns when `allowRemote` exposes it to other hosts.

Full-file line references for the 2001.4.11 API quirks are in the code comments.

## License

MIT — see [../LICENSE](../LICENSE).
