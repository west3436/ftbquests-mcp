package com.ftbqbridge.http;

import com.google.gson.JsonObject;
import java.util.Map;

public record RequestContext(String method, String path, Map<String,String> pathParams,
                             Map<String,String> query, JsonObject body, boolean loopback, String authHeader) {
    public String param(String n) { return pathParams.get(n); }
    public String queryOr(String n, String def) { return query.getOrDefault(n, def); }
}
