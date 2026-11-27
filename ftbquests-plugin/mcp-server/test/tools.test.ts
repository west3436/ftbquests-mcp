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
    clientState: async () => ({ ready: true }),
    render: async (r: any) => ({
      image: { mimeType: "image/png", data: "iVBORw0KGgo=" },
      view: { kind: r.kind, id: r.id, chapterId: r.id, zoom: 16, width: 640, height: 360, request: r },
      restored: true,
    }),
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

  const visualNames = ["ftbq_client_state", "ftbq_render_chapter", "ftbq_render_quest"];
  const writeNames = ["ftbq_create_object", "ftbq_edit_object", "ftbq_delete_object", "ftbq_set_dependency", "ftbq_move_object", "ftbq_save"];

  it("registers screenshot tools only when the bridge runs in a game client", () => {
    for (const health of [{}, { clientAvailable: false }]) {
      const names = buildTools(fakeClient(), health).map((t) => t.name);
      for (const n of visualNames) expect(names).not.toContain(n);
    }
    const names = buildTools(fakeClient(), { clientAvailable: true }).map((t) => t.name);
    for (const n of visualNames) expect(names).toContain(n);
    for (const n of writeNames) expect(names).toContain(n);
  });

  it("drops every edit tool on a read-only bridge but keeps reads and screenshots", () => {
    const names = buildTools(fakeClient(), { readOnly: true, clientAvailable: true }).map((t) => t.name);
    for (const n of writeNames) expect(names).not.toContain(n);
    expect(names).toContain("ftbq_get_chapter");
    expect(names).toContain("ftbq_render_chapter");
  });

  it("returns a render as an image block plus view metadata", async () => {
    const render = buildTools(fakeClient(), { clientAvailable: true }).find((t) => t.name === "ftbq_render_chapter")!;
    const res = await render.handler({ id: "0000000000000ABC", zoom: 20 });
    expect(res.isError).toBeFalsy();
    expect(res.content[0]).toEqual({ type: "image", mimeType: "image/png", data: "iVBORw0KGgo=" });
    const view = JSON.parse((res.content[1] as any).text);
    expect(view.request).toEqual({ kind: "chapter", id: "0000000000000ABC", zoom: 20 });
    expect(JSON.stringify(view)).not.toContain("iVBOR"); // the PNG is not duplicated into the text
  });

  it("sends quest renders with kind quest", async () => {
    const render = buildTools(fakeClient(), { clientAvailable: true }).find((t) => t.name === "ftbq_render_quest")!;
    const res = await render.handler({ id: "0000000000000DEF", page: 1 });
    expect(JSON.parse((res.content[1] as any).text).request).toEqual({ kind: "quest", id: "0000000000000DEF", page: 1 });
  });
});
