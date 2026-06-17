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
