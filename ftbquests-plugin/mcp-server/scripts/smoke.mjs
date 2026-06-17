// Integration smoke test: spawn the built MCP server over stdio (via the real MCP
// SDK client) against an in-process mock bridge, list tools, and call ftbq_health.
// Run: node scripts/smoke.mjs   (after `npm run build`)
import { createServer } from "node:http";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

function mockBridge() {
  return new Promise((resolve) => {
    const srv = createServer((req, res) => {
      const send = (code, obj) => { res.writeHead(code, { "Content-Type": "application/json" }); res.end(JSON.stringify(obj)); };
      if (req.url === "/health") return send(200, { ok: true, questsLoaded: true, protocolVersion: 1, loader: "mock" });
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
  assert(names.length === 15, `expected 15 tools, got ${names.length}`);

  const res = await client.callTool({ name: "ftbq_health", arguments: {} });
  const payload = JSON.parse(res.content[0].text);
  console.log("ftbq_health ->", JSON.stringify(payload));
  assert(payload.protocolVersion === 1, "health protocolVersion !== 1");
  assert(payload.loader === "mock", "health loader !== mock");

  console.log("SMOKE OK");
} finally {
  await client.close().catch(() => {});
  srv.close();
}
