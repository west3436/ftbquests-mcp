package com.ftbqbridge.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCredentialsTest {
    @Test void writesAllFields(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("ftbquests-bridge/runtime.json");
        RuntimeCredentials.write(f, 25599, "abc123", 1, "127.0.0.1");
        JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        assertEquals(25599, o.get("port").getAsInt());
        assertEquals("abc123", o.get("token").getAsString());
        assertEquals(1, o.get("protocolVersion").getAsInt());
        assertEquals("127.0.0.1", o.get("boundAddress").getAsString());
    }
}
