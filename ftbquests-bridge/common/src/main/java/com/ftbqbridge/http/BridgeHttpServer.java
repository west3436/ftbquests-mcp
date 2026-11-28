package com.ftbqbridge.http;

import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.json.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BridgeHttpServer {
    /** The one non-GET route a read-only bridge still serves: it captures a screenshot and changes nothing. */
    static final String RENDER_PATH = "/client/render";

    private final String bindAddress; private final int port; private final boolean allowRemote;
    private final String token; private final Router router; private final boolean readOnly;
    private ExecutorService executor;
    private HttpServer server;
    private boolean fellBackToEphemeralPort;

    public BridgeHttpServer(String bindAddress, int port, boolean allowRemote, String token, Router router) {
        this(bindAddress, port, allowRemote, token, router, false);
    }

    public BridgeHttpServer(String bindAddress, int port, boolean allowRemote, String token, Router router, boolean readOnly) {
        this.bindAddress = bindAddress; this.port = port; this.allowRemote = allowRemote; this.token = token; this.router = router;
        this.readOnly = readOnly;
    }

    /**
     * Binds the configured port, or an ephemeral one if it is taken — e.g. a game client and a dedicated
     * server on the same machine both running the bridge. The real port is published in runtime.json.
     */
    public void start() {
        try {
            InetAddress address = InetAddress.getByName(bindAddress);
            try {
                server = HttpServer.create(new InetSocketAddress(address, port), 0);
            } catch (BindException taken) {
                if (port == 0) throw taken;
                server = HttpServer.create(new InetSocketAddress(address, 0), 0);
                fellBackToEphemeralPort = true;
            }
            executor = Executors.newFixedThreadPool(4);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException e) {
            stop();
            throw new RuntimeException("Failed to start bridge HTTP server", e);
        }
    }

    public void stop() {
        if (server != null) { server.stop(0); server = null; }
        if (executor != null) { executor.shutdownNow(); executor = null; }
    }
    public int boundPort() { return server.getAddress().getPort(); }
    /** True when the configured port was busy and {@link #boundPort()} is an ephemeral port instead. */
    public boolean fellBackToEphemeralPort() { return fellBackToEphemeralPort; }

    private void handle(HttpExchange ex) throws IOException {
        try {
            boolean loopback = ex.getRemoteAddress().getAddress().isLoopbackAddress();
            String authHeader = ex.getRequestHeaders().getFirst("Authorization");
            switch (Auth.evaluate(loopback, authHeader, token, allowRemote)) {
                case REMOTE_DISABLED -> { writeError(ex, 403, "remote_disabled", "Remote access disabled"); return; }
                case UNAUTHORIZED   -> { writeError(ex, 401, "unauthorized", "Missing or invalid token"); return; }
                default -> {}
            }
            String method = ex.getRequestMethod();
            String rawPath = ex.getRequestURI().getRawPath();
            if (readOnly && !method.equals("GET") && !(method.equals("POST") && rawPath.equals(RENDER_PATH))) {
                writeError(ex, 403, "read_only", "Quest edits are disabled (readOnly=true in ftbquests-bridge.json)");
                return;
            }
            Router.Match m = router.match(method, rawPath);
            if (m == null) { writeError(ex, 404, "not_found", "No route: " + method + " " + rawPath); return; }

            JsonObject body = readBody(ex);
            RequestContext ctx = new RequestContext(method, rawPath, m.params(), parseQuery(ex.getRequestURI().getRawQuery()),
                    body, loopback, authHeader);
            JsonResponse res = m.route().handle(ctx);
            write(ex, res.status(), res.body());
        } catch (ApiException e) {
            writeError(ex, e.status, e.type, e.getMessage());
        } catch (Exception e) {
            writeError(ex, 500, "internal", String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private static JsonObject readBody(HttpExchange ex) {
        try {
            byte[] raw = ex.getRequestBody().readAllBytes();
            if (raw.length == 0) return null;
            return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) { throw ApiException.badRequest("Malformed JSON body"); }
    }

    private static Map<String,String> parseQuery(String raw) {
        Map<String,String> q = new HashMap<>();
        if (raw == null || raw.isEmpty()) return q;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) q.put(urlDecode(pair), "");
            else q.put(urlDecode(pair.substring(0, i)), urlDecode(pair.substring(i + 1)));
        }
        return q;
    }
    private static String urlDecode(String s) { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8); }

    private static void writeError(HttpExchange ex, int status, String type, String message) throws IOException {
        JsonObject err = new JsonObject(); JsonObject inner = new JsonObject();
        inner.addProperty("code", status); inner.addProperty("type", type); inner.addProperty("message", message == null ? "" : message);
        err.add("error", inner);
        write(ex, status, err);
    }
    /** Streams the JSON (chunked) so a multi-MB screenshot payload is not first copied into a String and a byte[]. */
    private static void write(HttpExchange ex, int status, JsonElement body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, 0);
        try (var writer = new OutputStreamWriter(ex.getResponseBody(), StandardCharsets.UTF_8)) {
            Json.GSON.toJson(body, writer);
        }
    }
}
