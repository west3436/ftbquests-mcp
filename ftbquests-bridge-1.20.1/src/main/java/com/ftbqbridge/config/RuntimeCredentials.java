package com.ftbqbridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.file.*;

public final class RuntimeCredentials {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private RuntimeCredentials() {}

    public static void write(Path file, int port, String token, int protocolVersion, String boundAddress)
            throws java.io.IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        JsonObject o = new JsonObject();
        o.addProperty("port", port);
        o.addProperty("token", token);
        o.addProperty("protocolVersion", protocolVersion);
        o.addProperty("boundAddress", boundAddress);
        Files.writeString(file, GSON.toJson(o));
    }
}
