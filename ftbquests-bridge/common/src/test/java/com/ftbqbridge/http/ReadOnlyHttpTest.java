package com.ftbqbridge.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Adapted from RedyRT/ftbquests-visual-mcp. */
class ReadOnlyHttpTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger mutations = new AtomicInteger();
    private BridgeHttpServer server;

    @BeforeEach void start() {
        var router = new Router();
        for (String method : new String[]{"POST", "PATCH", "DELETE"}) {
            router.add(method, "/quests/object", ctx -> {
                mutations.incrementAndGet();
                return JsonResponse.ok(new JsonObject());
            });
        }
        router.add("POST", "/save", ctx -> {
            mutations.incrementAndGet();
            return JsonResponse.ok(new JsonObject());
        });
        router.add("GET", "/health", ctx -> JsonResponse.ok(new JsonObject()));
        router.add("POST", "/client/render", ctx -> JsonResponse.ok(new JsonObject()));
        server = new BridgeHttpServer("127.0.0.1", 0, false, "local-token", router, true);
        server.start();
    }

    @AfterEach void stop() { server.stop(); }

    private HttpResponse<String> request(String method, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.boundPort() + path))
                .header("Authorization", "Bearer local-token")
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test void rejectsAllMutationVerbsBeforeExecutingHandlers() throws Exception {
        for (String method : new String[]{"POST", "PATCH", "DELETE"}) {
            var response = request(method, "/quests/object");
            assertEquals(403, response.statusCode(), method);
            assertEquals("read_only", JsonParser.parseString(response.body()).getAsJsonObject()
                    .getAsJsonObject("error").get("type").getAsString());
        }
        assertEquals(0, mutations.get());
    }

    @Test void savingIsAlsoAMutation() throws Exception {
        assertEquals(403, request("POST", "/save").statusCode());
        assertEquals(0, mutations.get());
    }

    @Test void permitsReadOperationsIncludingNativeRenderPost() throws Exception {
        assertEquals(200, request("GET", "/health").statusCode());
        assertEquals(200, request("POST", "/client/render").statusCode());
    }
}
