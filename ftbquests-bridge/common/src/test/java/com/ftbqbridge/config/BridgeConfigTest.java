package com.ftbqbridge.config;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class BridgeConfigTest {
    @Test void createsDefaultsAndTokenWhenMissing(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path f = dir.resolve("ftbquests-bridge.json");
        BridgeConfig c = BridgeConfig.load(f);
        assertTrue(Files.exists(f));
        assertEquals("127.0.0.1", c.bindAddress);
        assertEquals(25599, c.port);
        assertFalse(c.allowRemote);
        assertEquals("immediate", c.saveMode);
        assertEquals(10000L, c.requestTimeoutMs);
        assertNotNull(c.token);
        assertEquals(64, c.token.length()); // 32 bytes hex
    }

    @Test void roundTripsAndKeepsToken(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path f = dir.resolve("ftbquests-bridge.json");
        BridgeConfig c1 = BridgeConfig.load(f);
        String token = c1.token;
        BridgeConfig c2 = BridgeConfig.load(f);
        assertEquals(token, c2.token, "token must persist across loads");
    }
}
