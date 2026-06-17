import { z } from "zod";
import { BridgeClient, BridgeError } from "./bridgeClient.js";

export interface ToolResult { content: { type: "text"; text: string }[]; isError?: boolean; }
export interface ToolDef { name: string; description: string; inputSchema: z.ZodRawShape; handler: (args: any) => Promise<ToolResult>; }

function ok(data: unknown): ToolResult { return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] }; }

function wrap(fn: (args: any) => Promise<unknown>): (args: any) => Promise<ToolResult> {
  return async (args) => {
    try { return ok(await fn(args)); }
    catch (e) {
      if (e instanceof BridgeError) {
        return { isError: true, content: [{ type: "text", text: JSON.stringify({ error: { type: e.type, status: e.status, message: e.message } }, null, 2) }] };
      }
      return { isError: true, content: [{ type: "text", text: JSON.stringify({ error: { type: "client_error", message: String((e as Error)?.message ?? e) } }, null, 2) }] };
    }
  };
}

export function buildTools(client: BridgeClient): ToolDef[] {
  const idSchema = z.string().describe("16-char uppercase hex object id (e.g. 0000000000000001)");
  return [
    { name: "ftbq_health", description: "Check the bridge: questsLoaded, protocol version, loader.", inputSchema: {}, handler: wrap(() => client.health()) },

    { name: "ftbq_search_registry",
      description: "Search a Minecraft registry. kind: items|blocks|fluids|entity_types|mob_effects (and any extended kinds the bridge supports). Returns {id, displayName}. Use to find item/block ids for tasks/rewards.",
      inputSchema: { kind: z.string(), query: z.string().optional(), limit: z.number().int().optional(), offset: z.number().int().optional() },
      handler: wrap((a) => client.searchRegistry(a.kind, a.query, a.limit, a.offset)) },

    { name: "ftbq_list_task_types", description: "List all registered FTB Quests task types (incl. modded/KubeJS).", inputSchema: {}, handler: wrap(() => client.listTaskTypes()) },
    { name: "ftbq_list_reward_types", description: "List all registered FTB Quests reward types (incl. modded/KubeJS).", inputSchema: {}, handler: wrap(() => client.listRewardTypes()) },

    { name: "ftbq_get_type_schema",
      description: "Get the field schema (defaults + best-effort metadata) for a task or reward type. Call before creating a task/reward to learn its properties.",
      inputSchema: { kind: z.enum(["task", "reward"]), typeId: z.string().describe("e.g. ftbquests:item") },
      handler: wrap((a) => client.typeSchema(a.kind, a.typeId)) },

    { name: "ftbq_get_quest_map", description: "Overview of the quest file: chapter groups -> chapters (+counts) and reward tables.", inputSchema: {}, handler: wrap(() => client.questMap()) },
    { name: "ftbq_get_chapter", description: "Full chapter: quests (id, title, x/y/shape) + links.", inputSchema: { id: idSchema }, handler: wrap((a) => client.getChapter(a.id)) },
    { name: "ftbq_get_object", description: "Any quest object by id, including its full data JSON.", inputSchema: { id: idSchema }, handler: wrap((a) => client.getObject(a.id)) },
    { name: "ftbq_search_quests", description: "Search quests by title substring (and optional type).", inputSchema: { q: z.string().optional(), type: z.string().optional() }, handler: wrap((a) => client.searchQuests(a.q, a.type)) },

    { name: "ftbq_create_object",
      description: "Create a quest object and broadcast it live to all clients. type: CHAPTER_GROUP|CHAPTER|QUEST|QUEST_LINK|TASK|REWARD|REWARD_TABLE. parent: container id (top-level=0000000000000001; TASK/REWARD parent=quest id). extra: type/group fields (e.g. {\"group\":\"<id>\"} for a CHAPTER; {\"type\":\"ftbquests:item\"} for a TASK/REWARD). properties: the object's fields (see ftbq_get_type_schema).",
      inputSchema: { type: z.string(), parent: z.string(), properties: z.record(z.any()).optional(), extra: z.record(z.any()).optional() },
      handler: wrap((a) => client.createObject(a.type, a.parent, a.properties ?? {}, a.extra ?? {})) },

    { name: "ftbq_edit_object", description: "Partially edit an object (merge given properties) and broadcast live.", inputSchema: { id: idSchema, properties: z.record(z.any()) }, handler: wrap((a) => client.editObject(a.id, a.properties)) },
    { name: "ftbq_delete_object", description: "Delete an object and broadcast live. Irreversible.", inputSchema: { id: idSchema }, handler: wrap((a) => client.deleteObject(a.id)) },
    { name: "ftbq_set_dependency", description: "Add or remove a dependency: questId depends on dependsOnId.", inputSchema: { questId: idSchema, dependsOnId: idSchema, add: z.boolean() }, handler: wrap((a) => client.setDependency(a.questId, a.dependsOnId, a.add)) },
    { name: "ftbq_move_object", description: "Reposition a quest on the chapter grid.", inputSchema: { id: idSchema, x: z.number(), y: z.number() }, handler: wrap((a) => client.move(a.id, a.x, a.y)) },
    { name: "ftbq_save", description: "Force an immediate save of the quest file to disk.", inputSchema: {}, handler: wrap(() => client.save()) },
  ];
}
