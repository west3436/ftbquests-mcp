// Integration smoke test: spawn the built MCP server over stdio (via the real MCP
// SDK client) against an in-process mock bridge, list tools, call ftbq_health, and
// check a render comes back as an MCP image block.
// Run: node scripts/smoke.mjs   (after `npm run build`)
import { createServer } from "node:http";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

function mockBridge() {
  return new Promise((resolve) => {
    const srv = createServer((req, res) => {
      const send = (code, obj) => { res.writeHead(code, { "Content-Type": "application/json" }); res.end(JSON.stringify(obj)); };
      if (req.url === "/health") return send(200, { ok: true, questsLoaded: true, protocolVersion: 1, loader: "mock", clientAvailable: true });
      if (req.url === "/client/render" && req.method === "POST") {
        let body = ""; req.on("data", (c) => (body += c));
        req.on("end", () => {
          const r = JSON.parse(body);
          send(200, { image: { mimeType: "image/png", data: "iVBORw0KGgo=" },
            view: { kind: r.kind, id: r.id, chapterId: r.id, zoom: 16, width: 640, height: 360 }, restored: true });
        });
        return;
      }
      if (req.url === "/task-types") return send(200, [{ typeId: "ftbquests:item" }, { typeId: "ftbquests:checkmark" }]);
      return send(404, { error: { code: 404, type: "not_found", message: "no route" } });
    });
    srv.listen(0, "127.0.0.1", () => resolve({ srv, port: srv.address().port }));
  });
}

function assert(cond, msg) { if (!cond) { console.error("FAIL:", msg); process.exit(1); } }

const { srv, port } = await mockBridge();
const url = `http://127.0.0.1:${port}`;
const transport = new StdioClientTransport({
  command: process.execPath,
  args: ["dist/index.js"],
  env: { ...process.env, FTBQUESTS_BRIDGE_URL: url, FTBQUESTS_BRIDGE_TOKEN: "smoke" },
  stderr: "inherit",
});
const client = new Client({ name: "smoke", version: "1.0.0" });

try {
  await client.connect(transport);

  const { tools } = await client.listTools();
  const names = tools.map((t) => t.name);
  console.log("tools:", names.join(", "));
  assert(names.includes("ftbq_health"), "ftbq_health tool missing");
  assert(names.includes("ftbq_create_object"), "ftbq_create_object tool missing");
  assert(names.includes("ftbq_render_chapter"), "ftbq_render_chapter tool missing (bridge reports clientAvailable)");
  assert(names.length === 18, `expected 18 tools, got ${names.length}`);

  const res = await client.callTool({ name: "ftbq_health", arguments: {} });
  const payload = JSON.parse(res.content[0].text);
  console.log("ftbq_health ->", JSON.stringify(payload));
  assert(payload.protocolVersion === 1, "health protocolVersion !== 1");
  assert(payload.loader === "mock", "health loader !== mock");

  const shot = await client.callTool({ name: "ftbq_render_chapter", arguments: { id: "0000000000000ABC" } });
  const image = shot.content.find((c) => c.type === "image");
  const view = JSON.parse(shot.content.find((c) => c.type === "text").text);
  console.log("ftbq_render_chapter ->", image?.mimeType, JSON.stringify(view));
  assert(!shot.isError && image?.mimeType === "image/png" && image.data === "iVBORw0KGgo=", "render did not return the PNG image block");
  assert(view.kind === "chapter" && view.id === "0000000000000ABC", "render view metadata mismatch");

  console.log("SMOKE OK");
} finally {
  await client.close().catch(() => {});
  srv.close();
}
