package com.ftbqbridge.forge;

import com.ftbqbridge.FtbQuestsBridge;
import com.ftbqbridge.Protocol;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge entry point for Minecraft 1.20.1.
 * <p>
 * No registries and no networking: the whole mod is a loopback HTTP server that edits the
 * server-side quest file through FTB Quests' own objects, so there is nothing to sync to clients
 * beyond what FTB already broadcasts. On a game client, {@link ClientBootstrap} also installs
 * quest-book screenshots, which keeps the bridge running for the client's whole lifetime.
 */
@Mod(Protocol.MOD_ID)
public final class FtbQuestsBridgeForge {
    public FtbQuestsBridgeForge() {
        FtbQuestsBridge.init();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        FtbQuestsBridge.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        FtbQuestsBridge.onServerStopping(event.getServer());
    }

    /** Stops a client-owned bridge (see {@link FtbQuestsBridge}); a no-op on a dedicated server. */
    @SubscribeEvent
    public void onGameShuttingDown(GameShuttingDownEvent event) {
        FtbQuestsBridge.shutdown();
    }
}
