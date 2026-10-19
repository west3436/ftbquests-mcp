package com.ftbqbridge;

import com.ftbqbridge.backend.ServerTaskExecutor;
import com.ftbqbridge.backend.ftbq.FtbQuestsBackend;
import com.ftbqbridge.backend.ftbq.MinecraftServerExecutor;
import com.ftbqbridge.config.BridgeConfig;
import com.ftbqbridge.config.RuntimeCredentials;
import com.ftbqbridge.http.BridgeHandlers;
import com.ftbqbridge.http.BridgeHttpServer;
import com.ftbqbridge.http.Router;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Owns the bridge lifecycle: builds the HTTP server when the integrated or dedicated server
 * starts, and tears it down when that server stops.
 * <p>
 * Kept static and Minecraft-Forge-free in its shape so the behavioural contract stays obvious:
 * one server per JVM, started on {@code ServerStartedEvent}, stopped on {@code ServerStoppingEvent}.
 */
public final class FtbQuestsBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("ftbquests-bridge");

    /** The running bridge, or {@code null} when no server is hosting one. */
    private static BridgeHttpServer httpServer;

    private FtbQuestsBridge() {
    }

    /** Called from the mod constructor; logging only, because no server exists yet. */
    public static void init() {
        // In a dev run the package has no Implementation-Version, so ask the mod container instead.
        String version = ModList.get().getModContainerById(Protocol.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("dev");
        LOGGER.info("FTB Quests Bridge {} initialised (protocol v{})", version, Protocol.PROTOCOL_VERSION);
    }

    /** Boots the bridge, or stays dormant when the config disables it. */
    public static void onServerStarted(MinecraftServer server) {
        if (httpServer != null) {
            // Defensive: an integrated server restart reuses the JVM, so a stale instance would
            // keep the old port bound and point at a dead MinecraftServer.
            LOGGER.warn("Bridge already running; ignoring duplicate server-start event");
            return;
        }

        Path configDir = FMLPaths.CONFIGDIR.get();
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

        ServerTaskExecutor executor = new MinecraftServerExecutor(server, config.requestTimeoutMs);
        FtbQuestsBackend backend = new FtbQuestsBackend(executor, saveImmediately(config));

        Router router = new Router();
        BridgeHandlers.register(router, backend);

        BridgeHttpServer candidate = new BridgeHttpServer(
                config.bindAddress, config.port, config.allowRemote, config.token, router);
        try {
            candidate.start();
        } catch (Exception e) {
            // A busy port must not take the game down; the bridge is an authoring aid.
            LOGGER.error("Failed to start FTB Quests Bridge on {}:{}",
                    config.bindAddress, config.port, e);
            return;
        }

        httpServer = candidate;
        int boundPort = candidate.boundPort();

        try {
            RuntimeCredentials.write(
                    configDir.resolve("ftbquests-bridge").resolve("runtime.json"),
                    boundPort, config.token, Protocol.PROTOCOL_VERSION, config.bindAddress);
            LOGGER.info("[ftbquests-bridge] HTTP API on {}:{} (allowRemote={})",
                    config.bindAddress, boundPort, config.allowRemote);
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

    /** Stops the bridge; safe to call when it never started. */
    public static void onServerStopping(MinecraftServer server) {
        stop();
    }

    private static void stop() {
        BridgeHttpServer running = httpServer;
        httpServer = null;
        if (running == null) {
            return;
        }
        running.stop();
        LOGGER.info("FTB Quests Bridge stopped");
    }

    /** Exposed so the router can be exercised without a server; unused in production. */
    static BridgeHttpServer server() {
        return httpServer;
    }
}
