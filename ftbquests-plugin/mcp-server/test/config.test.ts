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
