import { z } from "zod";
import type { BridgeTarget } from "./config.js";

export class BridgeError extends Error {
  constructor(public status: number, public type: string, message: string) { super(message); this.name = "BridgeError"; }
}

/** Fields of GET /health that change which tools the MCP server registers. */
export interface BridgeHealth { protocolVersion?: number; readOnly?: boolean; clientAvailable?: boolean; [key: string]: unknown }

export interface RenderRequest {
  kind: "chapter" | "quest";
  id: string;
  zoom?: number;
  centerX?: number;
  centerY?: number;
  fit?: boolean;
  page?: number;
  scroll?: number;
}

// Validated so a malformed bridge reply surfaces as an error instead of a broken image block.
const renderResultSchema = z.object({
  image: z.object({ mimeType: z.literal("image/png"), data: z.string().min(1) }),
  view: z.object({
    kind: z.enum(["chapter", "quest"]),
    id: z.string(),
    chapterId: z.string(),
    width: z.number().int().positive(),
    height: z.number().int().positive(),
  }).passthrough(),
  restored: z.literal(true),
});
export type RenderResult = z.infer<typeof renderResultSchema>;

/** A capture can take up to 12 s on the bridge; leave headroom, but never hang the tool call. */
const REQUEST_TIMEOUT_MS = 30_000;

export class BridgeClient {
  constructor(private target: BridgeTarget, private fetchFn: typeof fetch = fetch) {}

  private async req(method: string, path: string, body?: unknown): Promise<any> {
    let res: Response;
    try {
      res = await this.fetchFn(this.target.url + path, {
        method,
        headers: {
          Authorization: `Bearer ${this.target.token}`,
          ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        },
        body: body !== undefined ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
    } catch (e) {
      if ((e as Error)?.name === "TimeoutError") {
        throw new BridgeError(504, "timeout", `Bridge request ${method} ${path} took longer than ${REQUEST_TIMEOUT_MS / 1000} s`);
      }
      throw e;
    }
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

  health(): Promise<BridgeHealth> { return this.req("GET", "/health"); }
  clientState() { return this.req("GET", "/client/state"); }
  async render(request: RenderRequest): Promise<RenderResult> {
    return renderResultSchema.parse(await this.req("POST", "/client/render", request));
  }
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
