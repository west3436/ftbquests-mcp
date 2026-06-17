# FTB Quests Companion Plugin — Implementation Plan (Plan 2 of 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `ftbquests` Claude Code plugin: a thin TypeScript/Node **MCP server** that adapts MCP tool calls to the bridge mod's HTTP API (Plan 1, spec §5.5), plus a domain **skill** and optional slash commands, packaged for install alongside the mod.

**Architecture:** A `BridgeClient` wraps `fetch` over the frozen HTTP contract (Bearer auth, JSON). `resolveBridgeTarget` discovers the bridge from env or the mod's `runtime.json` creds file. `buildTools(client)` produces SDK-agnostic tool definitions (name, zod input schema, handler) that `index.ts` registers on an `McpServer` over stdio. Everything except `index.ts`'s transport wiring is unit-tested against a real in-process mock HTTP server (mirroring Plan 1's approach), so the plugin can be built and verified **before** the mod is finished.

**Tech Stack:** Node 18+ (global `fetch`), TypeScript (ESM, NodeNext), `@modelcontextprotocol/sdk`, `zod`, `vitest`. Packaged as a Claude Code plugin (`.claude-plugin/plugin.json`, `skills/`, `commands/`, MCP server via `.mcp.json`).

## Global Constraints

- Node **18+**, TypeScript **ESM** (`"type": "module"`, `module`/`moduleResolution` = `NodeNext`), `strict: true`.
- Plugin name: **`ftbquests`**. MCP server name reported to clients: **`ftbquests-bridge`**, version from `package.json`.
- The MCP server adapts the HTTP contract from spec §5.5 / Plan 1 Tasks 8–12 **exactly**. Do not invent endpoints not in that contract.
- Expected bridge protocol version: **`EXPECTED_PROTOCOL = 1`**. On `/health` mismatch, refuse to start with a clear message.
- Bridge discovery precedence: (1) `FTBQUESTS_BRIDGE_URL` + `FTBQUESTS_BRIDGE_TOKEN` env; else (2) `FTBQUESTS_SERVER_DIR` → read `<dir>/config/ftbquests-bridge/runtime.json` → `http://127.0.0.1:<port>` + its token. If neither resolves, exit with a clear error.
- Tool names are prefixed **`ftbq_`**. IDs are 16-char hex strings (passed through verbatim).
- Every tool handler catches `BridgeError` and returns an MCP error result (`isError: true`) carrying `{type, status, message}` — never throws out of the handler.
- A `CommandReward` embeds a server command that runs on claim. The skill MUST instruct the agent to confirm with the user before creating one. The MCP server itself adds no special gating (trust is the bridge's loopback+token).

---

## File Structure

```
ftbquests-plugin/
  .claude-plugin/plugin.json          # plugin manifest
  .mcp.json                           # registers the MCP server (stdio)
  README.md                           # install + usage
  mcp-server/
    package.json  tsconfig.json  vitest.config.ts
    src/
      index.ts          # entrypoint: resolve -> client -> health/protocol check -> register tools -> stdio
      config.ts         # resolveBridgeTarget(env, readFile)
      bridgeClient.ts   # typed fetch wrapper + BridgeError
      protocol.ts       # EXPECTED_PROTOCOL + assertCompatible()
      tools.ts          # buildTools(client) -> ToolDef[]
    test/
      config.test.ts
      bridgeClient.test.ts
      protocol.test.ts
      tools.test.ts
  skills/
    ftbquests/
      SKILL.md
      references/
        data-model.md
        task-types.md
        reward-types.md
        workflows.md
  commands/
    ftbq-status.md
    ftbq-new-chapter.md
```

Tasks 2–5 are fast TDD (no MCP transport, no real bridge). Task 6 wires the entrypoint (verified against the mock bridge / a running mod). Tasks 7–8 are the skill content and plugin packaging (verified by loading in Claude Code).

---

### Task 1: MCP server scaffold (builds + test runner green)

**Files:**
- Create: `ftbquests-plugin/mcp-server/package.json`, `tsconfig.json`, `vitest.config.ts`
- Create: `ftbquests-plugin/mcp-server/src/index.ts` (temporary stub)

**Interfaces:**
- Produces: a buildable ESM TS project; `npm test` runs vitest; `npm run build` emits `dist/`.

- [ ] **Step 1: Write `package.json`**

```json
{
  "name": "ftbquests-mcp-server",
  "version": "1.0.0",
  "type": "module",
  "bin": { "ftbquests-mcp-server": "dist/index.js" },
  "main": "dist/index.js",
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "@modelcontextprotocol/sdk": "^1.0.0",
    "zod": "^3.23.0"
  },
  "devDependencies": {
    "typescript": "^5.5.0",
    "vitest": "^2.0.0",
    "@types/node": "^20.14.0"
  }
}
```

> Pin `@modelcontextprotocol/sdk` to the latest 1.x at implementation time and align the `McpServer`/`registerTool` API in Task 6 with that version.

- [ ] **Step 2: Write `tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "outDir": "dist",
    "rootDir": "src",
    "strict": true,
    "declaration": false,
    "sourceMap": true,
    "esModuleInterop": true,
    "skipLibCheck": true
  },
  "include": ["src/**/*"]
}
```

- [ ] **Step 3: Write `vitest.config.ts`**

```ts
import { defineConfig } from "vitest/config";
export default defineConfig({ test: { include: ["test/**/*.test.ts"], environment: "node" } });
```

- [ ] **Step 4: Write a temporary `src/index.ts` stub**

```ts
export const PLACEHOLDER = true;
```

- [ ] **Step 5: Install + verify**

Run: `cd ftbquests-plugin/mcp-server && npm install && npm run build && npm test`
Expected: install succeeds; `tsc` emits `dist/index.js`; vitest reports "no test files" (or 0 tests) without error.

- [ ] **Step 6: Commit**

```bash
git add ftbquests-plugin/mcp-server/package.json ftbquests-plugin/mcp-server/tsconfig.json ftbquests-plugin/mcp-server/vitest.config.ts ftbquests-plugin/mcp-server/src/index.ts
git commit -m "chore: MCP server TS scaffold (esm, vitest)"
```

---

### Task 2: `resolveBridgeTarget` — bridge discovery

**Files:**
- Create: `ftbquests-plugin/mcp-server/src/config.ts`
- Test: `ftbquests-plugin/mcp-server/test/config.test.ts`

**Interfaces:**
- Produces: `interface BridgeTarget { url: string; token: string }`; `async function resolveBridgeTarget(env: Record<string,string|undefined>, readFile?: (p:string)=>Promise<string>): Promise<BridgeTarget>`. Precedence per Global Constraints. Throws `Error` with a clear message if unresolved.

- [ ] **Step 1: Write the failing test**

```ts
import { describe, it, expect } from "vitest";
import { resolveBridgeTarget } from "../src/config.js";

describe("resolveBridgeTarget", () => {
  it("uses explicit env url+token", async () => {
    const t = await resolveBridgeTarget({ FTBQUESTS_BRIDGE_URL: "http://127.0.0.1:9", FTBQUESTS_BRIDGE_TOKEN: "abc" });
    expect(t).toEqual({ url: "http://127.0.0.1:9", token: "abc" });
  });

  it("reads runtime.json from FTBQUESTS_SERVER_DIR", async () => {
    const fake = async (p: string) => {
      expect(p.replace(/\\/g, "/")).toContain("config/ftbquests-bridge/runtime.json");
      return JSON.stringify({ port: 25599, token: "tok", protocolVersion: 1, boundAddress: "127.0.0.1" });
    };
    const t = await resolveBridgeTarget({ FTBQUESTS_SERVER_DIR: "/srv" }, fake);
    expect(t).toEqual({ url: "http://127.0.0.1:25599", token: "tok" });
  });

  it("throws when nothing resolves", async () => {
    await expect(resolveBridgeTarget({})).rejects.toThrow(/FTBQUESTS_BRIDGE_URL|FTBQUESTS_SERVER_DIR/);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- config`
Expected: FAIL — cannot find `../src/config.js`.

- [ ] **Step 3: Write minimal implementation**

```ts
import { readFile as fsReadFile } from "node:fs/promises";
import { join } from "node:path";

export interface BridgeTarget { url: string; token: string; }

export async function resolveBridgeTarget(
  env: Record<string, string | undefined>,
  readFile: (p: string) => Promise<string> = (p) => fsReadFile(p, "utf8")
): Promise<BridgeTarget> {
  if (env.FTBQUESTS_BRIDGE_URL && env.FTBQUESTS_BRIDGE_TOKEN) {
    return { url: env.FTBQUESTS_BRIDGE_URL, token: env.FTBQUESTS_BRIDGE_TOKEN };
  }
  if (env.FTBQUESTS_SERVER_DIR) {
    const path = join(env.FTBQUESTS_SERVER_DIR, "config", "ftbquests-bridge", "runtime.json");
    const raw = await readFile(path);
    const j = JSON.parse(raw) as { port: number; token: string; boundAddress?: string };
    const host = j.boundAddress && j.boundAddress !== "0.0.0.0" ? j.boundAddress : "127.0.0.1";
    return { url: `http://${host}:${j.port}`, token: j.token };
  }
  throw new Error(
    "Cannot locate FTB Quests bridge. Set FTBQUESTS_BRIDGE_URL + FTBQUESTS_BRIDGE_TOKEN, or FTBQUESTS_SERVER_DIR (containing config/ftbquests-bridge/runtime.json)."
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- config`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add ftbquests-plugin/mcp-server/src/config.ts ftbquests-plugin/mcp-server/test/config.test.ts
git commit -m "feat: bridge target discovery (env + runtime.json)"
```

---

### Task 3: `BridgeClient` — typed HTTP wrapper

**Files:**
- Create: `ftbquests-plugin/mcp-server/src/bridgeClient.ts`
- Test: `ftbquests-plugin/mcp-server/test/bridgeClient.test.ts`

**Interfaces:**
- Consumes: `BridgeTarget`.
- Produces:
  - `class BridgeError extends Error { status: number; type: string }`.
  - `class BridgeClient` (constructor `(target: BridgeTarget, fetchFn?: typeof fetch)`): `health()`, `searchRegistry(kind,query,limit,offset)`, `listTaskTypes()`, `listRewardTypes()`, `typeSchema(kind,id)`, `questMap()`, `getChapter(id)`, `getObject(id)`, `searchQuests(q,type)`, `getRewardTable(id)`, `createObject(type,parent,properties,extra)`, `editObject(id,properties)`, `deleteObject(id)`, `setDependency(questId,dependsOnId,add)`, `move(id,x,y)`, `save()`. Each returns parsed JSON; non-2xx throws `BridgeError` populated from the `{error:{code,type,message}}` body.

- [ ] **Step 1: Write the failing test (against an in-process mock bridge)**

```ts
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { createServer, Server } from "node:http";
import { AddressInfo } from "node:net";
import { BridgeClient, BridgeError } from "../src/bridgeClient.js";

let server: Server; let base: string; let lastAuth: string | undefined;

beforeAll(async () => {
  server = createServer((req, res) => {
    lastAuth = req.headers["authorization"];
    const send = (code: number, obj: unknown) => { res.writeHead(code, { "Content-Type": "application/json" }); res.end(JSON.stringify(obj)); };
    if (req.url === "/health") return send(200, { ok: true, protocolVersion: 1 });
    if (req.url === "/quests/object/DEADBEEF") return send(404, { error: { code: 404, type: "not_found", message: "nope" } });
    if (req.url === "/quests/object" && req.method === "POST") {
      let body = ""; req.on("data", (c) => (body += c));
      req.on("end", () => send(200, { id: "00000000000000AA", echo: JSON.parse(body) }));
      return;
    }
    if (req.url === "/save" && req.method === "POST") return send(200, { ok: true });
    return send(404, { error: { code: 404, type: "not_found", message: "no route" } });
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
afterAll(() => server.close());

describe("BridgeClient", () => {
  it("sends bearer token and parses JSON", async () => {
    const c = new BridgeClient({ url: base, token: "secret" });
    const h = await c.health();
    expect(h.protocolVersion).toBe(1);
    expect(lastAuth).toBe("Bearer secret");
  });

  it("throws BridgeError on non-2xx with type", async () => {
    const c = new BridgeClient({ url: base, token: "secret" });
    await expect(c.getObject("DEADBEEF")).rejects.toMatchObject({ status: 404, type: "not_found" });
    await expect(c.getObject("DEADBEEF")).rejects.toBeInstanceOf(BridgeError);
  });

  it("POSTs create body", async () => {
    const c = new BridgeClient({ url: base, token: "secret" });
    const r = await c.createObject("CHAPTER", "0000000000000001", { title: "Intro" }, {});
    expect(r.id).toBe("00000000000000AA");
    expect(r.echo.type).toBe("CHAPTER");
    expect(r.echo.properties.title).toBe("Intro");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- bridgeClient`
Expected: FAIL — cannot find `../src/bridgeClient.js`.

- [ ] **Step 3: Write minimal implementation**

```ts
import type { BridgeTarget } from "./config.js";

export class BridgeError extends Error {
  constructor(public status: number, public type: string, message: string) { super(message); this.name = "BridgeError"; }
}

export class BridgeClient {
  constructor(private target: BridgeTarget, private fetchFn: typeof fetch = fetch) {}

  private async req(method: string, path: string, body?: unknown): Promise<any> {
    const res = await this.fetchFn(this.target.url + path, {
      method,
      headers: {
        Authorization: `Bearer ${this.target.token}`,
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    const json = text ? JSON.parse(text) : {};
    if (!res.ok) {
      const e = json?.error ?? {};
      throw new BridgeError(res.status, e.type ?? "error", e.message ?? `HTTP ${res.status}`);
    }
    return json;
  }

  private qs(params: Record<string, string | number | undefined>): string {
    const parts = Object.entries(params)
      .filter(([, v]) => v !== undefined && v !== "")
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
    return parts.length ? "?" + parts.join("&") : "";
  }

  health() { return this.req("GET", "/health"); }
  searchRegistry(kind: string, query?: string, limit?: number, offset?: number) {
    return this.req("GET", `/registry/${encodeURIComponent(kind)}` + this.qs({ query, limit, offset }));
  }
  listTaskTypes() { return this.req("GET", "/task-types"); }
  listRewardTypes() { return this.req("GET", "/reward-types"); }
  typeSchema(kind: "task" | "reward", id: string) {
    return this.req("GET", `/${kind === "task" ? "task-types" : "reward-types"}/${encodeURIComponent(id)}/schema`);
  }
  questMap() { return this.req("GET", "/quests"); }
  getChapter(id: string) { return this.req("GET", `/quests/chapter/${encodeURIComponent(id)}`); }
  getObject(id: string) { return this.req("GET", `/quests/object/${encodeURIComponent(id)}`); }
  searchQuests(q?: string, type?: string) { return this.req("GET", "/quests/search" + this.qs({ q, type })); }
  getRewardTable(id: string) { return this.req("GET", `/reward-tables/${encodeURIComponent(id)}`); }
  createObject(type: string, parent: string, properties: object, extra: object) {
    return this.req("POST", "/quests/object", { type, parent, properties, extra });
  }
  editObject(id: string, properties: object) { return this.req("PATCH", `/quests/object/${encodeURIComponent(id)}`, { properties }); }
  deleteObject(id: string) { return this.req("DELETE", `/quests/object/${encodeURIComponent(id)}`); }
  setDependency(questId: string, dependsOnId: string, add: boolean) {
    return this.req("POST", "/quests/dependencies", { questId, dependsOnId, add });
  }
  move(id: string, x: number, y: number) { return this.req("POST", `/quests/object/${encodeURIComponent(id)}/move`, { x, y }); }
  save() { return this.req("POST", "/save"); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- bridgeClient`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add ftbquests-plugin/mcp-server/src/bridgeClient.ts ftbquests-plugin/mcp-server/test/bridgeClient.test.ts
git commit -m "feat: BridgeClient HTTP wrapper + BridgeError"
```

---

### Task 4: `protocol.ts` — version compatibility

**Files:**
- Create: `ftbquests-plugin/mcp-server/src/protocol.ts`
- Test: `ftbquests-plugin/mcp-server/test/protocol.test.ts`

**Interfaces:**
- Produces: `const EXPECTED_PROTOCOL = 1`; `function assertCompatible(serverProtocol: number | undefined): void` — throws a clear `Error` if `serverProtocol !== EXPECTED_PROTOCOL`.

- [ ] **Step 1: Write the failing test**

```ts
import { describe, it, expect } from "vitest";
import { assertCompatible, EXPECTED_PROTOCOL } from "../src/protocol.js";

describe("assertCompatible", () => {
  it("passes on match", () => { expect(() => assertCompatible(EXPECTED_PROTOCOL)).not.toThrow(); });
  it("throws on mismatch", () => { expect(() => assertCompatible(EXPECTED_PROTOCOL + 1)).toThrow(/protocol/i); });
  it("throws on missing", () => { expect(() => assertCompatible(undefined)).toThrow(/protocol/i); });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- protocol`
Expected: FAIL — cannot find `../src/protocol.js`.

- [ ] **Step 3: Write minimal implementation**

```ts
export const EXPECTED_PROTOCOL = 1;

export function assertCompatible(serverProtocol: number | undefined): void {
  if (serverProtocol !== EXPECTED_PROTOCOL) {
    throw new Error(
      `FTB Quests bridge protocol mismatch: expected ${EXPECTED_PROTOCOL}, got ${serverProtocol ?? "unknown"}. ` +
      `Update the mod and/or the companion plugin so their protocol versions match.`
    );
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- protocol`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add ftbquests-plugin/mcp-server/src/protocol.ts ftbquests-plugin/mcp-server/test/protocol.test.ts
git commit -m "feat: protocol compatibility check"
```

---

### Task 5: `buildTools` — SDK-agnostic tool definitions

**Files:**
- Create: `ftbquests-plugin/mcp-server/src/tools.ts`
- Test: `ftbquests-plugin/mcp-server/test/tools.test.ts`

**Interfaces:**
- Consumes: `BridgeClient`, `BridgeError`, `zod`.
- Produces:
  - `interface ToolDef { name: string; description: string; inputSchema: z.ZodRawShape; handler: (args: any) => Promise<ToolResult> }`.
  - `interface ToolResult { content: { type: "text"; text: string }[]; isError?: boolean }`.
  - `function buildTools(client: BridgeClient): ToolDef[]` — one entry per `ftbq_*` tool; each handler calls the matching `BridgeClient` method, returns the JSON result as text, and on `BridgeError` returns `{ isError: true, content: [{type:"text", text: JSON.stringify({error})}] }`.

- [ ] **Step 1: Write the failing test (against a fake client)**

```ts
import { describe, it, expect } from "vitest";
import { buildTools } from "../src/tools.js";
import { BridgeError } from "../src/bridgeClient.js";

function fakeClient(overrides: Record<string, any> = {}) {
  return {
    health: async () => ({ ok: true, protocolVersion: 1 }),
    questMap: async () => ({ chapterGroups: [], rewardTables: [] }),
    createObject: async (type: string, parent: string, properties: any) => ({ id: "00FF", type, properties }),
    getObject: async (_id: string) => { throw new BridgeError(404, "not_found", "nope"); },
    save: async () => ({ ok: true }),
    ...overrides,
  } as any;
}

describe("buildTools", () => {
  it("exposes ftbq_ tools including health, create, save", () => {
    const names = buildTools(fakeClient()).map((t) => t.name);
    expect(names).toContain("ftbq_health");
    expect(names).toContain("ftbq_create_object");
    expect(names).toContain("ftbq_save");
    expect(names.every((n) => n.startsWith("ftbq_"))).toBe(true);
  });

  it("create handler calls client and returns JSON text", async () => {
    const tools = buildTools(fakeClient());
    const create = tools.find((t) => t.name === "ftbq_create_object")!;
    const res = await create.handler({ type: "CHAPTER", parent: "0000000000000001", properties: { title: "X" }, extra: {} });
    expect(res.isError).toBeFalsy();
    const parsed = JSON.parse(res.content[0].text);
    expect(parsed.id).toBe("00FF");
    expect(parsed.type).toBe("CHAPTER");
  });

  it("maps BridgeError to an error result", async () => {
    const tools = buildTools(fakeClient());
    const get = tools.find((t) => t.name === "ftbq_get_object")!;
    const res = await get.handler({ id: "DEADBEEF" });
    expect(res.isError).toBe(true);
    expect(JSON.parse(res.content[0].text).error.type).toBe("not_found");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- tools`
Expected: FAIL — cannot find `../src/tools.js`.

- [ ] **Step 3: Write minimal implementation**

```ts
import { z } from "zod";
import { BridgeClient, BridgeError } from "./bridgeClient.js";

export interface ToolResult { content: { type: "text"; text: string }[]; isError?: boolean; }
export interface ToolDef { name: string; description: string; inputSchema: z.ZodRawShape; handler: (args: any) => Promise<ToolResult>; }

function ok(data: unknown): ToolResult { return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] }; }

function wrap(fn: (args: any) => Promise<unknown>): (args: any) => Promise<ToolResult> {
  return async (args) => {
    try { return ok(await fn(args)); }
    catch (e) {
      if (e instanceof BridgeError) {
        return { isError: true, content: [{ type: "text", text: JSON.stringify({ error: { type: e.type, status: e.status, message: e.message } }, null, 2) }] };
      }
      return { isError: true, content: [{ type: "text", text: JSON.stringify({ error: { type: "client_error", message: String((e as Error)?.message ?? e) } }, null, 2) }] };
    }
  };
}

export function buildTools(client: BridgeClient): ToolDef[] {
  const idSchema = z.string().describe("16-char uppercase hex object id (e.g. 0000000000000001)");
  return [
    { name: "ftbq_health", description: "Check the bridge: questsLoaded, protocol version, loader.", inputSchema: {}, handler: wrap(() => client.health()) },

    { name: "ftbq_search_registry",
      description: "Search a Minecraft registry. kind: items|blocks|fluids|entity_types|mob_effects (and any extended kinds the bridge supports). Returns {id, displayName}. Use to find item/block ids for tasks/rewards.",
      inputSchema: { kind: z.string(), query: z.string().optional(), limit: z.number().int().optional(), offset: z.number().int().optional() },
      handler: wrap((a) => client.searchRegistry(a.kind, a.query, a.limit, a.offset)) },

    { name: "ftbq_list_task_types", description: "List all registered FTB Quests task types (incl. modded/KubeJS).", inputSchema: {}, handler: wrap(() => client.listTaskTypes()) },
    { name: "ftbq_list_reward_types", description: "List all registered FTB Quests reward types (incl. modded/KubeJS).", inputSchema: {}, handler: wrap(() => client.listRewardTypes()) },

    { name: "ftbq_get_type_schema",
      description: "Get the field schema (defaults + best-effort metadata) for a task or reward type. Call before creating a task/reward to learn its properties.",
      inputSchema: { kind: z.enum(["task", "reward"]), typeId: z.string().describe("e.g. ftbquests:item") },
      handler: wrap((a) => client.typeSchema(a.kind, a.typeId)) },

    { name: "ftbq_get_quest_map", description: "Overview of the quest file: chapter groups -> chapters (+counts) and reward tables.", inputSchema: {}, handler: wrap(() => client.questMap()) },
    { name: "ftbq_get_chapter", description: "Full chapter: quests (id, title, x/y/shape) + links.", inputSchema: { id: idSchema }, handler: wrap((a) => client.getChapter(a.id)) },
    { name: "ftbq_get_object", description: "Any quest object by id, including its full data JSON.", inputSchema: { id: idSchema }, handler: wrap((a) => client.getObject(a.id)) },
    { name: "ftbq_search_quests", description: "Search quests by title substring (and optional type).", inputSchema: { q: z.string().optional(), type: z.string().optional() }, handler: wrap((a) => client.searchQuests(a.q, a.type)) },

    { name: "ftbq_create_object",
      description: "Create a quest object and broadcast it live to all clients. type: CHAPTER_GROUP|CHAPTER|QUEST|QUEST_LINK|TASK|REWARD|REWARD_TABLE. parent: container id (top-level=0000000000000001; TASK/REWARD parent=quest id). extra: type/group fields (e.g. {\"group\":\"<id>\"} for a CHAPTER; {\"type\":\"ftbquests:item\"} for a TASK/REWARD). properties: the object's fields (see ftbq_get_type_schema).",
      inputSchema: { type: z.string(), parent: z.string(), properties: z.record(z.any()).optional(), extra: z.record(z.any()).optional() },
      handler: wrap((a) => client.createObject(a.type, a.parent, a.properties ?? {}, a.extra ?? {})) },

    { name: "ftbq_edit_object", description: "Partially edit an object (merge given properties) and broadcast live.", inputSchema: { id: idSchema, properties: z.record(z.any()) }, handler: wrap((a) => client.editObject(a.id, a.properties)) },
    { name: "ftbq_delete_object", description: "Delete an object and broadcast live. Irreversible.", inputSchema: { id: idSchema }, handler: wrap((a) => client.deleteObject(a.id)) },
    { name: "ftbq_set_dependency", description: "Add or remove a dependency: questId depends on dependsOnId.", inputSchema: { questId: idSchema, dependsOnId: idSchema, add: z.boolean() }, handler: wrap((a) => client.setDependency(a.questId, a.dependsOnId, a.add)) },
    { name: "ftbq_move_object", description: "Reposition a quest on the chapter grid.", inputSchema: { id: idSchema, x: z.number(), y: z.number() }, handler: wrap((a) => client.move(a.id, a.x, a.y)) },
    { name: "ftbq_save", description: "Force an immediate save of the quest file to disk.", inputSchema: {}, handler: wrap(() => client.save()) },
  ];
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- tools`
Expected: PASS (3 tests). Then `npm test` → all suites green.

- [ ] **Step 5: Commit**

```bash
git add ftbquests-plugin/mcp-server/src/tools.ts ftbquests-plugin/mcp-server/test/tools.test.ts
git commit -m "feat: ftbq_ tool definitions over BridgeClient"
```

---

### Task 6: `index.ts` — entrypoint wiring (stdio MCP server)

**Files:**
- Modify: `ftbquests-plugin/mcp-server/src/index.ts` (replace the stub)

**Interfaces:**
- Consumes: `resolveBridgeTarget`, `BridgeClient`, `assertCompatible`, `buildTools`, `@modelcontextprotocol/sdk`.
- Produces: a runnable stdio MCP server (`dist/index.js`). On start: resolve target → `health()` → `assertCompatible(health.protocolVersion)` → register every `ToolDef` on an `McpServer` → connect `StdioServerTransport`. Fatal errors log to stderr and exit non-zero.

> Verification is integration (run against the mock bridge from Task 3 or a live mod), not a unit test — `index.ts` only wires already-tested parts to the transport.

- [ ] **Step 1: Write `index.ts`**

```ts
#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { resolveBridgeTarget } from "./config.js";
import { BridgeClient } from "./bridgeClient.js";
import { assertCompatible } from "./protocol.js";
import { buildTools } from "./tools.js";

async function main() {
  const target = await resolveBridgeTarget(process.env);
  const client = new BridgeClient(target);

  // Fail fast on connectivity / protocol problems with a clear message on stderr.
  const health = await client.health().catch((e) => {
    throw new Error(`Cannot reach FTB Quests bridge at ${target.url}: ${e?.message ?? e}`);
  });
  assertCompatible(health?.protocolVersion);

  const server = new McpServer({ name: "ftbquests-bridge", version: "1.0.0" });
  for (const tool of buildTools(client)) {
    // Align this call with the installed @modelcontextprotocol/sdk version.
    server.registerTool(
      tool.name,
      { description: tool.description, inputSchema: tool.inputSchema },
      async (args: unknown) => tool.handler(args)
    );
  }

  await server.connect(new StdioServerTransport());
  process.stderr.write(`[ftbquests] connected to bridge ${target.url} (protocol ${health.protocolVersion})\n`);
}

main().catch((e) => {
  process.stderr.write(`[ftbquests] fatal: ${e?.message ?? e}\n`);
  process.exit(1);
});
```

> **SDK API note:** if the pinned SDK uses `server.tool(name, description, zodShape, handler)` instead of `registerTool(name, {description, inputSchema}, handler)`, adapt the loop accordingly (the `ToolDef.inputSchema` is a zod raw shape, compatible with both). Verify with the version installed in Task 1.

- [ ] **Step 2: Build**

Run: `npm run build`
Expected: `dist/index.js` emitted, no type errors.

- [ ] **Step 3: Integration smoke test against a mock bridge**

Start a one-off mock bridge (a tiny node script returning `{ok:true,protocolVersion:1}` for `/health` and a stub `/task-types`), then:

```bash
FTBQUESTS_BRIDGE_URL=http://127.0.0.1:8765 FTBQUESTS_BRIDGE_TOKEN=dev node dist/index.js
```
Expected: stderr prints `connected to bridge ... (protocol 1)` and the process stays alive on stdio. Optionally exercise it with the MCP Inspector (`npx @modelcontextprotocol/inspector node dist/index.js`) and confirm the `ftbq_*` tools list and that `ftbq_health` returns the mock JSON. Against a **real** running mod, set `FTBQUESTS_SERVER_DIR=<server dir>` instead and confirm `ftbq_get_quest_map` returns live chapters.

- [ ] **Step 4: Commit**

```bash
git add ftbquests-plugin/mcp-server/src/index.ts
git commit -m "feat: MCP stdio entrypoint with health+protocol gate"
```

---

### Task 7: The `ftbquests` skill (domain knowledge + workflows)

**Files:**
- Create: `ftbquests-plugin/skills/ftbquests/SKILL.md`
- Create: `ftbquests-plugin/skills/ftbquests/references/data-model.md`
- Create: `ftbquests-plugin/skills/ftbquests/references/task-types.md`
- Create: `ftbquests-plugin/skills/ftbquests/references/reward-types.md`
- Create: `ftbquests-plugin/skills/ftbquests/references/workflows.md`

**Interfaces:**
- Produces: a skill that activates when the user works on FTB Quests, teaching the data model and the correct use of the `ftbq_*` tools.

> Verification is review + an example transcript (no automated test). Keep `SKILL.md` lean; push detail into `references/`.

- [ ] **Step 1: Write `SKILL.md`**

```markdown
---
name: ftbquests
description: Use when authoring or editing FTB Quests content on a running Minecraft server via the ftbquests-bridge MCP tools — creating/editing chapters, quests, tasks, rewards, reward tables, dependencies, and querying blocks/items/quest types.
---

# Authoring FTB Quests

You edit a **live** server through the `ftbq_*` MCP tools. Every create/edit/delete is broadcast to connected players immediately — an admin watching in-game sees your changes in real time.

## Orientation (do this first)
1. `ftbq_health` — confirm `questsLoaded: true`.
2. `ftbq_get_quest_map` — see chapter groups → chapters and reward tables.
3. Drill in with `ftbq_get_chapter` / `ftbq_get_object`.

## Core model (see references/data-model.md)
- Hierarchy: **chapter group → chapter → quest → (tasks + rewards)**; plus **reward tables** and **quest links**.
- **IDs are 16-char uppercase hex** strings. Top-level container id is `0000000000000001`.
- Create with `ftbq_create_object`: `type` ∈ CHAPTER_GROUP|CHAPTER|QUEST|QUEST_LINK|TASK|REWARD|REWARD_TABLE.
  - CHAPTER: put the group id in `extra.group`.
  - QUEST: `parent` = chapter id.
  - TASK / REWARD: `parent` = quest id, and `extra.type` = the type id (e.g. `ftbquests:item`).

## Before creating a task or reward
Call `ftbq_get_type_schema` for that type to learn its fields. The schema's `defaults` are authoritative (pack-aware, live). The curated notes in references/ are a convenience, not a substitute. To find item/block ids, use `ftbq_search_registry`.

## Discipline
- Build in order: chapter group → chapter → quests → tasks/rewards → dependencies (`ftbq_set_dependency`) → positions (`ftbq_move_object`).
- After a batch of edits, call `ftbq_save`.
- Errors come back as `{error:{type,status,message}}` — read `message` and correct your input (e.g. wrong `parent`, unknown `type`, bad field).

## Safety
A **command reward** (`ftbquests:command`) runs a server command when a player claims it. **Confirm with the user before creating a command reward** and show them the exact command.

See: references/data-model.md, references/task-types.md, references/reward-types.md, references/workflows.md.
```

- [ ] **Step 2: Write `references/data-model.md`**

Document (from spec §3): the object hierarchy and which objects are containers vs leaves; the hex-id scheme and `0000000000000001` top-level convention; the meaning of `extra.group` (chapter) and `extra.type` (task/reward); dependencies as an array of hex ids (and `#tag` references); quest layout fields `x`, `y`, `size`, `shape`; that titles/descriptions may route through `extra` on create (carry the resolved answer from Plan 1 Task 12 spike). Note that `ftbq_get_object` returns the live `data` JSON, which is the ground truth for any field.

- [ ] **Step 3: Write `references/task-types.md`**

List the 14 built-in task types with a one-line purpose and key fields, and the instruction to confirm fields via `ftbq_get_type_schema`:

```markdown
# Task types (confirm fields with ftbq_get_type_schema task <id>)

- ftbquests:item — submit/hold item(s). Key fields: item (id), count.
- ftbquests:checkmark — manual checkbox; title only.
- ftbquests:kill — kill N of an entity. Fields: entity, value.
- ftbquests:dimension — enter a dimension. Field: dimension.
- ftbquests:advancement — earn an advancement. Field: advancement.
- ftbquests:stat — reach a stat value. Fields: stat, value.
- ftbquests:observation — look at/observe a target.
- ftbquests:location — reach an area (dimension + coords/size).
- ftbquests:biome — enter a biome. Field: biome.
- ftbquests:structure — enter a structure. Field: structure.
- ftbquests:fluid — collect fluid. Fields: fluid, amount.
- ftbquests:xp — accumulate XP. Fields: value, points/levels.
- ftbquests:gamestage — requires a game stage (integration mod).
- ftbquests:custom — script-driven (KubeJS/other mods).
- (ftbquests:energy — present only when a compatible energy mod is installed.)
```

- [ ] **Step 4: Write `references/reward-types.md`**

List the 13 built-in reward types similarly, flagging `command`:

```markdown
# Reward types (confirm fields with ftbq_get_type_schema reward <id>)

- ftbquests:item — give item(s). Fields: item, count.
- ftbquests:xp — give XP points. Field: xp.
- ftbquests:xp_levels — give XP levels. Field: xp_levels.
- ftbquests:choice — choose one of several item rewards.
- ftbquests:loot — roll a loot/reward table.
- ftbquests:random — random reward from a table.
- ftbquests:all_table — grant all entries of a reward table.
- ftbquests:advancement — grant an advancement.
- ftbquests:toast — show a toast (cosmetic).
- ftbquests:gamestage — grant a game stage.
- ftbquests:currency — grant currency (not available by default; integration).
- ftbquests:custom — script-driven (KubeJS/other mods).
- ftbquests:command — ⚠️ RUNS A SERVER COMMAND on claim. Field: command. CONFIRM WITH THE USER before creating; show the exact command.
```

- [ ] **Step 5: Write `references/workflows.md`**

Provide concrete, copyable tool-call sequences: (a) "Build a progression chapter" (create group → chapter → 3 quests → item tasks → item rewards → chain dependencies → positions → save); (b) "Import an item list as collection tasks" (loop `ftbq_search_registry` to resolve ids, then a quest with N item tasks); (c) "Create and attach a reward table" (create REWARD_TABLE → add weighted rewards → attach via a `loot`/`random` reward on a quest). Each step names the exact tool and the JSON shape, and ends with `ftbq_save`.

- [ ] **Step 6: Commit**

```bash
git add ftbquests-plugin/skills/
git commit -m "docs: ftbquests skill (model, task/reward types, workflows)"
```

---

### Task 8: Plugin packaging (manifest, MCP registration, commands, README)

**Files:**
- Create: `ftbquests-plugin/.claude-plugin/plugin.json`
- Create: `ftbquests-plugin/.mcp.json`
- Create: `ftbquests-plugin/commands/ftbq-status.md`
- Create: `ftbquests-plugin/commands/ftbq-new-chapter.md`
- Create: `ftbquests-plugin/README.md`

**Interfaces:**
- Produces: an installable Claude Code plugin that registers the MCP server and ships the skill + commands.

> Verification is integration: install the plugin in Claude Code, confirm the skill is listed, the `ftbq_*` tools appear, and `/ftbq-status` works against a running mod.

- [ ] **Step 1: Write `plugin.json`**

```json
{
  "name": "ftbquests",
  "version": "1.0.0",
  "description": "Author and edit FTB Quests on a live Minecraft server via the ftbquests-bridge mod.",
  "author": { "name": "west3436" }
}
```

- [ ] **Step 2: Write `.mcp.json`**

```json
{
  "mcpServers": {
    "ftbquests-bridge": {
      "command": "node",
      "args": ["${CLAUDE_PLUGIN_ROOT}/mcp-server/dist/index.js"],
      "env": {
        "FTBQUESTS_SERVER_DIR": "${FTBQUESTS_SERVER_DIR}"
      }
    }
  }
}
```

> The MCP server must be built (`cd mcp-server && npm install && npm run build`) before first use — document this in the README. `${CLAUDE_PLUGIN_ROOT}` resolves to the plugin dir. The user sets `FTBQUESTS_SERVER_DIR` to their server directory (the one containing `config/ftbquests-bridge/runtime.json`), or instead sets `FTBQUESTS_BRIDGE_URL` + `FTBQUESTS_BRIDGE_TOKEN`. Confirm the exact MCP-in-plugin registration mechanism against the current Claude Code plugin docs; if MCP servers must be declared inside `plugin.json` rather than a separate `.mcp.json`, move the `mcpServers` block there.

- [ ] **Step 3: Write the slash commands**

`commands/ftbq-status.md`:

```markdown
---
description: Show FTB Quests bridge status and a quest overview.
---

Call `ftbq_health`, then `ftbq_get_quest_map`. Summarize: is the quest file loaded, the bridge protocol/loader, and the list of chapter groups → chapters with quest counts and reward tables.
```

`commands/ftbq-new-chapter.md`:

```markdown
---
description: Scaffold a new FTB Quests chapter (guided).
argument-hint: <chapter title> [in group <group id>]
---

Create a new chapter titled "$ARGUMENTS".
1. If no group id is given, call `ftbq_get_quest_map` and ask which chapter group to use (or offer to create one with `ftbq_create_object` type CHAPTER_GROUP).
2. Create the chapter with `ftbq_create_object` type CHAPTER, parent `0000000000000001`, `extra.group` = the chosen group id, `properties.title` = the title.
3. Report the new chapter id and call `ftbq_save`.
```

- [ ] **Step 4: Write `README.md`**

Document install/use: (1) put `ftbquests-bridge` mod in the server's `mods/` (NeoForge or Fabric, 1.21.1) alongside FTB Quests + FTB Library + Architectury API; start the server once to generate `config/ftbquests-bridge/runtime.json`. (2) In the plugin's `mcp-server/`, run `npm install && npm run build`. (3) Install this plugin in Claude Code; set `FTBQUESTS_SERVER_DIR` to the server directory (or `FTBQUESTS_BRIDGE_URL`/`FTBQUESTS_BRIDGE_TOKEN`). (4) For the SSH'd-admin scenario, run Claude Code on the server host so the loopback bridge is reachable and the creds file is local. (5) Verify with `/ftbq-status`. Include the security note (loopback-only by default; `allowRemote` opt-in) and the `CommandReward` warning.

- [ ] **Step 5: Integration verification**

With a running mod (or the mock bridge) and `FTBQUESTS_SERVER_DIR` set: install the plugin, then in Claude Code confirm the `ftbquests` skill is listed, the `ftbq_*` tools are available, and `/ftbq-status` returns health + the quest map.

- [ ] **Step 6: Commit**

```bash
git add ftbquests-plugin/.claude-plugin/ ftbquests-plugin/.mcp.json ftbquests-plugin/commands/ ftbquests-plugin/README.md
git commit -m "feat: package ftbquests Claude Code plugin (manifest, mcp, commands, readme)"
```

---

## Self-Review

**Spec coverage (companion side, spec §6):**
- MCP server adapting MCP→HTTP → Tasks 3, 5, 6. ✅
- Bridge discovery via creds file or env → Task 2. ✅
- Protocol compatibility check → Tasks 4, 6. ✅
- Full `ftbq_*` tool set (health, registry search, type lists, type schema, quest map, chapter/object/search, create/edit/delete, dependency, move, save) → Task 5. ✅
- Skill with model + curated per-type docs + workflows + safety → Task 7. ✅
- Plugin packaging (manifest, MCP registration, slash commands, README, install for the SSH scenario) → Task 8. ✅
- `CommandReward` confirmation guidance → Tasks 5 (description), 7 (SKILL.md + reward-types.md). ✅

**Placeholder scan:** Tasks 2–6 contain complete, compilable code with real tests. Tasks 7–8 are content/packaging tasks: each step says exactly what each file must contain; the longer reference docs (Task 7 Steps 2/5, Task 8 Step 4) are specified by content rather than verbatim prose to avoid bloat, which is appropriate for documentation deliverables (not code). Two items intentionally defer to verification rather than guessing: the exact `@modelcontextprotocol/sdk` tool-registration call (Task 6, pin in Task 1) and the exact MCP-in-plugin registration mechanism (Task 8) — both flagged with how to confirm.

**Type consistency:** `BridgeTarget` (Task 2) is consumed by `BridgeClient` (Task 3). `BridgeClient` method names used in `tools.ts` (Task 5) match Task 3's definitions exactly (`searchRegistry, listTaskTypes, listRewardTypes, typeSchema, questMap, getChapter, getObject, searchQuests, getRewardTable, createObject, editObject, deleteObject, setDependency, move, save`). `ToolDef`/`ToolResult` (Task 5) are consumed by `index.ts` (Task 6). `assertCompatible`/`EXPECTED_PROTOCOL` (Task 4) used in Task 6. `BridgeError` (Task 3) handled in Task 5. Tool names are stable between Task 5 definitions and the commands referencing them in Task 8.

---

## Cross-plan dependency

This plan adapts the HTTP contract realized by **Plan 1 (the mod)**. It can be built and unit-tested entirely against the in-process mock bridge (Task 3) and the fake client (Task 5) before the mod exists; only the Task 6/8 *integration* checks need a running mod. Carry the two Plan 1 spike resolutions into the skill docs (Task 7): the title/description field location, and whether per-type `fields` metadata is available beyond `defaults`.
```