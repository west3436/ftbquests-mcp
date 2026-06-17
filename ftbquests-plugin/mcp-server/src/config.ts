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
