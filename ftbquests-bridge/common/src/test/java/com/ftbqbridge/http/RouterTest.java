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

    @Test void decodesPercentEncodedPathParams() {
        Router r = new Router();
        r.add("GET", "/task-types/{id}/schema", ctx -> JsonResponse.ok(new JsonObject()));

        // The MCP client percent-encodes namespaced type ids (ftbquests:item -> ftbquests%3Aitem).
        // The captured param must be decoded back so the backend can parse it as a ResourceLocation.
        Router.Match m = r.match("GET", "/task-types/ftbquests%3Aitem/schema");
        assertNotNull(m);
        assertEquals("ftbquests:item", m.params().get("id"));
    }

    @Test void leavesUnencodedPathParamsUnchanged() {
        Router r = new Router();
        r.add("GET", "/quests/object/{id}", ctx -> JsonResponse.ok(new JsonObject()));
        Router.Match m = r.match("GET", "/quests/object/0000000000000001");
        assertNotNull(m);
        assertEquals("0000000000000001", m.params().get("id"));
    }

    @Test void methodAndLengthMismatchReturnNull() {
        Router r = new Router();
        r.add("GET", "/quests/chapter/{id}", ctx -> JsonResponse.ok(new JsonObject()));
        assertNull(r.match("POST", "/quests/chapter/00FF"), "method mismatch");
        assertNull(r.match("GET", "/quests/chapter/00FF/extra"), "segment count mismatch");
        assertNull(r.match("GET", "/quests"), "no route");
    }
}
