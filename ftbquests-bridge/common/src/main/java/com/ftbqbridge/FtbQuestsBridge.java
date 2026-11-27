package com.ftbqbridge;

import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.backend.VisualBackend;
import com.ftbqbridge.backend.ftbq.FtbQuestsBackend;
import com.ftbqbridge.backend.ftbq.MinecraftServerExecutor;
import com.ftbqbridge.config.BridgeConfig;
import com.ftbqbridge.config.RuntimeCredentials;
import com.ftbqbridge.http.BridgeHandlers;
import com.ftbqbridge.http.BridgeHttpServer;
import com.ftbqbridge.http.JsonResponse;
import com.ftbqbridge.http.Router;
import dev.architectury.platform.Platform;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Common entry point and lifecycle owner of the one HTTP bridge per game instance. Each loader calls
 * {@link #init()} at mod construction, then {@link #onServerStarted(MinecraftServer)} /
 * {@link #onServerStopping(MinecraftServer)} from its own server-lifecycle events.
 *
 * <p>Two lifetimes: on a dedicated server (or a client without a visual backend) the bridge runs while
 * the server runs. On a client that installs a {@link VisualBackend}, the bridge starts at client setup
 * and lives until the game shuts down, so the MCP server can connect before a world is loaded; the
 * quest-editing routes answer {@code quests_not_loaded} until the integrated server is up.
 */
public final class FtbQuestsBridge {
    public static final Logger LOG = LoggerFactory.getLogger("ftbquests-bridge");
    private static BridgeHttpServer httpServer;
    private static volatile VisualBackend visualBackend;
    private static volatile boolean readOnly;
    private static boolean clientOwned;
    private FtbQuestsBridge() {}

    public static void init() {
        LOG.info("[ftbquests-bridge] loaded (protocol v{})", Protocol.PROTOCOL_VERSION);
    }

    public static boolean isReadOnly() { return readOnly; }
    public static boolean hasVisualBackend() { return visualBackend != null; }

    /** Called once from client setup; takes ownership of the bridge for the client's whole lifetime. */
    public static synchronized void installVisualBackend(VisualBackend backend) {
        if (visualBackend != null) throw new IllegalStateException("Client visual backend already installed");
        visualBackend = Objects.requireNonNull(backend);
        clientOwned = true;
        startHttp();
    }

    public static synchronized void onServerStarted(MinecraftServer server) {
        if (!clientOwned) startHttp();
    }

    public static synchronized void onServerStopping(MinecraftServer server) {
        if (!clientOwned) stopHttp();
    }

    /** Game shutdown: stops a client-owned bridge. */
    public static synchronized void shutdown() {
        stopHttp();
        visualBackend = null;
        clientOwned = false;
    }

    private static VisualBackend visual() {
        VisualBackend backend = visualBackend;
        if (backend == null) {
            throw new ApiException(503, "client_unavailable", "Quest screenshots need a NeoForge game client running the bridge");
        }
        return backend;
    }

    private static void startHttp() {
        if (httpServer != null) return;
        try {
            Path configDir = Platform.getConfigFolder();
            BridgeConfig cfg = BridgeConfig.load(configDir.resolve("ftbquests-bridge.json"));
            if (!cfg.enabled) { LOG.info("[ftbquests-bridge] disabled via config"); return; }

            readOnly = cfg.readOnly;
            boolean saveImmediately = !"manual".equalsIgnoreCase(cfg.saveMode);
            Router r = new Router();
            BridgeHandlers.register(r, new FtbQuestsBackend(new MinecraftServerExecutor(cfg.requestTimeoutMs), saveImmediately));
            r.add("GET", "/client/state", ctx -> JsonResponse.ok(visual().state()));
            r.add("POST", "/client/render", ctx -> {
                if (ctx.body() == null) throw ApiException.badRequest("JSON render request required");
                return JsonResponse.ok(visual().render(ctx.body()));
            });
            httpServer = new BridgeHttpServer(cfg.bindAddress, cfg.port, cfg.allowRemote, cfg.token, r, readOnly);
            httpServer.start();
            int boundPort = httpServer.boundPort();
            RuntimeCredentials.write(configDir.resolve("ftbquests-bridge/runtime.json"),
                    boundPort, cfg.token, Protocol.PROTOCOL_VERSION, cfg.bindAddress);
            if (httpServer.fellBackToEphemeralPort()) {
                LOG.warn("[ftbquests-bridge] port {} is in use (another Minecraft instance running the bridge?); "
                        + "using port {} instead — runtime.json has the real port", cfg.port, boundPort);
            }
            LOG.info("[ftbquests-bridge] HTTP API on {}:{} (allowRemote={}, readOnly={}, screenshots={})",
                    cfg.bindAddress, boundPort, cfg.allowRemote, readOnly, clientOwned);
            if (cfg.allowRemote) LOG.warn("[ftbquests-bridge] allowRemote=true — non-loopback clients may connect with the token");
        } catch (Exception e) {
            stopHttp();
            LOG.error("[ftbquests-bridge] failed to start HTTP API", e);
        }
    }

    private static void stopHttp() {
        if (httpServer != null) { httpServer.stop(); httpServer = null; LOG.info("[ftbquests-bridge] HTTP API stopped"); }
    }
}
