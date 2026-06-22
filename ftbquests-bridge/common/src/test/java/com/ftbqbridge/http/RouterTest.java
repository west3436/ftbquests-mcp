package com.ftbqbridge.http;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RouterTest {
    @Test void matchesStaticAndExtractsParams() {
        Router r = new Router();
        r.add("GET", "/health", ctx -> JsonResponse.ok(new JsonObject()));
        r.add("GET", "/quests/chapter/{id}", ctx -> JsonResponse.ok(new JsonObject()));

        assertNotNull(r.match("GET", "/health"));
        Router.Match m = r.match("GET", "/quests/chapter/00FF");
        assertNotNull(m);
        assertEquals("00FF", m.params().get("id"));
    }

    @Test void methodAndLengthMismatchReturnNull() {
        Router r = new Router();
        r.add("GET", "/quests/chapter/{id}", ctx -> JsonResponse.ok(new JsonObject()));
        assertNull(r.match("POST", "/quests/chapter/00FF"), "method mismatch");
        assertNull(r.match("GET", "/quests/chapter/00FF/extra"), "segment count mismatch");
        assertNull(r.match("GET", "/quests"), "no route");
    }
}
