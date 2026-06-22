package com.ftbqbridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.security.SecureRandom;

public final class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;
    public String bindAddress = "127.0.0.1";
    public int port = 25599;
    public boolean allowRemote = false;
    public String token = "";
    public String saveMode = "immediate";
    public long requestTimeoutMs = 10000L;

    public static BridgeConfig load(Path file) throws java.io.IOException {
        BridgeConfig cfg;
        if (Files.exists(file)) {
            cfg = GSON.fromJson(Files.readString(file), BridgeConfig.class);
            if (cfg == null) cfg = new BridgeConfig();
        } else {
            cfg = new BridgeConfig();
        }
        if (cfg.token == null || cfg.token.isBlank()) cfg.token = newToken();
        cfg.store(file);
        return cfg;
    }

    public void store(Path file) throws java.io.IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this));
    }

    private static String newToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
