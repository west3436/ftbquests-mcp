package com.ftbqbridge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge entry point for Minecraft 1.20.1.
 * <p>
 * No registries and no networking: the whole mod is a loopback HTTP server that edits the
 * server-side quest file through FTB Quests' own objects, so there is nothing to sync to clients
 * beyond what FTB already broadcasts.
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
}
