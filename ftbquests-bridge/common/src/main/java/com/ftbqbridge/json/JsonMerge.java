package com.ftbqbridge.json;

import com.google.gson.JsonObject;
import java.util.Map;

public final class JsonMerge {
    private JsonMerge() {}

    public static JsonObject shallowMerge(JsonObject base, JsonObject patch) {
        JsonObject out = base.deepCopy();
        for (Map.Entry<String, com.google.gson.JsonElement> e : patch.entrySet()) {
            out.add(e.getKey(), e.getValue());
        }
        return out;
    }
}
