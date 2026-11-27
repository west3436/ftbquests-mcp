import { z } from "zod";
import { BridgeClient, BridgeError, type BridgeHealth, type RenderResult } from "./bridgeClient.js";

export type ToolContent = { type: "text"; text: string } | { type: "image"; mimeType: string; data: string };
export interface ToolResult { content: ToolContent[]; isError?: boolean; }
export interface ToolDef { name: string; description: string; inputSchema: z.ZodRawShape; handler: (args: any) => Promise<ToolResult>; }

function ok(data: unknown): ToolResult { return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] }; }

/** A screenshot comes back as an MCP image block, with the view metadata (zoom, scroll, page count...) as text. */
function rendered(result: RenderResult): ToolResult {
  return {
    content: [
      { type: "image", mimeType: result.image.mimeType, data: result.image.data },
      { type: "text", text: JSON.stringify(result.view, null, 2) },
    ],
  };
}

function wrap<T>(fn: (args: any) => Promise<T>, format: (data: T) => ToolResult = ok): (args: any) => Promise<ToolResult> {
  return async (args) => {
    try { return format(await fn(args)); }
    catch (e) {
      if (e instanceof BridgeError) {
        return { isError: true, content: [{ type: "text", text: JSON.stringify({ error: { type: e.type, status: e.status, message: e.message } }, null, 2) }] };
      }
      return { isError: true, content: [{ type: "text", text: JSON.stringify({ error: { type: "client_error", message: String((e as Error)?.message ?? e) } }, null, 2) }] };
    }
  };
}

/**
 * Tools depend on what the bridge reported at startup: the screenshot tools only exist when it runs inside a
 * game client that can draw the quest book (`clientAvailable`), and the edit tools disappear when the admin
 * set `readOnly`. A dedicated server therefore exposes exactly the tools it can serve.
 */
export function buildTools(client: BridgeClient, health: BridgeHealth = {}): ToolDef[] {
  const idSchema = z.string().describe("16-char uppercase hex object id (e.g. 0000000000000001)");
  const readTools: ToolDef[] = [
    { name: "ftbq_health", description: "Check the bridge: questsLoaded, protocol version, loader, readOnly, clientAvailable (screenshots).", inputSchema: {}, handler: wrap(() => client.health()) },

    { name: "ftbq_search_registry",
      description: "Search a Minecraft registry. kind: items|blocks|fluids|entity_types|mob_effects|biomes|structures|dimensions|stats|advancements. Returns {id, displayName} (displayName is localized where available, else the id). Use to find ids for tasks/rewards.",
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
  ];

  const visualTools: ToolDef[] = [
    { name: "ftbq_client_state",
      description: "Whether the game client can take quest screenshots right now (world loaded, quests synced), plus its current screen and window. Check this when a render fails.",
      inputSchema: {}, handler: wrap(() => client.clientState()) },
    { name: "ftbq_render_chapter",
      description: "Screenshot a chapter exactly as the in-game quest book draws it (PNG + view metadata). With no options it fits the whole chapter; pass zoom and/or centerX+centerY to look closer. Use after placing or editing quests to check spacing, overlaps, dependency lines and icons. Briefly swaps the player's screen, then restores it.",
      inputSchema: {
        id: z.string().describe("Chapter id (16-char hex)"),
        zoom: z.number().int().min(4).max(28).optional().describe("Quest icon size, 4-28 (in-game default 16)"),
        centerX: z.number().optional().describe("Quest-grid x to center on (requires centerY)"),
        centerY: z.number().optional().describe("Quest-grid y to center on (requires centerX)"),
      },
      handler: wrap((a) => client.render({ kind: "chapter", ...a }), rendered) },
    { name: "ftbq_render_quest",
      description: "Screenshot a quest's in-game detail panel: title, description, tasks, rewards (PNG + metadata incl. pageCount and maxScroll). Use to check how description text and formatting actually render.",
      inputSchema: {
        id: z.string().describe("Quest id (16-char hex)"),
        page: z.number().int().min(0).optional().describe("Description page, 0-based"),
        scroll: z.number().min(0).optional().describe("Scroll offset within the page, in GUI pixels"),
      },
      handler: wrap((a) => client.render({ kind: "quest", ...a }), rendered) },
  ];

  const writeTools: ToolDef[] = [
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

  return [
    ...readTools,
    ...(health.clientAvailable === true ? visualTools : []),
    ...(health.readOnly === true ? [] : writeTools),
  ];
}
