package com.ftbqbridge.json;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonMergeTest {
    @Test void patchOverridesAndAdds() {
        JsonObject base = new JsonObject();
        base.addProperty("title", "Old"); base.addProperty("x", 1);
        JsonObject patch = new JsonObject();
        patch.addProperty("title", "New"); patch.addProperty("y", 2);
        JsonObject out = JsonMerge.shallowMerge(base, patch);
        assertEquals("New", out.get("title").getAsString());
        assertEquals(1, out.get("x").getAsInt());
        assertEquals(2, out.get("y").getAsInt());
        assertEquals("Old", base.get("title").getAsString(), "base must be unmodified");
    }
}
