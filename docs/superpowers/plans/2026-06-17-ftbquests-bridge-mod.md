# FTB Quests Bridge Mod — Implementation Plan (Plan 1 of 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `ftbquests-bridge`, an Architectury multi-loader mod (MC 1.21.1, NeoForge + Fabric) that runs a loopback HTTP+JSON API inside the Minecraft server JVM to read FTB Quests registries/structure and create/edit/delete quest objects, with live client sync.

**Architecture:** A pure, Minecraft-free core (HTTP server, router, auth, JSON, handlers) depends on a `QuestBackend` interface that speaks only Gson + plain Java types. The HTTP layer is fully unit-testable against an in-memory `FakeQuestBackend`. The single Minecraft-coupled class, `FtbQuestsBackend`, implements `QuestBackend` by marshalling every operation onto the server main thread and reusing FTB Quests' own `ServerQuestFile` create/edit/delete code paths (verified in the spec, §3.6). It is verified by booting a dev server and curling endpoints.

**Tech Stack:** Java 21, Architectury Loom, Minecraft 1.21.1, NeoForge 21.1.213 + Fabric Loader 0.15.11, FTB Quests `2101.1.27` + FTB Library `2101.1.31`, JDK `com.sun.net.httpserver.HttpServer`, Gson (bundled with Minecraft), JUnit 5.

## Global Constraints

- Java **21**; Minecraft **1.21.1**; loaders **NeoForge + Fabric** via Architectury (common module holds all logic).
- Mod id: **`ftbqbridge`**. Java package root: **`com.ftbqbridge`**. Loader subpackages: `com.ftbqbridge.neoforge`, `com.ftbqbridge.fabric`.
- HTTP server uses **JDK `com.sun.net.httpserver.HttpServer` only** — no third-party HTTP/web dependency.
- JSON uses **Gson** (`com.google.gson.*`), which Minecraft already provides on the classpath — do **not** add a separate Gson dependency.
- API protocol version constant: **`PROTOCOL_VERSION = 1`** (returned by `/health`, written into the runtime creds file).
- Default config: `bindAddress=127.0.0.1`, `port=25599`, `allowRemote=false`, `saveMode=immediate`, `requestTimeoutMs=10000`. Token auto-generated (32 random bytes, hex) when blank.
- Bind loopback-only by default; reject non-loopback peers with HTTP 403 unless `allowRemote=true`. All requests require `Authorization: Bearer <token>`.
- Quest definitions live in `config/ftbquests/quests/`. Runtime creds file: `config/ftbquests-bridge/runtime.json`. User config file: `config/ftbquests-bridge.json`.
- **All quest/registry operations run on the server main thread** (FTB Quests has no internal locking). The HTTP thread must marshal via `MinecraftServer.submit(...)`.
- Object ids are 16-char uppercase hex strings (FTB Quests `getCodeString`). The bridge accepts/returns ids as those hex strings; conversion to/from `long` happens only inside `FtbQuestsBackend`.

---

## File Structure

```
ftbquests-bridge/
  settings.gradle  build.gradle  gradle.properties  gradle/wrapper/...
  common/
    build.gradle
    src/main/java/com/ftbqbridge/
      FtbQuestsBridge.java              # common init/shutdown; holds Router + HTTP server
      Protocol.java                     # PROTOCOL_VERSION + mod id constants
      config/BridgeConfig.java          # user config POJO + load/store (pure)
      config/RuntimeCredentials.java    # write runtime.json (pure)
      http/Router.java                  # method+path matching, path params (pure)
      http/RequestContext.java          # parsed request (pure)
      http/JsonResponse.java            # status + Gson body (pure)
      http/Auth.java                    # bearer + loopback evaluation (pure)
      http/BridgeHttpServer.java        # JDK HttpServer adapter (pure: no MC)
      http/BridgeHandlers.java          # registers routes against QuestBackend (pure)
      json/JsonMerge.java               # shallow merge for PATCH (pure)
      json/Json.java                    # shared Gson instance (pure)
      backend/QuestBackend.java         # the contract (pure: Gson + plain types)
      backend/ApiException.java         # typed error -> HTTP status (pure)
      backend/ServerTaskExecutor.java   # run Callable on server thread (interface, pure)
      backend/ftbq/FtbQuestsBackend.java        # QuestBackend impl (Minecraft-coupled)
      backend/ftbq/RegistryReader.java          # registry/type enumeration (Minecraft-coupled)
      backend/ftbq/QuestSerializer.java         # QuestObjectBase <-> Gson (Minecraft-coupled)
      backend/ftbq/MinecraftServerExecutor.java # ServerTaskExecutor impl (Minecraft-coupled)
    src/test/java/com/ftbqbridge/
      config/BridgeConfigTest.java
      config/RuntimeCredentialsTest.java
      http/RouterTest.java
      http/AuthTest.java
      http/BridgeHttpServerTest.java
      json/JsonMergeTest.java
      backend/ApiExceptionTest.java
      backend/FakeQuestBackend.java     # in-memory test double (test sourceset)
  neoforge/
    build.gradle
    src/main/java/com/ftbqbridge/neoforge/FtbQuestsBridgeNeoForge.java
    src/main/resources/META-INF/neoforge.mods.toml
  fabric/
    build.gradle
    src/main/java/com/ftbqbridge/fabric/FtbQuestsBridgeFabric.java
    src/main/resources/fabric.mod.json
```

Tasks 2–8 touch only the **pure** files (no Minecraft imports) and run as fast JUnit tests. Tasks 9–12 build the Minecraft-coupled `backend/ftbq/` classes, verified by a dev-server boot. Task 13 wires loader lifecycle.

---

### Task 1: Multi-loader Architectury skeleton that builds and loads

**Files:**
- Create: `settings.gradle`, `build.gradle`, `gradle.properties`, `common/build.gradle`, `neoforge/build.gradle`, `fabric/build.gradle`
- Create: `common/src/main/java/com/ftbqbridge/Protocol.java`
- Create: `common/src/main/java/com/ftbqbridge/FtbQuestsBridge.java`
- Create: `neoforge/src/main/java/com/ftbqbridge/neoforge/FtbQuestsBridgeNeoForge.java`, `neoforge/src/main/resources/META-INF/neoforge.mods.toml`
- Create: `fabric/src/main/java/com/ftbqbridge/fabric/FtbQuestsBridgeFabric.java`, `fabric/src/main/resources/fabric.mod.json`

**Interfaces:**
- Produces: `com.ftbqbridge.FtbQuestsBridge.init()` / `.shutdown(...)` (no-op stubs in this task), `com.ftbqbridge.Protocol.{MOD_ID, PROTOCOL_VERSION}`.

> **Build-scaffold note (read first):** Generate the skeleton from the official Architectury example template for **1.21.1 (NeoForge + Fabric)** rather than hand-writing the shadow/remap config: clone `https://github.com/architectury/architectury-templates` (or the `architectury-example-mod` 1.21.1 branch), then apply the edits below. This guarantees the loom/shadow wiring matches current plugin versions. Verification for this task is a successful build + both jars loading — not a unit test.

- [ ] **Step 1: Generate skeleton and set identifiers**

Create `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx3G
org.gradle.daemon=false

minecraft_version=1.21.1
enabled_platforms=fabric,neoforge

archives_base_name=ftbquests-bridge
mod_version=1.0.0
maven_group=com.ftbqbridge

architectury_api_version=13.0.8
fabric_loader_version=0.15.11
fabric_api_version=0.102.1+1.21.1
neoforge_version=21.1.213
```

Create `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        maven { url "https://maven.fabricmc.net/" }
        maven { url "https://maven.architectury.dev/" }
        maven { url "https://maven.neoforged.net/releases/" }
        gradlePluginPortal()
    }
}
include("common"); include("fabric"); include("neoforge")
rootProject.name = "ftbquests-bridge"
```

Create root `build.gradle`:

```groovy
plugins {
    id "architectury-plugin" version "3.4-SNAPSHOT"
    id "dev.architectury.loom" version "1.10-SNAPSHOT" apply false
}
architectury { minecraft = rootProject.minecraft_version }
allprojects { group = rootProject.maven_group; version = rootProject.mod_version }
subprojects {
    apply plugin: "dev.architectury.loom"
    apply plugin: "architectury-plugin"
    base { archivesName = "${rootProject.archives_base_name}-${project.name}" }
    repositories {
        maven { url "https://maven.architectury.dev/" }
        maven { url "https://maven.neoforged.net/releases/" }
        maven { url "https://maven.fabricmc.net/" }
    }
    dependencies {
        minecraft "com.mojang:minecraft:${rootProject.minecraft_version}"
        mappings loom.officialMojangMappings()
    }
    java { withSourcesJar(); sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    tasks.withType(JavaCompile).configureEach { options.encoding = "UTF-8"; options.release = 21 }
}
```

Keep the template's `common/build.gradle`, `fabric/build.gradle`, `neoforge/build.gradle` shadow/remap blocks. In `common/build.gradle` ensure the architectury common dependency and JUnit test config are present (FTB Quests deps are added later in Task 9):

```groovy
// common/build.gradle (append)
dependencies {
    modImplementation "dev.architectury:architectury:${rootProject.architectury_api_version}"
    testImplementation "org.junit.jupiter:junit-jupiter:5.10.2"
    testRuntimeOnly "org.junit.platform:junit-platform-launcher"
}
test { useJUnitPlatform() }
```

- [ ] **Step 2: Write `Protocol.java`**

```java
package com.ftbqbridge;

public final class Protocol {
    public static final String MOD_ID = "ftbqbridge";
    public static final int PROTOCOL_VERSION = 1;
    private Protocol() {}
}
```

- [ ] **Step 3: Write common init stub `FtbQuestsBridge.java`**

```java
package com.ftbqbridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FtbQuestsBridge {
    public static final Logger LOG = LoggerFactory.getLogger("ftbquests-bridge");
    private FtbQuestsBridge() {}

    /** Called by each loader once the common mod loads. Wiring added in Task 13. */
    public static void init() {
        LOG.info("[ftbquests-bridge] common init (protocol v{})", Protocol.PROTOCOL_VERSION);
    }
}
```

- [ ] **Step 4: Write loader entrypoints**

`neoforge/.../FtbQuestsBridgeNeoForge.java`:

```java
package com.ftbqbridge.neoforge;

import com.ftbqbridge.FtbQuestsBridge;
import com.ftbqbridge.Protocol;
import net.neoforged.fml.common.Mod;

@Mod(Protocol.MOD_ID)
public final class FtbQuestsBridgeNeoForge {
    public FtbQuestsBridgeNeoForge() { FtbQuestsBridge.init(); }
}
```

`fabric/.../FtbQuestsBridgeFabric.java`:

```java
package com.ftbqbridge.fabric;

import com.ftbqbridge.FtbQuestsBridge;
import net.fabricmc.api.ModInitializer;

public final class FtbQuestsBridgeFabric implements ModInitializer {
    @Override public void onInitialize() { FtbQuestsBridge.init(); }
}
```

Create `fabric/src/main/resources/fabric.mod.json` (id `ftbqbridge`, entrypoint `com.ftbqbridge.fabric.FtbQuestsBridgeFabric`, depends minecraft `~1.21.1`, java `>=21`) and `neoforge/src/main/resources/META-INF/neoforge.mods.toml` (modId `ftbqbridge`, loader `javafml`, neoforge version range). Use the template's files as the shape and set those fields.

- [ ] **Step 5: Build both loaders**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; jars at `neoforge/build/libs/ftbquests-bridge-neoforge-1.0.0.jar` and `fabric/build/libs/ftbquests-bridge-fabric-1.0.0.jar`.

- [ ] **Step 6: Smoke-load (manual, optional but recommended)**

Run: `./gradlew :neoforge:runServer` (and later `:fabric:runServer`). Expected: server log contains `[ftbquests-bridge] common init (protocol v1)`. Stop with `stop`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: architectury multi-loader skeleton (1.21.1 neoforge+fabric)"
```

---

### Task 2: `BridgeConfig` — user config load/store

**Files:**
- Create: `common/src/main/java/com/ftbqbridge/config/BridgeConfig.java`
- Test: `common/src/test/java/com/ftbqbridge/config/BridgeConfigTest.java`

**Interfaces:**
- Produces: `BridgeConfig` (public mutable fields `enabled, bindAddress, port, allowRemote, token, saveMode, requestTimeoutMs`); `static BridgeConfig load(Path file)` (creates file with defaults + generated token if missing; fills blank token on load); `void store(Path file)`.

- [ ] **Step 1: Write the failing test**

```java
package com.ftbqbridge.config;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class BridgeConfigTest {
    @Test void createsDefaultsAndTokenWhenMissing(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path f = dir.resolve("ftbquests-bridge.json");
        BridgeConfig c = BridgeConfig.load(f);
        assertTrue(Files.exists(f));
        assertEquals("127.0.0.1", c.bindAddress);
        assertEquals(25599, c.port);
        assertFalse(c.allowRemote);
        assertEquals("immediate", c.saveMode);
        assertEquals(10000L, c.requestTimeoutMs);
        assertNotNull(c.token);
        assertEquals(64, c.token.length()); // 32 bytes hex
    }

    @Test void roundTripsAndKeepsToken(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path f = dir.resolve("ftbquests-bridge.json");
        BridgeConfig c1 = BridgeConfig.load(f);
        String token = c1.token;
        BridgeConfig c2 = BridgeConfig.load(f);
        assertEquals(token, c2.token, "token must persist across loads");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:test --tests "com.ftbqbridge.config.BridgeConfigTest"`
Expected: FAIL — `BridgeConfig` does not exist / cannot resolve.

- [ ] **Step 3: Write minimal implementation**

```java
package com.ftbqbridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.security.SecureRandom;

public final class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;
    public String bindAddress = "127.0.0.1";
    public int port = 25599;
    public boolean allowRemote = false;
    public String token = "";
    public String saveMode = "immediate";
    public long requestTimeoutMs = 10000L;

    public static BridgeConfig load(Path file) throws java.io.IOException {
        BridgeConfig cfg;
        if (Files.exists(file)) {
            cfg = GSON.fromJson(Files.readString(file), BridgeConfig.class);
            if (cfg == null) cfg = new BridgeConfig();
        } else {
            cfg = new BridgeConfig();
        }
        if (cfg.token == null || cfg.token.isBlank()) cfg.token = newToken();
        cfg.store(file);
        return cfg;
    }

    public void store(Path file) throws java.io.IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this));
    }

    private static String newToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:test --tests "com.ftbqbridge.config.BridgeConfigTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/ftbqbridge/config/BridgeConfig.java common/src/test/java/com/ftbqbridge/config/BridgeConfigTest.java
git commit -m "feat: BridgeConfig load/store with auto token"
```

---

### Task 3: `RuntimeCredentials` — write `runtime.json`

**Files:**
- Create: `common/src/main/java/com/ftbqbridge/config/RuntimeCredentials.java`
- Test: `common/src/test/java/com/ftbqbridge/config/RuntimeCredentialsTest.java`

**Interfaces:**
- Produces: `static void write(Path file, int port, String token, int protocolVersion, String boundAddress)` — writes `{port, token, protocolVersion, boundAddress}` JSON, creating parent dirs.

- [ ] **Step 1: Write the failing test**

```java
package com.ftbqbridge.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCredentialsTest {
    @Test void writesAllFields(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("ftbquests-bridge/runtime.json");
        RuntimeCredentials.write(f, 25599, "abc123", 1, "127.0.0.1");
        JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        assertEquals(25599, o.get("port").getAsInt());
        assertEquals("abc123", o.get("token").getAsString());
        assertEquals(1, o.get("protocolVersion").getAsInt());
        assertEquals("127.0.0.1", o.get("boundAddress").getAsString());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:test --tests "com.ftbqbridge.config.RuntimeCredentialsTest"`
Expected: FAIL — `RuntimeCredentials` not found.

- [ ] **Step 3: Write minimal implementation**

```java
package com.ftbqbridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.file.*;

public final class RuntimeCredentials {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private RuntimeCredentials() {}

    public static void write(Path file, int port, String token, int protocolVersion, String boundAddress)
            throws java.io.IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        JsonObject o = new JsonObject();
        o.addProperty("port", port);
        o.addProperty("token", token);
        o.addProperty("protocolVersion", protocolVersion);
        o.addProperty("boundAddress", boundAddress);
        Files.writeString(file, GSON.toJson(o));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:test --tests "com.ftbqbridge.config.RuntimeCredentialsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/ftbqbridge/config/RuntimeCredentials.java common/src/test/java/com/ftbqbridge/config/RuntimeCredentialsTest.java
git commit -m "feat: write runtime credentials file"
```

---

### Task 4: `Auth` — bearer token + loopback evaluation

**Files:**
- Create: `common/src/main/java/com/ftbqbridge/http/Auth.java`
- Test: `common/src/test/java/com/ftbqbridge/http/AuthTest.java`

**Interfaces:**
- Produces: enum `Auth.Result { OK, UNAUTHORIZED, REMOTE_DISABLED }`; `static Result evaluate(boolean loopback, String authHeader, String expectedToken, boolean allowRemote)`.

- [ ] **Step 1: Write the failing test**

```java
package com.ftbqbridge.http;

import org.junit.jupiter.api.Test;
import static com.ftbqbridge.http.Auth.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthTest {
    @Test void okOnLoopbackWithGoodToken() {
        assertEquals(OK, Auth.evaluate(true, "Bearer secret", "secret", false));
    }
    @Test void unauthorizedOnBadToken() {
        assertEquals(UNAUTHORIZED, Auth.evaluate(true, "Bearer nope", "secret", false));
    }
    @Test void unauthorizedOnMissingHeader() {
        assertEquals(UNAUTHORIZED, Auth.evaluate(true, null, "secret", false));
    }
    @Test void remoteDisabledBlocksNonLoopbackEvenWithGoodToken() {
        assertEquals(REMOTE_DISABLED, Auth.evaluate(false, "Bearer secret", "secret", false));
    }
    @Test void remoteAllowedWithGoodToken() {
        assertEquals(OK, Auth.evaluate(false, "Bearer secret", "secret", true));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:test --tests "com.ftbqbridge.http.AuthTest"`
Expected: FAIL — `Auth` not found.

- [ ] **Step 3: Write minimal implementation**

```java
package com.ftbqbridge.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Auth {
    public enum Result { OK, UNAUTHORIZED, REMOTE_DISABLED }
    private Auth() {}

    public static Result evaluate(boolean loopback, String authHeader, String expectedToken, boolean allowRemote) {
        if (!loopback && !allowRemote) return Result.REMOTE_DISABLED;
        String presented = null;
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            presented = authHeader.substring(7).trim();
        }
        if (presented == null || !constantTimeEquals(presented, expectedToken)) return Result.UNAUTHORIZED;
        return Result.OK;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:test --tests "com.ftbqbridge.http.AuthTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/ftbqbridge/http/Auth.java common/src/test/java/com/ftbqbridge/http/AuthTest.java
git commit -m "feat: bearer+loopback auth evaluation"
```

---

### Task 5: `JsonMerge` + `Json` — shallow merge for PATCH

**Files:**
- Create: `common/src/main/java/com/ftbqbridge/json/Json.java`
- Create: `common/src/main/java/com/ftbqbridge/json/JsonMerge.java`
- Test: `common/src/test/java/com/ftbqbridge/json/JsonMergeTest.java`

**Interfaces:**
- Produces: `Json.GSON` (shared `com.google.gson.Gson`); `JsonMerge.shallowMerge(JsonObject base, JsonObject patch)` → new `JsonObject` (patch keys overwrite base keys; base is not mutated).

- [ ] **Step 1: Write the failing test**

```java
package com.ftbqbridge.json;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonMergeTest {
    @Test void patchOverridesAndAdds() {
        JsonObject base = new JsonObject();
        base.addProperty("title", "Old"); base.addProperty("x", 1);
        JsonObject patch = new JsonObject();
        patch.addProperty("title", "New"); patch.addProperty("y", 2);
        JsonObject out = JsonMerge.shallowMerge(base, patch);
        assertEquals("New", out.get("title").getAsString());
        assertEquals(1, out.get("x").getAsInt());
        assertEquals(2, out.get("y").getAsInt());
        assertEquals("Old", base.get("title").getAsString(), "base must be unmodified");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:test --tests "com.ftbqbridge.json.JsonMergeTest"`
Expected: FAIL — `JsonMerge` not found.

- [ ] **Step 3: Write minimal implementation**

```java
// Json.java
package com.ftbqbridge.json;
import com.google.gson.Gson;
public final class Json { public static final Gson GSON = new Gson(); private Json() {} }
```

```java
// JsonMerge.java
package com.ftbqbridge.json;

import com.google.gson.JsonObject;
import java.util.Map;

public final class JsonMerge {
    private JsonMerge() {}
    public static JsonObject shallowMerge(JsonObject base, JsonObject patch) {
        JsonObject out = base.deepCopy();
        for (Map.Entry<String, com.google.gson.JsonElement> e : patch.entrySet()) {
            out.add(e.getKey(), e.getValue());
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:test --tests "com.ftbqbridge.json.JsonMergeTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/ftbqbridge/json/ common/src/test/java/com/ftbqbridge/json/JsonMergeTest.java
git commit -m "feat: shared Gson + shallow JSON merge for PATCH"
```

---

### Task 6: `Router` + `RequestContext` + `JsonResponse`

**Files:**
- Create: `common/src/main/java/com/ftbqbridge/http/RequestContext.java`
- Create: `common/src/main/java/com/ftbqbridge/http/JsonResponse.java`
- Create: `common/src/main/java/com/ftbqbridge/http/Router.java`
- Test: `common/src/test/java/com/ftbqbridge/http/RouterTest.java`

**Interfaces:**
- Produces:
  - `RequestContext` record: `String method, String path, Map<String,String> pathParams, Map<String,String> query, JsonObject body, boolean loopback, String authHeader`. Helper `String param(String name)`, `String queryOr(String name, String def)`.
  - `JsonResponse` record: `int status, JsonElement body`; statics `ok(JsonElement)`, `of(int, JsonElement)`.
  - `Router`: functional `Route { JsonResponse handle(RequestContext) }`; `Router add(String method, String pattern, Route)`; `Match { Route route; Map<String,String> params }`; `Match match(String method, String path)` (null if none). Patterns use `{name}` segments, e.g. `/quests/chapter/{id}`.

- [ ] **Step 1: Write the failing test**

```java
package com.ftbqbridge.http;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RouterTest {
    @Test void matchesStaticAndExtractsParams() {
        Router r = new Router();
        r.add("GET", "/health", ctx -> JsonResponse.ok(new JsonObject()));
        r.add("GET", "/quests/chapter/{id}", ctx -> JsonResponse.ok(new JsonObject()));

        assertNotNull(r.match("GET", "/health"));
        Router.Match m = r.match("GET", "/quests/chapter/00FF");
        assertNotNull(m);
        assertEquals("00FF", m.params().get("id"));
    }

    @Test void methodAndLengthMismatchReturnNull() {
        Router r = new Router();
        r.add("GET", "/quests/chapter/{id}", ctx -> JsonResponse.ok(new JsonObject()));
        assertNull(r.match("POST", "/quests/chapter/00FF"), "method mismatch");
        assertNull(r.match("GET", "/quests/chapter/00FF/extra"), "segment count mismatch");
        assertNull(r.match("GET", "/quests"), "no route");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:test --tests "com.ftbqbridge.http.RouterTest"`
Expected: FAIL — `Router` not found.

- [ ] **Step 3: Write minimal implementation**

```java
// RequestContext.java
package com.ftbqbridge.http;
import com.google.gson.JsonObject;
import java.util.Map;
public record RequestContext(String method, String path, Map<String,String> pathParams,
                             Map<String,String> query, JsonObject body, boolean loopback, String authHeader) {
    public String param(String n) { return pathParams.get(n); }
    public String queryOr(String n, String def) { return query.getOrDefault(n, def); }
}
```

```java
// JsonResponse.java
package com.ftbqbridge.http;
import com.google.gson.JsonElement;
public record JsonResponse(int status, JsonElement body) {
    public static JsonResponse ok(JsonElement b) { return new JsonResponse(200, b); }
    public static JsonResponse of(int s, JsonElement b) { return new JsonResponse(s, b); }
}
```

```java
// Router.java
package com.ftbqbridge.http;
import java.util.*;

public final class Router {
    @FunctionalInterface public interface Route { JsonResponse handle(RequestContext ctx); }
    public record Match(Route route, Map<String,String> params) {}

    private record Entry(String method, String[] segs, Route route) {}
    private final List<Entry> entries = new ArrayList<>();

    public Router add(String method, String pattern, Route route) {
        entries.add(new Entry(method, split(pattern), route));
        return this;
    }

    public Match match(String method, String path) {
        String[] p = split(path);
        for (Entry e : entries) {
            if (!e.method().equalsIgnoreCase(method) || e.segs().length != p.length) continue;
            Map<String,String> params = new HashMap<>();
            boolean ok = true;
            for (int i = 0; i < p.length; i++) {
                String seg = e.segs()[i];
                if (seg.startsWith("{") && seg.endsWith("}")) params.put(seg.substring(1, seg.length()-1), p[i]);
                else if (!seg.equals(p[i])) { ok = false; break; }
            }
            if (ok) return new Match(e.route(), params);
        }
        return null;
    }

    private static String[] split(String path) {
        String s = path;
        int q = s.indexOf('?'); if (q >= 0) s = s.substring(0, q);
        if (s.startsWith("/")) s = s.substring(1);
        if (s.endsWith("/")) s = s.substring(0, s.length()-1);
        if (s.isEmpty()) return new String[0];
        return s.split("/");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:test --tests "com.ftbqbridge.http.RouterTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/ftbqbridge/http/RequestContext.java common/src/main/java/com/ftbqbridge/http/JsonResponse.java common/src/main/java/com/ftbqbridge/http/Router.java common/src/test/java/com/ftbqbridge/http/RouterTest.java
git commit -m "feat: router with path params + request/response types"
```

---

### Task 7: `QuestBackend` contract + `ApiException` + `FakeQuestBackend`

**Files:**
- Create: `common/src/main/java/com/ftbqbridge/backend/QuestBackend.java`
- Create: `common/src/main/java/com/ftbqbridge/backend/ApiException.java`
- Create: `common/src/test/java/com/ftbqbridge/backend/FakeQuestBackend.java` (test sourceset)
- Test: `common/src/test/java/com/ftbqbridge/backend/ApiExceptionTest.java`

**Interfaces:**
- Produces:
  - `ApiException extends RuntimeException`: `int status; String type;` + factories `notFound(msg)`→404/`not_found`, `badRequest(msg)`→400/`bad_request`, `notLoaded()`→503/`quests_not_loaded`, `serverBusy()`→504/`server_busy`, `internal(msg)`→500/`internal`.
  - `QuestBackend` interface (all methods may throw `ApiException`): `JsonObject health()`, `JsonArray searchRegistry(String kind, String query, int limit, int offset)`, `JsonArray listTaskTypes()`, `JsonArray listRewardTypes()`, `JsonObject typeSchema(String kind, String typeId)`, `JsonObject questMap()`, `JsonObject getChapter(String id)`, `JsonObject getObject(String id)`, `JsonArray searchQuests(String q, String type)`, `JsonObject getRewardTable(String id)`, `JsonObject createObject(String type, String parent, JsonObject properties, JsonObject extra)`, `JsonObject editObject(String id, JsonObject properties)`, `void deleteObject(String id)`, `JsonObject setDependency(String questId, String dependsOnId, boolean add)`, `void save()`.
  - `FakeQuestBackend implements QuestBackend` — in-memory test double (see Step 3).

- [ ] **Step 1: Write the failing test**

```java
package com.ftbqbridge.backend;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiExceptionTest {
    @Test void factoriesCarryStatusAndType() {
        assertEquals(404, ApiException.notFound("x").status);
        assertEquals("not_found", ApiException.notFound("x").type);
        assertEquals(400, ApiException.badRequest("x").status);
        assertEquals("bad_request", ApiException.badRequest("x").type);
        assertEquals(503, ApiException.notLoaded().status);
        assertEquals("quests_not_loaded", ApiException.notLoaded().type);
        assertEquals(504, ApiException.serverBusy().status);
        assertEquals(500, ApiException.internal("x").status);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:test --tests "com.ftbqbridge.backend.ApiExceptionTest"`
Expected: FAIL — `ApiException` not found.

- [ ] **Step 3: Write implementation + contract + fake**

```java
// ApiException.java
package com.ftbqbridge.backend;

public final class ApiException extends RuntimeException {
    public final int status; public final String type;
    public ApiException(int status, String type, String message) { super(message); this.status = status; this.type = type; }
    public static ApiException notFound(String m)   { return new ApiException(404, "not_found", m); }
    public static ApiException badRequest(String m)  { return new ApiException(400, "bad_request", m); }
    public static ApiException notLoaded()           { return new ApiException(503, "quests_not_loaded", "Quest file not loaded"); }
    public static ApiException serverBusy()          { return new ApiException(504, "server_busy", "Server thread timed out"); }
    public static ApiException internal(String m)    { return new ApiException(500, "internal", m); }
}
```

```java
// QuestBackend.java
package com.ftbqbridge.backend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public interface QuestBackend {
    JsonObject health();
    JsonArray  searchRegistry(String kind, String query, int limit, int offset);
    JsonArray  listTaskTypes();
    JsonArray  listRewardTypes();
    JsonObject typeSchema(String kind, String typeId);
    JsonObject questMap();
    JsonObject getChapter(String id);
    JsonObject getObject(String id);
    JsonArray  searchQuests(String q, String type);
    JsonObject getRewardTable(String id);
    JsonObject createObject(String type, String parent, JsonObject properties, JsonObject extra);
    JsonObject editObject(String id, JsonObject properties);
    void       deleteObject(String id);
    JsonObject setDependency(String questId, String dependsOnId, boolean add);
    void       save();
}
```

```java
// FakeQuestBackend.java  (test sourceset)
package com.ftbqbridge.backend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;

public final class FakeQuestBackend implements QuestBackend {
    public final Map<String, JsonObject> objects = new LinkedHashMap<>();
    public int saveCount = 0;
    private int idSeq = 0x100;

    @Override public JsonObject health() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true); o.addProperty("questsLoaded", true);
        o.addProperty("protocolVersion", com.ftbqbridge.Protocol.PROTOCOL_VERSION);
        return o;
    }
    @Override public JsonArray searchRegistry(String kind, String query, int limit, int offset) {
        JsonArray a = new JsonArray();
        JsonObject e = new JsonObject(); e.addProperty("id", "minecraft:stone"); e.addProperty("displayName", "Stone");
        a.add(e); return a;
    }
    @Override public JsonArray listTaskTypes()   { return typeArray("ftbquests:item"); }
    @Override public JsonArray listRewardTypes() { return typeArray("ftbquests:item"); }
    private JsonArray typeArray(String id) { JsonArray a = new JsonArray(); JsonObject o = new JsonObject(); o.addProperty("typeId", id); a.add(o); return a; }
    @Override public JsonObject typeSchema(String kind, String typeId) { JsonObject o = new JsonObject(); o.addProperty("typeId", typeId); o.add("defaults", new JsonObject()); return o; }
    @Override public JsonObject questMap() { JsonObject o = new JsonObject(); o.add("chapterGroups", new JsonArray()); o.add("rewardTables", new JsonArray()); return o; }
    @Override public JsonObject getChapter(String id) { return require(id); }
    @Override public JsonObject getObject(String id)  { return require(id); }
    @Override public JsonArray searchQuests(String q, String type) { return new JsonArray(); }
    @Override public JsonObject getRewardTable(String id) { return require(id); }
    @Override public JsonObject createObject(String type, String parent, JsonObject properties, JsonObject extra) {
        String id = String.format("%016X", idSeq++);
        JsonObject o = new JsonObject(); o.addProperty("id", id); o.addProperty("type", type); o.addProperty("parent", parent);
        o.add("data", properties == null ? new JsonObject() : properties.deepCopy());
        objects.put(id, o); return o;
    }
    @Override public JsonObject editObject(String id, JsonObject properties) {
        JsonObject o = require(id); o.add("data", properties.deepCopy()); return o;
    }
    @Override public void deleteObject(String id) { if (objects.remove(id) == null) throw ApiException.notFound(id); }
    @Override public JsonObject setDependency(String questId, String dependsOnId, boolean add) {
        JsonObject o = new JsonObject(); o.addProperty("questId", questId); o.addProperty("dependsOnId", dependsOnId); o.addProperty("added", add); return o;
    }
    @Override public void save() { saveCount++; }
    private JsonObject require(String id) { JsonObject o = objects.get(id); if (o == null) throw ApiException.notFound(id); return o; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:test --tests "com.ftbqbridge.backend.ApiExceptionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/ftbqbridge/backend/ common/src/test/java/com/ftbqbridge/backend/
git commit -m "feat: QuestBackend contract, ApiException, in-memory fake"
```

---

### Task 8: `BridgeHttpServer` + `BridgeHandlers` — wired HTTP API (end-to-end against the fake)

**Files:**
- Create: `common/src/main/java/com/ftbqbridge/http/BridgeHandlers.java`
- Create: `common/src/main/java/com/ftbqbridge/http/BridgeHttpServer.java`
- Test: `common/src/test/java/com/ftbqbridge/http/BridgeHttpServerTest.java`

**Interfaces:**
- Consumes: `Router`, `RequestContext`, `JsonResponse`, `Auth`, `QuestBackend`, `ApiException`, `JsonMerge`, `Json.GSON`, `Protocol`.
- Produces:
  - `BridgeHandlers.register(Router r, QuestBackend backend)` — registers every route from the spec (§5.5) and maps `ApiException` → status; PATCH uses current `getObject(id).data` + `JsonMerge.shallowMerge` is performed inside the handler before calling `editObject`? No — handler passes raw `properties` to `editObject`, which performs the merge (so the fake/real both own merge semantics). For this task the handler passes `properties` straight through.
  - `BridgeHttpServer`: `BridgeHttpServer(String bindAddress, int port, boolean allowRemote, String token, Router router)`; `void start()`; `void stop()`; `int boundPort()`. Translates `HttpExchange` → `RequestContext`, applies `Auth.evaluate`, dispatches via `Router`, writes JSON. Unknown route → 404; `Auth` non-OK → 401/403; `ApiException` thrown in a handler → its status; any other exception → 500.

- [ ] **Step 1: Write the failing test**

```java
package com.ftbqbridge.http;

import com.ftbqbridge.backend.FakeQuestBackend;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class BridgeHttpServerTest {
    static BridgeHttpServer server; static FakeQuestBackend backend; static int port; static final String TOKEN = "secret";
    static HttpClient http = HttpClient.newHttpClient();

    @BeforeAll static void start() {
        backend = new FakeQuestBackend();
        Router r = new Router();
        BridgeHandlers.register(r, backend);
        server = new BridgeHttpServer("127.0.0.1", 0, false, TOKEN, r);
        server.start();
        port = server.boundPort();
    }
    @AfterAll static void stop() { server.stop(); }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Authorization", "Bearer " + TOKEN);
    }

    @Test void healthOk() throws Exception {
        HttpResponse<String> res = http.send(req("/health").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertTrue(JsonParser.parseString(res.body()).getAsJsonObject().get("ok").getAsBoolean());
    }
    @Test void unauthorizedWithoutToken() throws Exception {
        HttpResponse<String> res = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, res.statusCode());
    }
    @Test void createThenGetThenDelete() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("type", "CHAPTER"); body.addProperty("parent", "0000000000000001");
        body.add("properties", new JsonObject()); body.add("extra", new JsonObject());
        HttpResponse<String> create = http.send(req("/quests/object")
            .header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, create.statusCode());
        String id = JsonParser.parseString(create.body()).getAsJsonObject().get("id").getAsString();

        HttpResponse<String> get = http.send(req("/quests/object/" + id).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, get.statusCode());

        HttpResponse<String> del = http.send(req("/quests/object/" + id).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, del.statusCode());
    }
    @Test void missingObjectIs404() throws Exception {
        HttpResponse<String> res = http.send(req("/quests/object/DEADBEEF").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, res.statusCode());
        assertEquals("not_found", JsonParser.parseString(res.body()).getAsJsonObject()
            .getAsJsonObject("error").get("type").getAsString());
    }
    @Test void unknownRouteIs404() throws Exception {
        HttpResponse<String> res = http.send(req("/nope").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, res.statusCode());
    }
    @Test void saveCalls() throws Exception {
        int before = backend.saveCount;
        HttpResponse<String> res = http.send(req("/save").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertEquals(before + 1, backend.saveCount);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:test --tests "com.ftbqbridge.http.BridgeHttpServerTest"`
Expected: FAIL — `BridgeHandlers`/`BridgeHttpServer` not found.

- [ ] **Step 3: Write `BridgeHandlers`**

```java
package com.ftbqbridge.http;

import com.ftbqbridge.Protocol;
import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.backend.QuestBackend;
import com.google.gson.JsonObject;

public final class BridgeHandlers {
    private BridgeHandlers() {}

    public static void register(Router r, QuestBackend b) {
        r.add("GET", "/health", ctx -> JsonResponse.ok(b.health()));

        r.add("GET", "/registry/{kind}", ctx -> JsonResponse.ok(b.searchRegistry(
                ctx.param("kind"), ctx.queryOr("query", ""),
                parseInt(ctx.queryOr("limit", "50"), 50), parseInt(ctx.queryOr("offset", "0"), 0))));

        r.add("GET", "/task-types",   ctx -> JsonResponse.ok(b.listTaskTypes()));
        r.add("GET", "/reward-types", ctx -> JsonResponse.ok(b.listRewardTypes()));
        r.add("GET", "/task-types/{id}/schema",   ctx -> JsonResponse.ok(b.typeSchema("task",  ctx.param("id"))));
        r.add("GET", "/reward-types/{id}/schema", ctx -> JsonResponse.ok(b.typeSchema("reward", ctx.param("id"))));

        r.add("GET", "/quests", ctx -> JsonResponse.ok(b.questMap()));
        r.add("GET", "/quests/chapter/{id}", ctx -> JsonResponse.ok(b.getChapter(ctx.param("id"))));
        r.add("GET", "/quests/object/{id}",  ctx -> JsonResponse.ok(b.getObject(ctx.param("id"))));
        r.add("GET", "/quests/search", ctx -> JsonResponse.ok(b.searchQuests(ctx.queryOr("q", ""), ctx.queryOr("type", ""))));
        r.add("GET", "/reward-tables/{id}", ctx -> JsonResponse.ok(b.getRewardTable(ctx.param("id"))));

        r.add("POST", "/quests/object", ctx -> {
            JsonObject body = requireBody(ctx);
            return JsonResponse.ok(b.createObject(
                str(body, "type"), str(body, "parent"),
                obj(body, "properties"), obj(body, "extra")));
        });
        r.add("PATCH", "/quests/object/{id}", ctx -> {
            JsonObject body = requireBody(ctx);
            return JsonResponse.ok(b.editObject(ctx.param("id"), obj(body, "properties")));
        });
        r.add("DELETE", "/quests/object/{id}", ctx -> { b.deleteObject(ctx.param("id")); return ok(); });
        r.add("POST", "/quests/object/{id}/move", ctx -> {
            JsonObject body = requireBody(ctx);
            JsonObject props = new JsonObject();
            props.add("x", body.get("x")); props.add("y", body.get("y"));
            return JsonResponse.ok(b.editObject(ctx.param("id"), props));
        });
        r.add("POST", "/quests/dependencies", ctx -> {
            JsonObject body = requireBody(ctx);
            return JsonResponse.ok(b.setDependency(str(body, "questId"), str(body, "dependsOnId"),
                body.has("add") && body.get("add").getAsBoolean()));
        });
        r.add("POST", "/save", ctx -> { b.save(); return ok(); });
    }

    private static JsonResponse ok() { JsonObject o = new JsonObject(); o.addProperty("ok", true); return JsonResponse.ok(o); }
    private static JsonObject requireBody(RequestContext ctx) {
        if (ctx.body() == null) throw ApiException.badRequest("JSON body required");
        return ctx.body();
    }
    private static String str(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) throw ApiException.badRequest("missing field: " + k);
        return o.get(k).getAsString();
    }
    private static JsonObject obj(JsonObject o, String k) {
        return (o.has(k) && o.get(k).isJsonObject()) ? o.getAsJsonObject(k) : new JsonObject();
    }
    private static int parseInt(String s, int def) { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }
}
```

- [ ] **Step 4: Write `BridgeHttpServer`**

```java
package com.ftbqbridge.http;

import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.json.Json;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class BridgeHttpServer {
    private final String bindAddress; private final int port; private final boolean allowRemote;
    private final String token; private final Router router;
    private HttpServer server;

    public BridgeHttpServer(String bindAddress, int port, boolean allowRemote, String token, Router router) {
        this.bindAddress = bindAddress; this.port = port; this.allowRemote = allowRemote; this.token = token; this.router = router;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bindAddress), port), 0);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException e) { throw new RuntimeException("Failed to start bridge HTTP server", e); }
    }

    public void stop() { if (server != null) server.stop(0); }
    public int boundPort() { return server.getAddress().getPort(); }

    private void handle(HttpExchange ex) throws IOException {
        try {
            boolean loopback = ex.getRemoteAddress().getAddress().isLoopbackAddress();
            String authHeader = ex.getRequestHeaders().getFirst("Authorization");
            switch (Auth.evaluate(loopback, authHeader, token, allowRemote)) {
                case REMOTE_DISABLED -> { writeError(ex, 403, "remote_disabled", "Remote access disabled"); return; }
                case UNAUTHORIZED   -> { writeError(ex, 401, "unauthorized", "Missing or invalid token"); return; }
                default -> {}
            }
            String method = ex.getRequestMethod();
            String rawPath = ex.getRequestURI().getRawPath();
            Router.Match m = router.match(method, rawPath);
            if (m == null) { writeError(ex, 404, "not_found", "No route: " + method + " " + rawPath); return; }

            JsonObject body = readBody(ex);
            RequestContext ctx = new RequestContext(method, rawPath, m.params(), parseQuery(ex.getRequestURI().getRawQuery()),
                    body, loopback, authHeader);
            JsonResponse res = m.route().handle(ctx);
            write(ex, res.status(), Json.GSON.toJson(res.body()));
        } catch (ApiException e) {
            writeError(ex, e.status, e.type, e.getMessage());
        } catch (Exception e) {
            writeError(ex, 500, "internal", String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private static JsonObject readBody(HttpExchange ex) {
        try {
            byte[] raw = ex.getRequestBody().readAllBytes();
            if (raw.length == 0) return null;
            return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) { throw ApiException.badRequest("Malformed JSON body"); }
    }

    private static Map<String,String> parseQuery(String raw) {
        Map<String,String> q = new HashMap<>();
        if (raw == null || raw.isEmpty()) return q;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) q.put(urlDecode(pair), "");
            else q.put(urlDecode(pair.substring(0, i)), urlDecode(pair.substring(i + 1)));
        }
        return q;
    }
    private static String urlDecode(String s) { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8); }

    private static void writeError(HttpExchange ex, int status, String type, String message) throws IOException {
        JsonObject err = new JsonObject(); JsonObject inner = new JsonObject();
        inner.addProperty("code", status); inner.addProperty("type", type); inner.addProperty("message", message == null ? "" : message);
        err.add("error", inner);
        write(ex, status, Json.GSON.toJson(err));
    }
    private static void write(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :common:test --tests "com.ftbqbridge.http.BridgeHttpServerTest"`
Expected: PASS (6 tests). Then run the full unit suite: `./gradlew :common:test` → all green.

- [ ] **Step 6: Commit**

```bash
git add common/src/main/java/com/ftbqbridge/http/BridgeHandlers.java common/src/main/java/com/ftbqbridge/http/BridgeHttpServer.java common/src/test/java/com/ftbqbridge/http/BridgeHttpServerTest.java
git commit -m "feat: wired HTTP bridge (routing, auth, error mapping) over QuestBackend"
```

---

> **Tasks 9–12 build the Minecraft-coupled `FtbQuestsBackend`.** These cannot use the fast JUnit loop (they need a running server), so each is verified by booting a dev server and curling the live endpoint, asserting against `ServerQuestFile` state and the SNBT files under `run/config/ftbquests/quests/`. Add the FTB Quests/Library dependencies and (if needed) an access widener in Task 9. Ground every call in spec Appendix B.

### Task 9: `FtbQuestsBackend` skeleton + server-thread executor + read operations

**Files:**
- Modify: `common/build.gradle` (add FTB Quests + FTB Library deps; optional access widener)
- Create: `common/src/main/java/com/ftbqbridge/backend/ServerTaskExecutor.java`
- Create: `common/src/main/java/com/ftbqbridge/backend/ftbq/MinecraftServerExecutor.java`
- Create: `common/src/main/java/com/ftbqbridge/backend/ftbq/QuestSerializer.java`
- Create: `common/src/main/java/com/ftbqbridge/backend/ftbq/FtbQuestsBackend.java`

**Interfaces:**
- Consumes: `QuestBackend`, `ApiException`, `Json.GSON`, FTB Quests `ServerQuestFile`, `QuestObjectBase`, `Chapter`, `Quest`, etc. (spec §3).
- Produces:
  - `ServerTaskExecutor` interface: `<T> T call(java.util.concurrent.Callable<T> task)` (runs on server thread, blocks with timeout, wraps failures as `ApiException`).
  - `MinecraftServerExecutor implements ServerTaskExecutor` — wraps `ServerQuestFile.getInstance().server.submit(task).get(timeoutMs, MS)`; maps `TimeoutException`→`serverBusy`, missing instance→`notLoaded`.
  - `QuestSerializer` — static helpers: `JsonObject objectSummary(QuestObjectBase)`, `JsonObject objectFull(QuestObjectBase, HolderLookup.Provider)` (uses `writeData`), `String hex(long)` / `long parseHex(String)`.
  - `FtbQuestsBackend implements QuestBackend` — constructor `(ServerTaskExecutor exec)`; this task implements `health()`, `questMap()`, `getChapter(id)`, `getObject(id)`, `searchQuests(q,type)`, `getRewardTable(id)`. Mutating + registry + schema methods throw `ApiException.internal("not yet implemented")` until Tasks 10–12.

- [ ] **Step 1: Add dependencies**

In `common/build.gradle` add (resolve coordinates against the current FTB maven; CurseMaven is the fallback):

```groovy
repositories {
    maven { url "https://maven.ftb.dev/releases" }
    maven { url "https://www.cursemaven.com" }
}
dependencies {
    // Prefer FTB maven coordinates; if unavailable use cursemaven file IDs for FTB Quests/Library 1.21.1 builds.
    modImplementation "dev.ftb.mods:ftb-library-common:${rootProject.ftb_library_version}"
    modImplementation "dev.ftb.mods:ftb-quests-common:${rootProject.ftb_quests_version}"
}
```

Add `ftb_library_version=2101.1.31` and `ftb_quests_version=2101.1.27` to `gradle.properties`. Also add the FTB Quests/Library `modImplementation` lines to `neoforge/build.gradle` and `fabric/build.gradle` using the loader-specific artifacts (`ftb-quests-neoforge` / `ftb-quests-fabric`, etc.), matching the template's dependency style.

Verify: `./gradlew :common:compileJava` resolves and compiles.

- [ ] **Step 2: Write `ServerTaskExecutor` + `MinecraftServerExecutor`**

```java
// ServerTaskExecutor.java
package com.ftbqbridge.backend;
import java.util.concurrent.Callable;
public interface ServerTaskExecutor { <T> T call(Callable<T> task); }
```

```java
// MinecraftServerExecutor.java
package com.ftbqbridge.backend.ftbq;

import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.backend.ServerTaskExecutor;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.*;

public final class MinecraftServerExecutor implements ServerTaskExecutor {
    private final long timeoutMs;
    public MinecraftServerExecutor(long timeoutMs) { this.timeoutMs = timeoutMs; }

    @Override public <T> T call(Callable<T> task) {
        if (!ServerQuestFile.exists()) throw ApiException.notLoaded();
        MinecraftServer server = ServerQuestFile.getInstance().server;
        CompletableFuture<T> fut = server.submit(() -> {
            try { return task.call(); }
            catch (ApiException ae) { throw ae; }
            catch (Exception e) { throw ApiException.internal(String.valueOf(e.getMessage())); }
        });
        try {
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            throw ApiException.serverBusy();
        } catch (ExecutionException ee) {
            Throwable c = ee.getCause();
            if (c instanceof ApiException ae) throw ae;
            throw ApiException.internal(String.valueOf(c == null ? ee.getMessage() : c.getMessage()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ApiException.internal("interrupted");
        }
    }
}
```

> **API check:** confirm `ServerQuestFile.getInstance()`, `.exists()`, and the public `server` field exist on the pinned FTB Quests build (spec §3.6). On 1.20.1 these differ (`.INSTANCE` field) — out of scope here.

- [ ] **Step 3: Write `QuestSerializer` (read side)**

```java
package com.ftbqbridge.backend.ftbq;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ftb.mods.ftbquests.quest.*;
import net.minecraft.core.HolderLookup;

public final class QuestSerializer {
    private QuestSerializer() {}

    public static String hex(long id) { return String.format("%016X", id); }
    public static long parseHex(String s) {
        String t = s.startsWith("#") ? s.substring(1) : s;
        return Long.parseUnsignedLong(t, 16);
    }

    public static JsonObject objectSummary(QuestObjectBase o) {
        JsonObject j = new JsonObject();
        j.addProperty("id", hex(o.id));
        j.addProperty("type", o.getObjectType().name());
        j.addProperty("title", o.getTitle().getString());
        return j;
    }

    /** Full object including its writeData JSON (Json5Object on 1.21 — convert to Gson via toString/parse). */
    public static JsonObject objectFull(QuestObjectBase o, HolderLookup.Provider provider) {
        JsonObject j = objectSummary(o);
        // FTB Quests 1.21 writeData uses Json5Object; serialize then re-parse into Gson for transport.
        // Implementation detail to confirm during the spike (Task 11 nuance): obtain Json5Object via
        //   var j5 = new dev.ftb.mods.ftblibrary.snbt.... ; o.writeData(j5, provider);
        // then j.add("data", com.google.gson.JsonParser.parseString(j5.toString()));
        return j;
    }
}
```

> **Spike here:** confirm the exact `writeData(Json5Object, HolderLookup.Provider)` usage and the cleanest Json5→Gson bridge (likely `JsonParser.parseString(json5.toString())`). Capture the resolved approach in code comments. This same Json5↔Gson bridge is reused by every read/write method.

- [ ] **Step 4: Write `FtbQuestsBackend` (read methods only)**

```java
package com.ftbqbridge.backend.ftbq;

import com.ftbqbridge.Protocol;
import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.backend.QuestBackend;
import com.ftbqbridge.backend.ServerTaskExecutor;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ftb.mods.ftbquests.quest.*;

public final class FtbQuestsBackend implements QuestBackend {
    private final ServerTaskExecutor exec;
    public FtbQuestsBackend(ServerTaskExecutor exec) { this.exec = exec; }

    @Override public JsonObject health() {
        return exec.call(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("questsLoaded", ServerQuestFile.exists());
            o.addProperty("protocolVersion", Protocol.PROTOCOL_VERSION);
            o.addProperty("loader", loaderName());
            return o;
        });
    }

    @Override public JsonObject questMap() {
        return exec.call(() -> {
            ServerQuestFile f = ServerQuestFile.getInstance();
            JsonObject root = new JsonObject();
            JsonArray groups = new JsonArray();
            for (ChapterGroup g : f.getChapterGroups()) {
                JsonObject gj = new JsonObject();
                gj.addProperty("id", QuestSerializer.hex(g.id));
                gj.addProperty("title", g.getTitle().getString());
                JsonArray chapters = new JsonArray();
                for (Chapter c : g.chapters) {
                    JsonObject cj = new JsonObject();
                    cj.addProperty("id", QuestSerializer.hex(c.id));
                    cj.addProperty("title", c.getTitle().getString());
                    cj.addProperty("filename", c.getFilename());
                    cj.addProperty("questCount", c.quests.size());
                    chapters.add(cj);
                }
                gj.add("chapters", chapters);
                groups.add(gj);
            }
            root.add("chapterGroups", groups);
            JsonArray tables = new JsonArray();
            for (RewardTable t : f.getRewardTables()) tables.add(QuestSerializer.objectSummary(t));
            root.add("rewardTables", tables);
            return root;
        });
    }

    @Override public JsonObject getChapter(String id) {
        return exec.call(() -> {
            Chapter c = ServerQuestFile.getInstance().getChapter(QuestSerializer.parseHex(id));
            if (c == null) throw ApiException.notFound("chapter " + id);
            JsonObject j = QuestSerializer.objectSummary(c);
            JsonArray quests = new JsonArray();
            for (Quest q : c.quests) {
                JsonObject qj = QuestSerializer.objectSummary(q);
                qj.addProperty("x", q.x); qj.addProperty("y", q.y); qj.addProperty("shape", q.shape);
                quests.add(qj);
            }
            j.add("quests", quests);
            return j;
        });
    }

    @Override public JsonObject getObject(String id) {
        return exec.call(() -> {
            QuestObjectBase o = ServerQuestFile.getInstance().getBase(QuestSerializer.parseHex(id));
            if (o == null) throw ApiException.notFound(id);
            return QuestSerializer.objectFull(o, ServerQuestFile.getInstance().holderLookup());
        });
    }

    @Override public JsonArray searchQuests(String q, String type) {
        return exec.call(() -> {
            JsonArray out = new JsonArray();
            ServerQuestFile.getInstance().forAllQuests(quest -> {
                if (q.isEmpty() || quest.getTitle().getString().toLowerCase().contains(q.toLowerCase()))
                    out.add(QuestSerializer.objectSummary(quest));
            });
            return out;
        });
    }

    @Override public JsonObject getRewardTable(String id) {
        return exec.call(() -> {
            RewardTable t = ServerQuestFile.getInstance().getRewardTable(QuestSerializer.parseHex(id));
            if (t == null) throw ApiException.notFound("reward table " + id);
            return QuestSerializer.objectSummary(t);
        });
    }

    // ---- Implemented in Tasks 10-12 ----
    @Override public JsonArray searchRegistry(String k, String q, int l, int o) { throw ApiException.internal("not yet implemented"); }
    @Override public JsonArray listTaskTypes()   { throw ApiException.internal("not yet implemented"); }
    @Override public JsonArray listRewardTypes() { throw ApiException.internal("not yet implemented"); }
    @Override public JsonObject typeSchema(String k, String t) { throw ApiException.internal("not yet implemented"); }
    @Override public JsonObject createObject(String t, String p, JsonObject props, JsonObject extra) { throw ApiException.internal("not yet implemented"); }
    @Override public JsonObject editObject(String id, JsonObject props) { throw ApiException.internal("not yet implemented"); }
    @Override public void deleteObject(String id) { throw ApiException.internal("not yet implemented"); }
    @Override public JsonObject setDependency(String a, String b, boolean add) { throw ApiException.internal("not yet implemented"); }
    @Override public void save() { throw ApiException.internal("not yet implemented"); }

    private static String loaderName() {
        return dev.architectury.platform.Platform.getEnv() != null ? dev.architectury.platform.Platform.getModLoader() : "unknown";
    }
}
```

> Confirm field/method names against the pinned build: `ChapterGroup.chapters`, `Chapter.quests`, `Chapter.getFilename()`, `Quest.x/y/shape`, `f.getChapterGroups()/getRewardTables()/getChapter/getBase/getRewardTable/forAllQuests/holderLookup()` (spec §3.6, Appendix B). Adjust to the actual signatures where they differ.

- [ ] **Step 5: Wire a temporary dev route and boot-test**

Temporarily, in a scratch `init`, construct `new FtbQuestsBackend(new MinecraftServerExecutor(10000))`, register handlers on a `BridgeHttpServer` bound to `127.0.0.1:25599` with a fixed token `"dev"`, and start it on server-started (full lifecycle is Task 13; for now a minimal hook is fine). Run `./gradlew :neoforge:runServer` with FTB Quests + FTB Library + Architectury API present in `run/mods` (or as dependencies the dev runtime provides). After the world loads:

```bash
curl -s -H "Authorization: Bearer dev" http://127.0.0.1:25599/health
curl -s -H "Authorization: Bearer dev" http://127.0.0.1:25599/quests
```
Expected: `/health` → `{"ok":true,"questsLoaded":true,"protocolVersion":1,...}`; `/quests` → chapterGroups/rewardTables JSON reflecting the dev world's quests (create a chapter in-game first via the FTB Quests editor to have content).

- [ ] **Step 6: Commit**

```bash
git add common/build.gradle gradle.properties neoforge/build.gradle fabric/build.gradle common/src/main/java/com/ftbqbridge/backend/
git commit -m "feat: FtbQuestsBackend read ops + server-thread executor"
```

---

### Task 10: Registry enumeration + task/reward type lists

**Files:**
- Create: `common/src/main/java/com/ftbqbridge/backend/ftbq/RegistryReader.java`
- Modify: `common/src/main/java/com/ftbqbridge/backend/ftbq/FtbQuestsBackend.java` (implement `searchRegistry`, `listTaskTypes`, `listRewardTypes`)

**Interfaces:**
- Consumes: `net.minecraft.core.registries.BuiltInRegistries`, `dev.ftb.mods.ftbquests.quest.task.TaskTypes.TYPES`, `dev.ftb.mods.ftbquests.quest.reward.RewardTypes.TYPES`.
- Produces: `RegistryReader.search(String kind, String query, int limit, int offset)` → `JsonArray` of `{id, displayName}`; `RegistryReader.taskTypes()` / `.rewardTypes()` → `JsonArray`.

- [ ] **Step 1: Write `RegistryReader`**

```java
package com.ftbqbridge.backend.ftbq;

import com.ftbqbridge.backend.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ftb.mods.ftbquests.quest.reward.RewardType;
import dev.ftb.mods.ftbquests.quest.reward.RewardTypes;
import dev.ftb.mods.ftbquests.quest.task.TaskType;
import dev.ftb.mods.ftbquests.quest.task.TaskTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Map;

public final class RegistryReader {
    private RegistryReader() {}

    public static JsonArray search(String kind, String query, int limit, int offset) {
        Registry<?> reg = switch (kind) {
            case "items"        -> BuiltInRegistries.ITEM;
            case "blocks"       -> BuiltInRegistries.BLOCK;
            case "fluids"       -> BuiltInRegistries.FLUID;
            case "entity_types" -> BuiltInRegistries.ENTITY_TYPE;
            case "mob_effects"  -> BuiltInRegistries.MOB_EFFECT;
            default -> throw ApiException.badRequest("unknown registry kind: " + kind);
        };
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        JsonArray out = new JsonArray();
        int seen = 0;
        for (ResourceLocation rl : reg.keySet()) {
            String id = rl.toString();
            if (!q.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (seen++ < offset) continue;
            if (out.size() >= limit) break;
            JsonObject e = new JsonObject();
            e.addProperty("id", id);
            e.addProperty("displayName", id); // display-name resolution refined later if needed
            out.add(e);
        }
        return out;
    }

    public static JsonArray taskTypes() {
        JsonArray a = new JsonArray();
        for (Map.Entry<ResourceLocation, TaskType> e : TaskTypes.TYPES.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("typeId", e.getKey().toString());
            o.addProperty("namespace", e.getKey().getNamespace());
            o.addProperty("displayName", e.getValue().getDisplayName().getString());
            a.add(o);
        }
        return a;
    }

    public static JsonArray rewardTypes() {
        JsonArray a = new JsonArray();
        for (Map.Entry<ResourceLocation, RewardType> e : RewardTypes.TYPES.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("typeId", e.getKey().toString());
            o.addProperty("namespace", e.getKey().getNamespace());
            o.addProperty("displayName", e.getValue().getDisplayName().getString());
            a.add(o);
        }
        return a;
    }
}
```

> Confirm `TaskType.getDisplayName()` / `RewardType.getDisplayName()` return a `Component` (spec §3.5). Add more registry kinds (`biomes`, `dimensions`, `structures`, `advancements`, `stats`) by resolving from the server's dynamic registries via `ServerQuestFile.getInstance().server.registryAccess()` (those are not in `BuiltInRegistries`); start with the `BuiltInRegistries` set above and extend.

- [ ] **Step 2: Implement the three backend methods**

Replace the stubs in `FtbQuestsBackend`:

```java
@Override public JsonArray searchRegistry(String kind, String query, int limit, int offset) {
    return exec.call(() -> RegistryReader.search(kind, query, Math.min(Math.max(limit,1), 500), Math.max(offset,0)));
}
@Override public JsonArray listTaskTypes()   { return exec.call(RegistryReader::taskTypes); }
@Override public JsonArray listRewardTypes() { return exec.call(RegistryReader::rewardTypes); }
```

- [ ] **Step 3: Boot-test**

Run `./gradlew :neoforge:runServer`, then:

```bash
curl -s -H "Authorization: Bearer dev" "http://127.0.0.1:25599/registry/items?query=diamond&limit=10"
curl -s -H "Authorization: Bearer dev" http://127.0.0.1:25599/task-types
curl -s -H "Authorization: Bearer dev" http://127.0.0.1:25599/reward-types
```
Expected: items list contains `minecraft:diamond`; task-types lists the 14 built-ins (`ftbquests:item`, `ftbquests:checkmark`, …); reward-types lists the 13 built-ins. If a quest mod/KubeJS pack is in the dev run, its custom types appear too.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/com/ftbqbridge/backend/ftbq/RegistryReader.java common/src/main/java/com/ftbqbridge/backend/ftbq/FtbQuestsBackend.java
git commit -m "feat: registry search + task/reward type enumeration"
```

---

### Task 11: Type schema introspection (writeData defaults + guarded fillConfigGroup)

**Files:**
- Create: `common/src/main/java/com/ftbqbridge/backend/ftbq/TypeSchemas.java`
- Modify: `FtbQuestsBackend.java` (implement `typeSchema`)

**Interfaces:**
- Produces: `TypeSchemas.schema(String kind, String typeId)` → `JsonObject { typeId, defaults: {...}, fields?: [...] }`. Primary: instantiate a throwaway task/reward, call `writeData` to capture default fields. Enhancement: guarded `fillConfigGroup` for richer field metadata.

- [ ] **Step 1: Implement primary path (writeData defaults)**

```java
package com.ftbqbridge.backend.ftbq;

import com.ftbqbridge.backend.ApiException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.reward.RewardType;
import dev.ftb.mods.ftbquests.quest.reward.RewardTypes;
import dev.ftb.mods.ftbquests.quest.task.TaskType;
import dev.ftb.mods.ftbquests.quest.task.TaskTypes;
import net.minecraft.resources.ResourceLocation;

public final class TypeSchemas {
    private TypeSchemas() {}

    public static JsonObject schema(String kind, String typeId) {
        ServerQuestFile f = ServerQuestFile.getInstance();
        ResourceLocation rl = ResourceLocation.parse(typeId);
        JsonObject out = new JsonObject();
        out.addProperty("typeId", typeId);
        try {
            // Build a throwaway instance against a fake/transient parent, then writeData -> Json5 -> Gson.
            // Exact factory call confirmed during spike; e.g. TaskType.createTask(f.newID(), fakeQuest, ...) / RewardType.createReward(...).
            Object instance = makeThrowaway(kind, rl, f);
            JsonObject defaults = writeDataToGson(instance, f);
            out.add("defaults", defaults);
        } catch (Exception e) {
            throw ApiException.badRequest("could not introspect type " + typeId + ": " + e.getMessage());
        }
        // Enhancement (guarded): fillConfigGroup may reference client-only classes; never let it fail the request.
        try { out.add("fields", FillConfigIntrospector.tryFields(/* instance */ null)); }
        catch (Throwable ignored) { /* fall back to defaults only */ }
        return out;
    }

    // makeThrowaway / writeDataToGson resolved during the Task-11 spike (see note).
    private static Object makeThrowaway(String kind, ResourceLocation rl, ServerQuestFile f) { throw new UnsupportedOperationException("spike"); }
    private static JsonObject writeDataToGson(Object instance, ServerQuestFile f) { throw new UnsupportedOperationException("spike"); }
}
```

> **This task contains the two spec spikes (§11).** Resolve before finalizing:
> 1. **Throwaway instantiation:** the exact way to create a `Task`/`Reward` of a given type without a real quest. Options grounded in the source: `TaskType.createTask(long, Quest)` against a transient `Quest`, and `RewardType.createReward(long, Quest)` / `RewardTable.createRewardForTable(...)`. Use the reward table's `fakeQuest` if a parent is required.
> 2. **fillConfigGroup server-safety:** put the `fillConfigGroup` call behind `FillConfigIntrospector` (a separate class) and a `try/catch (Throwable)` so a `NoClassDefFoundError` on a dedicated server degrades gracefully to defaults-only. If it proves entirely client-side, drop `fields` and rely on `defaults` + the companion's curated docs (Plan 2).
>
> Capture the resolved approach in code + a comment. The `writeData→Json5→Gson` bridge is the same one resolved in Task 9 Step 3 — reuse it (extract a shared `Json5Bridge` helper).

- [ ] **Step 2: Implement `typeSchema` in backend**

```java
@Override public JsonObject typeSchema(String kind, String typeId) {
    if (!kind.equals("task") && !kind.equals("reward")) throw ApiException.badRequest("kind must be task|reward");
    return exec.call(() -> TypeSchemas.schema(kind, typeId));
}
```

- [ ] **Step 3: Boot-test**

```bash
curl -s -H "Authorization: Bearer dev" http://127.0.0.1:25599/task-types/ftbquests:item/schema
curl -s -H "Authorization: Bearer dev" http://127.0.0.1:25599/reward-types/ftbquests:command/schema
```
Expected: each returns `{typeId, defaults:{...}}` with the type's serialized default fields; `fields` present only if `fillConfigGroup` ran safely. No server crash either way.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/com/ftbqbridge/backend/ftbq/TypeSchemas.java common/src/main/java/com/ftbqbridge/backend/ftbq/FillConfigIntrospector.java common/src/main/java/com/ftbqbridge/backend/ftbq/FtbQuestsBackend.java
git commit -m "feat: type schema introspection (defaults + guarded config fields)"
```

---

### Task 12: Create / edit / delete / dependency / save (live broadcast)

**Files:**
- Modify: `FtbQuestsBackend.java` (implement `createObject`, `editObject`, `deleteObject`, `setDependency`, `save`)
- Modify: `QuestSerializer.java` (add Json5↔Gson write helpers if not already shared)

**Interfaces:**
- Consumes: `ServerQuestFile.create/getBase/refreshIDMap/clearCachedData/markDirty/saveNow/deleteObject`, `CreateObjectResponseMessage.create`, `EditObjectResponseMessage`, `Server2PlayNetworking.sendToAllPlayers` (spec §3.6 / Appendix B).
- Produces: full mutation methods that mutate, **broadcast to all clients**, and persist per `saveMode`.

- [ ] **Step 1: Implement create**

```java
@Override public JsonObject createObject(String type, String parent, JsonObject properties, JsonObject extra) {
    return exec.call(() -> {
        ServerQuestFile f = ServerQuestFile.getInstance();
        var objType = dev.ftb.mods.ftbquests.quest.QuestObjectType.valueOf(type.toUpperCase());
        long parentId = parent == null || parent.isBlank() ? 1L : QuestSerializer.parseHex(parent);
        var extraJson5 = QuestSerializer.gsonToJson5(extra == null ? new com.google.gson.JsonObject() : extra);
        var o = f.create(f.newID(), objType, parentId, extraJson5);                 // §3.6 create
        o.readData(QuestSerializer.gsonToJson5(properties == null ? new com.google.gson.JsonObject() : properties), f.holderLookup());
        o.onCreated();
        f.refreshIDMap(); f.clearCachedData(); f.markDirty();
        f.getTranslationManager().processInitialTranslation(extraJson5, o);
        dev.ftb.mods.ftbquests.client... /* broadcast */ ;
        broadcastCreate(f, o, extraJson5);
        persist(f);
        return QuestSerializer.objectFull(o, f.holderLookup());
    });
}
```

Where `broadcastCreate` / `broadcastEdit` / `broadcastDelete` and `persist` are private helpers:

```java
private static void broadcastCreate(ServerQuestFile f, dev.ftb.mods.ftbquests.quest.QuestObjectBase o, Object extraJson5) {
    // CreateObjectResponseMessage.create(o, extra, creatorOrNull) broadcast to all (spec §3.6).
    dev.ftb.mods.ftbquests.net.... .sendToAllPlayers(f.server,
        dev.ftb.mods.ftbquests.net.CreateObjectResponseMessage.create(o, null, null));
}
private void persist(ServerQuestFile f) {
    if ("immediate".equals(saveMode)) f.saveNow(); // else rely on markDirty
}
```

> Resolve the exact broadcast call (`Server2PlayNetworking.sendToAllPlayers(server, msg)` per spec §3.6) and the `CreateObjectResponseMessage.create(obj, extraOrNull, creatorOrNull)` signature against the pinned build. Pass `null` creator so no client is forced to open an edit screen. Add `saveMode` to the constructor (default from `BridgeConfig`).

- [ ] **Step 2: Implement edit**

```java
@Override public JsonObject editObject(String id, JsonObject properties) {
    return exec.call(() -> {
        ServerQuestFile f = ServerQuestFile.getInstance();
        var o = f.getBase(QuestSerializer.parseHex(id));
        if (o == null) throw ApiException.notFound(id);
        // Partial-merge: current writeData JSON <- properties (spec §5: PATCH semantics)
        com.google.gson.JsonObject current = QuestSerializer.writeDataToGson(o, f);
        com.google.gson.JsonObject merged = com.ftbqbridge.json.JsonMerge.shallowMerge(current, properties);
        o.readData(QuestSerializer.gsonToJson5(merged), f.holderLookup());
        f.clearCachedData(); f.markDirty();
        broadcastEdit(f, o);                       // EditObjectResponseMessage to all
        o.editedFromGUIOnServer();
        persist(f);
        return QuestSerializer.objectFull(o, f.holderLookup());
    });
}
```

- [ ] **Step 3: Implement delete / setDependency / save**

```java
@Override public void deleteObject(String id) {
    exec.call(() -> { ServerQuestFile f = ServerQuestFile.getInstance();
        if (f.getBase(QuestSerializer.parseHex(id)) == null) throw ApiException.notFound(id);
        f.deleteObject(QuestSerializer.parseHex(id));   // self-broadcasts (spec §3.6)
        persist(f); return null; });
}

@Override public JsonObject setDependency(String questId, String dependsOnId, boolean add) {
    return exec.call(() -> {
        ServerQuestFile f = ServerQuestFile.getInstance();
        var quest = f.getQuest(QuestSerializer.parseHex(questId));
        if (quest == null) throw ApiException.notFound("quest " + questId);
        // Edit the quest's "dependencies" via its writeData JSON (array of hex code strings) + readData, then broadcast edit.
        com.google.gson.JsonObject current = QuestSerializer.writeDataToGson(quest, f);
        com.google.gson.JsonArray deps = current.has("dependencies") && current.get("dependencies").isJsonArray()
            ? current.getAsJsonArray("dependencies") : new com.google.gson.JsonArray();
        String dep = dependsOnId.toUpperCase();
        boolean present = false; com.google.gson.JsonArray next = new com.google.gson.JsonArray();
        for (var el : deps) { if (el.getAsString().equalsIgnoreCase(dep)) { present = true; if (!add) continue; } next.add(el); }
        if (add && !present) next.add(dep);
        current.add("dependencies", next);
        quest.readData(QuestSerializer.gsonToJson5(current), f.holderLookup());
        f.clearCachedData(); f.markDirty(); broadcastEdit(f, quest); quest.editedFromGUIOnServer(); persist(f);
        JsonObject r = new JsonObject(); r.addProperty("questId", questId); r.addProperty("dependsOnId", dep); r.addProperty("added", add); return r;
    });
}

@Override public void save() { exec.call(() -> { ServerQuestFile.getInstance().saveNow(); return null; }); }
```

> Confirm the on-disk/serialized key for dependencies is `"dependencies"` as a JSON array of hex code strings (spec §3.3). Adjust if the Json5 shape differs.

- [ ] **Step 4: Boot-test (live create/edit/delete + hot-sync)**

Run `./gradlew :neoforge:runServer`. (Optionally also join with a client via `:neoforge:runClient` against the same world to watch live sync.)

```bash
# create a chapter group + chapter, then a quest
GROUP=$(curl -s -H "Authorization: Bearer dev" -H "Content-Type: application/json" \
  -d '{"type":"CHAPTER_GROUP","parent":"0000000000000001","properties":{"title":"MCP Test"},"extra":{}}' \
  http://127.0.0.1:25599/quests/object | python -c "import sys,json;print(json.load(sys.stdin)['id'])")
CH=$(curl -s -H "Authorization: Bearer dev" -H "Content-Type: application/json" \
  -d "{\"type\":\"CHAPTER\",\"parent\":\"0000000000000001\",\"properties\":{\"title\":\"Intro\"},\"extra\":{\"group\":\"$GROUP\"}}" \
  http://127.0.0.1:25599/quests/object | python -c "import sys,json;print(json.load(sys.stdin)['id'])")
# edit the chapter title
curl -s -H "Authorization: Bearer dev" -H "Content-Type: application/json" -X PATCH \
  -d '{"properties":{"title":"Introduction"}}' http://127.0.0.1:25599/quests/object/$CH
curl -s -H "Authorization: Bearer dev" -X POST http://127.0.0.1:25599/save
```
Expected: objects appear in `/quests`; files written under `run/config/ftbquests/quests/chapters/`; if a client is connected, the new chapter/edit appears **live** in the quest book without relog. Delete with `curl -X DELETE .../quests/object/$CH` and confirm it disappears live.

> If titles don't round-trip (the TranslationManager nuance, spec §11 #2), pass the title via `extra` on create and verify the edit path; capture the resolved field location in code + carry it into Plan 2's skill docs.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/ftbqbridge/backend/ftbq/
git commit -m "feat: live create/edit/delete/dependency/save with client broadcast"
```

---

### Task 13: Loader lifecycle wiring (NeoForge + Fabric)

**Files:**
- Modify: `common/src/main/java/com/ftbqbridge/FtbQuestsBridge.java` (own config load, backend, server start/stop)
- Create/Modify: loader entrypoints to invoke lifecycle hooks (NeoForge `ServerStartedEvent`/`ServerStoppingEvent`; Fabric `ServerLifecycleEvents.SERVER_STARTED`/`SERVER_STOPPING`)
- Remove: any temporary dev hook added in Task 9/10.

**Interfaces:**
- Consumes: `BridgeConfig`, `RuntimeCredentials`, `BridgeHttpServer`, `BridgeHandlers`, `Router`, `FtbQuestsBackend`, `MinecraftServerExecutor`, `Platform.getConfigFolder()`.
- Produces: `FtbQuestsBridge.onServerStarted(MinecraftServer)` and `FtbQuestsBridge.onServerStopping(MinecraftServer)` called by both loaders; starts the bound HTTP server after FTB Quests has loaded and writes runtime creds; stops it on shutdown.

- [ ] **Step 1: Implement lifecycle in common**

```java
package com.ftbqbridge;

import com.ftbqbridge.backend.ftbq.FtbQuestsBackend;
import com.ftbqbridge.backend.ftbq.MinecraftServerExecutor;
import com.ftbqbridge.config.BridgeConfig;
import com.ftbqbridge.config.RuntimeCredentials;
import com.ftbqbridge.http.BridgeHandlers;
import com.ftbqbridge.http.BridgeHttpServer;
import com.ftbqbridge.http.Router;
import dev.architectury.platform.Platform;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import java.nio.file.Path;

public final class FtbQuestsBridge {
    public static final Logger LOG = LoggerFactory.getLogger("ftbquests-bridge");
    private static BridgeHttpServer httpServer;
    private FtbQuestsBridge() {}

    public static void init() { LOG.info("[ftbquests-bridge] loaded (protocol v{})", Protocol.PROTOCOL_VERSION); }

    public static void onServerStarted(MinecraftServer server) {
        try {
            Path configDir = Platform.getConfigFolder();
            BridgeConfig cfg = BridgeConfig.load(configDir.resolve("ftbquests-bridge.json"));
            if (!cfg.enabled) { LOG.info("[ftbquests-bridge] disabled via config"); return; }

            Router r = new Router();
            BridgeHandlers.register(r, new FtbQuestsBackend(new MinecraftServerExecutor(cfg.requestTimeoutMs), cfg.saveMode));
            httpServer = new BridgeHttpServer(cfg.bindAddress, cfg.port, cfg.allowRemote, cfg.token, r);
            httpServer.start();
            int boundPort = httpServer.boundPort();
            RuntimeCredentials.write(configDir.resolve("ftbquests-bridge/runtime.json"),
                    boundPort, cfg.token, Protocol.PROTOCOL_VERSION, cfg.bindAddress);
            LOG.info("[ftbquests-bridge] HTTP API on {}:{} (allowRemote={})", cfg.bindAddress, boundPort, cfg.allowRemote);
            if (cfg.allowRemote) LOG.warn("[ftbquests-bridge] allowRemote=true — non-loopback clients may connect with the token");
        } catch (Exception e) {
            LOG.error("[ftbquests-bridge] failed to start HTTP API", e);
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        if (httpServer != null) { httpServer.stop(); httpServer = null; LOG.info("[ftbquests-bridge] HTTP API stopped"); }
    }
}
```

> Add the `saveMode` parameter to `FtbQuestsBackend`'s constructor (`public FtbQuestsBackend(ServerTaskExecutor exec, String saveMode)`) and store it for `persist(...)` (Task 12).

- [ ] **Step 2: Wire NeoForge events**

```java
package com.ftbqbridge.neoforge;

import com.ftbqbridge.FtbQuestsBridge; import com.ftbqbridge.Protocol;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(Protocol.MOD_ID)
public final class FtbQuestsBridgeNeoForge {
    public FtbQuestsBridgeNeoForge() {
        FtbQuestsBridge.init();
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e)  -> FtbQuestsBridge.onServerStarted(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> FtbQuestsBridge.onServerStopping(e.getServer()));
    }
}
```

- [ ] **Step 3: Wire Fabric events**

```java
package com.ftbqbridge.fabric;

import com.ftbqbridge.FtbQuestsBridge;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class FtbQuestsBridgeFabric implements ModInitializer {
    @Override public void onInitialize() {
        FtbQuestsBridge.init();
        ServerLifecycleEvents.SERVER_STARTED.register(FtbQuestsBridge::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(FtbQuestsBridge::onServerStopping);
    }
}
```

> `ServerStartedEvent` fires after FTB Quests' own `serverStarted` load in most orderings; if `/health` reports `questsLoaded=false` immediately after boot, the endpoints still return 503 cleanly until the file loads (no crash). Add `fabric-api` lifecycle module to `fabric/build.gradle` if not already present.

- [ ] **Step 4: Full integration verification**

Run both: `./gradlew :neoforge:runServer` then `./gradlew :fabric:runServer`. For each, after world load with no fixed dev token:

```bash
# read the generated token/port
cat run/config/ftbquests-bridge/runtime.json
TOKEN=$(python -c "import json;print(json.load(open('run/config/ftbquests-bridge/runtime.json'))['token'])")
PORT=$(python -c "import json;print(json.load(open('run/config/ftbquests-bridge/runtime.json'))['port'])")
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:$PORT/health
# verify remote/no-token rejection
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:$PORT/health   # -> 401
```
Expected: `/health` ok on both loaders; missing-token → 401; `runtime.json` present with the live port/token; server stop logs `HTTP API stopped`.

- [ ] **Step 5: Run the full unit suite + build**

Run: `./gradlew :common:test build`
Expected: all unit tests pass; both loader jars build.

- [ ] **Step 6: Commit**

```bash
git add common/src/main/java/com/ftbqbridge/FtbQuestsBridge.java neoforge/ fabric/
git commit -m "feat: server lifecycle wiring + runtime creds (neoforge+fabric)"
```

---

## Self-Review

**Spec coverage:**
- Registries/blocks/items → Task 10 (`/registry/{kind}`); task/reward type lists → Task 10; per-type schemas → Task 11. ✅
- Read structure (map/chapter/object/search/reward-table) → Task 9. ✅
- Create/edit/delete all aspects + dependency + move + save, with live broadcast → Task 12 (move/dependency handlers in Task 8). ✅
- Multi-loader Architectury 1.21.1 NeoForge+Fabric → Tasks 1, 13. ✅
- Loopback + bearer auth, allowRemote, runtime creds, config → Tasks 2, 3, 4, 8, 13. ✅
- Server-thread marshalling → Task 9 (`MinecraftServerExecutor`). ✅
- Error table (503/401/403/404/400/504/500) → Tasks 7, 8, 9. ✅
- Partial-edit PATCH semantics → Task 12 (`editObject` merge) + Task 5 (`JsonMerge`). ✅
- Schema spike + TranslationManager spike (spec §11) → flagged in Tasks 11 and 12. ✅
- Versioning (`/health` protocolVersion) → Tasks 7, 9, 13. ✅
- Testing strategy (unit + dev-server integration) → Tasks 2–8 unit, 9–13 integration. ✅

**Placeholder scan:** The remaining `throw new UnsupportedOperationException("spike")` / "resolved during spike" markers in Task 11 (and the broadcast/Json5-bridge calls in Task 12) are **deliberate, scoped spikes** tied to spec §11 risks — each step says exactly what to resolve and against which source. They are not silent TODOs. All pure-logic tasks (1–8) contain complete, compilable code.

**Type consistency:** `QuestBackend` signatures in Task 7 match their usage in Tasks 8 (handlers), 9–12 (impl). `FtbQuestsBackend` constructor gains `saveMode` in Task 13 (noted in both Task 12 and Task 13). `QuestSerializer.{hex,parseHex,objectSummary,objectFull,writeDataToGson,gsonToJson5}` are referenced consistently across Tasks 9–12 (Task 9 introduces the read helpers; Tasks 11–12 add `gsonToJson5`/`writeDataToGson` — both must live in `QuestSerializer`, extracted as the shared Json5 bridge from the Task 9 spike). `Auth.Result`, `Router.Match`, `JsonResponse`, `RequestContext` names are stable across Tasks 4/6/8.

---

## Notes for Plan 2 (companion plugin)

The frozen HTTP contract (spec §5.5, realized in Tasks 8–12) is what Plan 2's MCP server adapts. Plan 2 can be built and tested against a mock implementing that contract before this mod is finished. Two findings from this plan's spikes must flow into Plan 2's skill docs: (1) the resolved location for titles/descriptions (properties vs `extra`/TranslationManager), and (2) whether per-type `fields` metadata is available or the skill must rely on curated docs + `defaults`.
