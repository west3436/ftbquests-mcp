package com.ftbqbridge.backend.ftbq;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure listing logic of {@link RegistryReader#assemble}. The Minecraft-typed
 * name resolver (Item/Block/... -> Component) is a thin adapter verified at compile/live time;
 * here we inject a fake {@link RegistryReader.NameResolver} so the filter/paginate/fallback logic
 * is exercised without bootstrapping Minecraft.
 */
class RegistryReaderTest {

    /** Build (id, value) entries where the value is just the id string, so the fake resolver can switch on it. */
    private static List<Map.Entry<String, Object>> entries(String... ids) {
        List<Map.Entry<String, Object>> l = new ArrayList<>();
        for (String id : ids) l.add(Map.entry(id, (Object) id));
        return l;
    }

    /** Fake resolver: "Name of <id>", with hooks for the three fall-back paths (null/blank/throws). */
    private static final RegistryReader.NameResolver RESOLVER = v -> {
        String id = (String) v;
        return switch (id) {
            case "test:null_name"  -> null;
            case "test:blank_name" -> "   ";
            case "test:boom"       -> { throw new RuntimeException("client-only boom"); }
            default                -> "Name of " + id;
        };
    };

    @Test void filtersByCaseInsensitiveSubstringOnId() {
        JsonArray out = RegistryReader.assemble(
                entries("minecraft:stone", "minecraft:diamond", "minecraft:deepslate"),
                "DIA", 50, 0, RESOLVER);
        assertEquals(1, out.size());
        assertEquals("minecraft:diamond", out.get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test void respectsOffsetAndLimit() {
        JsonArray out = RegistryReader.assemble(
                entries("a", "b", "c", "d", "e"), "", 2, 1, RESOLVER);
        assertEquals(2, out.size());
        assertEquals("b", out.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("c", out.get(1).getAsJsonObject().get("id").getAsString());
    }

    @Test void resolvesDisplayNameFromResolver() {
        JsonArray out = RegistryReader.assemble(entries("minecraft:stone"), "", 50, 0, RESOLVER);
        JsonObject e = out.get(0).getAsJsonObject();
        assertEquals("minecraft:stone", e.get("id").getAsString());
        assertEquals("Name of minecraft:stone", e.get("displayName").getAsString());
    }

    @Test void fallsBackToIdWhenResolverReturnsNullBlankOrThrows() {
        JsonArray out = RegistryReader.assemble(
                entries("test:null_name", "test:blank_name", "test:boom"), "", 50, 0, RESOLVER);
        assertEquals(3, out.size());
        for (var el : out) {
            JsonObject e = el.getAsJsonObject();
            assertEquals(e.get("id").getAsString(), e.get("displayName").getAsString(),
                    "an unresolved name must fall back to the id");
        }
    }
}
