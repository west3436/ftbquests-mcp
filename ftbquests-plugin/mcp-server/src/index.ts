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
    server.registerTool(
      tool.name,
      { description: tool.description, inputSchema: tool.inputSchema },
      async (args: unknown) => (await tool.handler(args)) as any
    );
  }

  await server.connect(new StdioServerTransport());
  process.stderr.write(`[ftbquests] connected to bridge ${target.url} (protocol ${health.protocolVersion})\n`);
}

main().catch((e) => {
  process.stderr.write(`[ftbquests] fatal: ${e?.message ?? e}\n`);
  process.exit(1);
});
