package com.ftbqbridge.http;

import com.ftbqbridge.backend.FakeQuestBackend;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class BridgeHttpServerTest {
    static BridgeHttpServer server; static FakeQuestBackend backend; static int port; static final String TOKEN = "secret";
    static HttpClient http = HttpClient.newHttpClient();

    @BeforeAll static void start() {
        backend = new FakeQuestBackend();
        Router r = new Router();
        BridgeHandlers.register(r, backend);
        server = new BridgeHttpServer("127.0.0.1", 0, false, TOKEN, r);
        server.start();
        port = server.boundPort();
    }
    @AfterAll static void stop() { server.stop(); }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Authorization", "Bearer " + TOKEN);
    }

    @Test void healthOk() throws Exception {
        HttpResponse<String> res = http.send(req("/health").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertTrue(JsonParser.parseString(res.body()).getAsJsonObject().get("ok").getAsBoolean());
    }
    @Test void unauthorizedWithoutToken() throws Exception {
        HttpResponse<String> res = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, res.statusCode());
    }
    @Test void createThenGetThenDelete() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("type", "CHAPTER"); body.addProperty("parent", "0000000000000001");
        body.add("properties", new JsonObject()); body.add("extra", new JsonObject());
        HttpResponse<String> create = http.send(req("/quests/object")
            .header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, create.statusCode());
        String id = JsonParser.parseString(create.body()).getAsJsonObject().get("id").getAsString();

        HttpResponse<String> get = http.send(req("/quests/object/" + id).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, get.statusCode());

        HttpResponse<String> del = http.send(req("/quests/object/" + id).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, del.statusCode());
    }
    @Test void missingObjectIs404() throws Exception {
        HttpResponse<String> res = http.send(req("/quests/object/DEADBEEF").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, res.statusCode());
        assertEquals("not_found", JsonParser.parseString(res.body()).getAsJsonObject()
            .getAsJsonObject("error").get("type").getAsString());
    }
    @Test void unknownRouteIs404() throws Exception {
        HttpResponse<String> res = http.send(req("/nope").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, res.statusCode());
    }
    @Test void saveCalls() throws Exception {
        int before = backend.saveCount;
        HttpResponse<String> res = http.send(req("/save").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertEquals(before + 1, backend.saveCount);
    }
}
