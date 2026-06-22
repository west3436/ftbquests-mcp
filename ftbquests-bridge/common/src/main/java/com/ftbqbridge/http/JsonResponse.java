package com.ftbqbridge.http;

import com.google.gson.JsonElement;

public record JsonResponse(int status, JsonElement body) {
    public static JsonResponse ok(JsonElement b) { return new JsonResponse(200, b); }
    public static JsonResponse of(int s, JsonElement b) { return new JsonResponse(s, b); }
}
