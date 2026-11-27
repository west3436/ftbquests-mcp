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
    if (req.url === "/client/render" && req.method === "POST") {
      let body = ""; req.on("data", (c) => (body += c));
      req.on("end", () => {
        const r = JSON.parse(body);
        if (r.id === "BADBADBADBADBAD0") return send(200, { image: { mimeType: "image/png" }, view: {}, restored: true });
        send(200, { image: { mimeType: "image/png", data: "iVBORw0KGgo=" },
          view: { kind: r.kind, id: r.id, chapterId: r.id, zoom: 16, width: 640, height: 360 }, restored: true });
      });
      return;
    }
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

  it("POSTs a render request and returns the validated capture", async () => {
    const c = new BridgeClient({ url: base, token: "secret" });
    const r = await c.render({ kind: "chapter", id: "0000000000000ABC", zoom: 12 });
    expect(r.image.mimeType).toBe("image/png");
    expect(r.view).toMatchObject({ kind: "chapter", id: "0000000000000ABC", width: 640 });
  });

  it("rejects a malformed render reply instead of passing a broken image on", async () => {
    const c = new BridgeClient({ url: base, token: "secret" });
    await expect(c.render({ kind: "quest", id: "BADBADBADBADBAD0" })).rejects.toThrow();
  });
});
