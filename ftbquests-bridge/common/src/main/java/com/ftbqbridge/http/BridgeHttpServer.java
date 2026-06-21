package com.ftbqbridge.http;

import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.json.Json;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class BridgeHttpServer {
    private final String bindAddress; private final int port; private final boolean allowRemote;
    private final String token; private final Router router;
    private HttpServer server;

    public BridgeHttpServer(String bindAddress, int port, boolean allowRemote, String token, Router router) {
        this.bindAddress = bindAddress; this.port = port; this.allowRemote = allowRemote; this.token = token; this.router = router;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bindAddress), port), 0);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException e) { throw new RuntimeException("Failed to start bridge HTTP server", e); }
    }

    public void stop() { if (server != null) server.stop(0); }
    public int boundPort() { return server.getAddress().getPort(); }

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
            Router.Match m = router.match(method, rawPath);
            if (m == null) { writeError(ex, 404, "not_found", "No route: " + method + " " + rawPath); return; }

            JsonObject body = readBody(ex);
            RequestContext ctx = new RequestContext(method, rawPath, m.params(), parseQuery(ex.getRequestURI().getRawQuery()),
                    body, loopback, authHeader);
            JsonResponse res = m.route().handle(ctx);
            write(ex, res.status(), Json.GSON.toJson(res.body()));
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
        write(ex, status, Json.GSON.toJson(err));
    }
    private static void write(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
