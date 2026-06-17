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
