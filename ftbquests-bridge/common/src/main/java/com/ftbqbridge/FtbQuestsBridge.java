package com.ftbqbridge;

import com.ftbqbridge.backend.ftbq.FtbQuestsBackend;
import com.ftbqbridge.backend.ftbq.MinecraftServerExecutor;
import com.ftbqbridge.config.BridgeConfig;
import com.ftbqbridge.config.RuntimeCredentials;
import com.ftbqbridge.http.BridgeHandlers;
import com.ftbqbridge.http.BridgeHttpServer;
import com.ftbqbridge.http.Router;
import dev.architectury.platform.Platform;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Common entry point and lifecycle owner. Each loader calls {@link #init()} at mod construction, then
 * {@link #onServerStarted(MinecraftServer)} / {@link #onServerStopping(MinecraftServer)} from its own
 * server-lifecycle events. The HTTP bridge is started after the server (and FTB Quests) have loaded.
 */
public final class FtbQuestsBridge {
    public static final Logger LOG = LoggerFactory.getLogger("ftbquests-bridge");
    private static BridgeHttpServer httpServer;
    private FtbQuestsBridge() {}

    public static void init() {
        LOG.info("[ftbquests-bridge] loaded (protocol v{})", Protocol.PROTOCOL_VERSION);
    }

    public static void onServerStarted(MinecraftServer server) {
        try {
            Path configDir = Platform.getConfigFolder();
            BridgeConfig cfg = BridgeConfig.load(configDir.resolve("ftbquests-bridge.json"));
            if (!cfg.enabled) { LOG.info("[ftbquests-bridge] disabled via config"); return; }

            boolean saveImmediately = !"manual".equalsIgnoreCase(cfg.saveMode);
            Router r = new Router();
            BridgeHandlers.register(r, new FtbQuestsBackend(new MinecraftServerExecutor(cfg.requestTimeoutMs), saveImmediately));
            httpServer = new BridgeHttpServer(cfg.bindAddress, cfg.port, cfg.allowRemote, cfg.token, r);
            httpServer.start();
            int boundPort = httpServer.boundPort();
            RuntimeCredentials.write(configDir.resolve("ftbquests-bridge/runtime.json"),
                    boundPort, cfg.token, Protocol.PROTOCOL_VERSION, cfg.bindAddress);
            LOG.info("[ftbquests-bridge] HTTP API on {}:{} (allowRemote={})", cfg.bindAddress, boundPort, cfg.allowRemote);
            if (cfg.allowRemote) LOG.warn("[ftbquests-bridge] allowRemote=true — non-loopback clients may connect with the token");
        } catch (Exception e) {
            LOG.error("[ftbquests-bridge] failed to start HTTP API", e);
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        if (httpServer != null) { httpServer.stop(); httpServer = null; LOG.info("[ftbquests-bridge] HTTP API stopped"); }
    }
}
