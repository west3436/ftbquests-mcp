package com.ftbqbridge.backend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;

public final class FakeQuestBackend implements QuestBackend {
    public final Map<String, JsonObject> objects = new LinkedHashMap<>();
    public int saveCount = 0;
    private int idSeq = 0x100;

    @Override public JsonObject health() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true); o.addProperty("questsLoaded", true);
        o.addProperty("protocolVersion", com.ftbqbridge.Protocol.PROTOCOL_VERSION);
        return o;
    }
    @Override public JsonArray searchRegistry(String kind, String query, int limit, int offset) {
        JsonArray a = new JsonArray();
        JsonObject e = new JsonObject(); e.addProperty("id", "minecraft:stone"); e.addProperty("displayName", "Stone");
        a.add(e); return a;
    }
    @Override public JsonArray listTaskTypes()   { return typeArray("ftbquests:item"); }
    @Override public JsonArray listRewardTypes() { return typeArray("ftbquests:item"); }
    private JsonArray typeArray(String id) { JsonArray a = new JsonArray(); JsonObject o = new JsonObject(); o.addProperty("typeId", id); a.add(o); return a; }
    @Override public JsonObject typeSchema(String kind, String typeId) { JsonObject o = new JsonObject(); o.addProperty("typeId", typeId); o.add("defaults", new JsonObject()); return o; }
    @Override public JsonObject questMap() { JsonObject o = new JsonObject(); o.add("chapterGroups", new JsonArray()); o.add("rewardTables", new JsonArray()); return o; }
    @Override public JsonObject getChapter(String id) { return require(id); }
    @Override public JsonObject getObject(String id)  { return require(id); }
    @Override public JsonArray searchQuests(String q, String type) { return new JsonArray(); }
    @Override public JsonObject getRewardTable(String id) { return require(id); }
    @Override public JsonObject createObject(String type, String parent, JsonObject properties, JsonObject extra) {
        String id = String.format("%016X", idSeq++);
        JsonObject o = new JsonObject(); o.addProperty("id", id); o.addProperty("type", type); o.addProperty("parent", parent);
        o.add("data", properties == null ? new JsonObject() : properties.deepCopy());
        objects.put(id, o); return o;
    }
    @Override public JsonObject editObject(String id, JsonObject properties) {
        JsonObject o = require(id); o.add("data", properties.deepCopy()); return o;
    }
    @Override public void deleteObject(String id) { if (objects.remove(id) == null) throw ApiException.notFound(id); }
    @Override public JsonObject setDependency(String questId, String dependsOnId, boolean add) {
        JsonObject o = new JsonObject(); o.addProperty("questId", questId); o.addProperty("dependsOnId", dependsOnId); o.addProperty("added", add); return o;
    }
    @Override public void save() { saveCount++; }
    private JsonObject require(String id) { JsonObject o = objects.get(id); if (o == null) throw ApiException.notFound(id); return o; }
}
