package com.ftbqbridge.http;

import com.ftbqbridge.Protocol;
import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.backend.QuestBackend;
import com.google.gson.JsonObject;

public final class BridgeHandlers {
    private BridgeHandlers() {}

    public static void register(Router r, QuestBackend b) {
        r.add("GET", "/health", ctx -> JsonResponse.ok(b.health()));

        r.add("GET", "/registry/{kind}", ctx -> JsonResponse.ok(b.searchRegistry(
                ctx.param("kind"), ctx.queryOr("query", ""),
                parseInt(ctx.queryOr("limit", "50"), 50), parseInt(ctx.queryOr("offset", "0"), 0))));

        r.add("GET", "/task-types",   ctx -> JsonResponse.ok(b.listTaskTypes()));
        r.add("GET", "/reward-types", ctx -> JsonResponse.ok(b.listRewardTypes()));
        r.add("GET", "/task-types/{id}/schema",   ctx -> JsonResponse.ok(b.typeSchema("task",  ctx.param("id"))));
        r.add("GET", "/reward-types/{id}/schema", ctx -> JsonResponse.ok(b.typeSchema("reward", ctx.param("id"))));

        r.add("GET", "/quests", ctx -> JsonResponse.ok(b.questMap()));
        r.add("GET", "/quests/chapter/{id}", ctx -> JsonResponse.ok(b.getChapter(ctx.param("id"))));
        r.add("GET", "/quests/object/{id}",  ctx -> JsonResponse.ok(b.getObject(ctx.param("id"))));
        r.add("GET", "/quests/search", ctx -> JsonResponse.ok(b.searchQuests(ctx.queryOr("q", ""), ctx.queryOr("type", ""))));
        r.add("GET", "/reward-tables/{id}", ctx -> JsonResponse.ok(b.getRewardTable(ctx.param("id"))));

        r.add("POST", "/quests/object", ctx -> {
            JsonObject body = requireBody(ctx);
            return JsonResponse.ok(b.createObject(
                str(body, "type"), str(body, "parent"),
                obj(body, "properties"), obj(body, "extra")));
        });
        r.add("PATCH", "/quests/object/{id}", ctx -> {
            JsonObject body = requireBody(ctx);
            return JsonResponse.ok(b.editObject(ctx.param("id"), obj(body, "properties")));
        });
        r.add("DELETE", "/quests/object/{id}", ctx -> { b.deleteObject(ctx.param("id")); return ok(); });
        r.add("POST", "/quests/object/{id}/move", ctx -> {
            JsonObject body = requireBody(ctx);
            JsonObject props = new JsonObject();
            props.add("x", body.get("x")); props.add("y", body.get("y"));
            return JsonResponse.ok(b.editObject(ctx.param("id"), props));
        });
        r.add("POST", "/quests/dependencies", ctx -> {
            JsonObject body = requireBody(ctx);
            return JsonResponse.ok(b.setDependency(str(body, "questId"), str(body, "dependsOnId"),
                body.has("add") && body.get("add").getAsBoolean()));
        });
        r.add("POST", "/save", ctx -> { b.save(); return ok(); });
    }

    private static JsonResponse ok() { JsonObject o = new JsonObject(); o.addProperty("ok", true); return JsonResponse.ok(o); }
    private static JsonObject requireBody(RequestContext ctx) {
        if (ctx.body() == null) throw ApiException.badRequest("JSON body required");
        return ctx.body();
    }
    private static String str(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) throw ApiException.badRequest("missing field: " + k);
        return o.get(k).getAsString();
    }
    private static JsonObject obj(JsonObject o, String k) {
        return (o.has(k) && o.get(k).isJsonObject()) ? o.getAsJsonObject(k) : new JsonObject();
    }
    private static int parseInt(String s, int def) { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }
}
