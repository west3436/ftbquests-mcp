package com.ftbqbridge;

import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.backend.ServerTaskExecutor;
import com.ftbqbridge.backend.VisualBackend;
import com.ftbqbridge.backend.ftbq.FtbQuestsBackend;
import com.ftbqbridge.backend.ftbq.MinecraftServerExecutor;
import com.ftbqbridge.config.BridgeConfig;
import com.ftbqbridge.config.RuntimeCredentials;
import com.ftbqbridge.http.BridgeHandlers;
import com.ftbqbridge.http.BridgeHttpServer;
import com.ftbqbridge.http.JsonResponse;
import com.ftbqbridge.http.Router;
import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Owns the lifecycle of the one HTTP bridge per game instance.
 * <p>
 * Loader-neutral: each loader's entry point calls {@link #init()} at mod construction, then
 * {@link #onServerStarted(MinecraftServer)} / {@link #onServerStopping(MinecraftServer)} from its own
 * server-lifecycle events.
 * <p>
 * Two lifetimes: on a dedicated server (or a client without a visual backend) the bridge runs while
 * the server runs. On a client that installs a {@link VisualBackend}, the bridge starts at client
 * setup and lives until the game shuts down, so the MCP server can connect before a world is loaded;
 * the quest-editing routes answer {@code quests_not_loaded} until the integrated server is up.
 */
public final class FtbQuestsBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("ftbquests-bridge");

    /** The running bridge, or {@code null} when none is running. */
    private static BridgeHttpServer httpServer;
    private static volatile VisualBackend visualBackend;
    private static volatile boolean readOnly;
    /** True once a client installed a visual backend: the bridge then ignores server start/stop. */
    private static boolean clientOwned;

    private FtbQuestsBridge() {
    }

    /** Called from the mod constructor; logging only, because no server exists yet. */
    public static void init() {
        // In a dev run the package has no Implementation-Version, so ask the mod container instead.
        String version = Platform.getOptionalMod(Protocol.MOD_ID).map(Mod::getVersion).orElse("dev");
        LOGGER.info("FTB Quests Bridge {} initialised (protocol v{})", version, Protocol.PROTOCOL_VERSION);
    }

    public static boolean isReadOnly() {
        return readOnly;
    }

    public static boolean hasVisualBackend() {
        return visualBackend != null;
    }

    /** Called once from client setup; takes ownership of the bridge for the client's whole lifetime. */
    public static synchronized void installVisualBackend(VisualBackend backend) {
        if (visualBackend != null) throw new IllegalStateException("Client visual backend already installed");
        visualBackend = Objects.requireNonNull(backend);
        clientOwned = true;
        start();
    }

    /** Boots the bridge with the server, unless a game client already owns it. */
    public static synchronized void onServerStarted(MinecraftServer server) {
        if (clientOwned) return;
        if (httpServer != null) {
            // Defensive: an integrated server restart reuses the JVM, so a stale instance would
            // keep the old port bound.
            LOGGER.warn("Bridge already running; ignoring duplicate server-start event");
            return;
        }
        start();
    }

    /** Stops a server-owned bridge; safe to call when it never started. */
    public static synchronized void onServerStopping(MinecraftServer server) {
        if (!clientOwned) stop();
    }

    /** Game shutdown: stops a client-owned bridge. */
    public static synchronized void shutdown() {
        stop();
        visualBackend = null;
        clientOwned = false;
    }

    private static VisualBackend visual() {
        VisualBackend backend = visualBackend;
        if (backend == null) {
            throw new ApiException(503, "client_unavailable",
                    "Quest screenshots need a game client running the bridge; a dedicated server has no screen to capture");
        }
        return backend;
    }

    /** Boots the bridge, or stays dormant when the config disables it. */
    private static void start() {
        if (httpServer != null) return;

        Path configDir = Platform.getConfigFolder();
        BridgeConfig config;
        try {
            config = BridgeConfig.load(configDir.resolve("ftbquests-bridge.json"));
        } catch (IOException e) {
            // An unreadable config is a deployment problem, not a crash: log and stay dormant.
            LOGGER.error("Could not read {}; bridge not started",
                    configDir.resolve("ftbquests-bridge.json"), e);
            return;
        }
        if (!config.enabled) {
            LOGGER.info("FTB Quests Bridge disabled by config; not starting");
            return;
        }

        readOnly = config.readOnly;
        ServerTaskExecutor executor = new MinecraftServerExecutor(config.requestTimeoutMs);
        FtbQuestsBackend backend = new FtbQuestsBackend(executor, saveImmediately(config));

        Router router = new Router();
        BridgeHandlers.register(router, backend);
        router.add("GET", "/client/state", ctx -> JsonResponse.ok(visual().state()));
        router.add("POST", "/client/render", ctx -> {
            if (ctx.body() == null) throw ApiException.badRequest("JSON render request required");
            return JsonResponse.ok(visual().render(ctx.body()));
        });

        BridgeHttpServer candidate = new BridgeHttpServer(
                config.bindAddress, config.port, config.allowRemote, config.token, router, readOnly);
        try {
            candidate.start();
        } catch (Exception e) {
            // The bridge is an authoring aid; failing to bind must not take the game down.
            LOGGER.error("Failed to start FTB Quests Bridge on {}:{}",
                    config.bindAddress, config.port, e);
            return;
        }

        httpServer = candidate;
        int boundPort = candidate.boundPort();
        if (candidate.fellBackToEphemeralPort()) {
            LOGGER.warn("[ftbquests-bridge] port {} is in use (another Minecraft instance running the bridge?); "
                    + "using port {} instead — runtime.json has the real port", config.port, boundPort);
        }

        try {
            RuntimeCredentials.write(
                    configDir.resolve("ftbquests-bridge").resolve("runtime.json"),
                    boundPort, config.token, Protocol.PROTOCOL_VERSION, config.bindAddress);
            LOGGER.info("[ftbquests-bridge] HTTP API on {}:{} (allowRemote={}, readOnly={}, screenshots={})",
                    config.bindAddress, boundPort, config.allowRemote, readOnly, clientOwned);
            if (config.allowRemote) {
                LOGGER.warn("[ftbquests-bridge] allowRemote=true — non-loopback clients may connect with the token");
            }
            LOGGER.info("FTB Quests Bridge listening on {}:{} (token in config/ftbquests-bridge/runtime.json)",
                    config.bindAddress, boundPort);
        } catch (Exception e) {
            LOGGER.error("Bridge is running but runtime.json could not be written", e);
        }
    }

    /**
     * "immediate" persists after every mutation; "manual" leaves the file dirty for an explicit
     * POST /save. Any other value is treated as immediate, matching the 1.21.1 behaviour.
     */
    private static boolean saveImmediately(BridgeConfig config) {
        return !"manual".equalsIgnoreCase(config.saveMode);
    }

    private static void stop() {
        BridgeHttpServer running = httpServer;
        httpServer = null;
        if (running == null) {
            return;
        }
        running.stop();
        LOGGER.info("[ftbquests-bridge] HTTP API stopped");
    }

    /** Exposed so the router can be exercised without a server; unused in production. */
    static BridgeHttpServer server() {
        return httpServer;
    }
}
