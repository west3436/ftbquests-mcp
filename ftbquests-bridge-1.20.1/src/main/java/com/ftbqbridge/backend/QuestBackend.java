package com.ftbqbridge.backend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public interface QuestBackend {
    JsonObject health();
    JsonArray  searchRegistry(String kind, String query, int limit, int offset);
    JsonArray  listTaskTypes();
    JsonArray  listRewardTypes();
    JsonObject typeSchema(String kind, String typeId);
    JsonObject questMap();
    JsonObject getChapter(String id);
    JsonObject getObject(String id);
    JsonArray  searchQuests(String q, String type);
    JsonObject getRewardTable(String id);
    JsonObject createObject(String type, String parent, JsonObject properties, JsonObject extra);
    JsonObject editObject(String id, JsonObject properties);
    void       deleteObject(String id);
    JsonObject setDependency(String questId, String dependsOnId, boolean add);
    void       save();
}
