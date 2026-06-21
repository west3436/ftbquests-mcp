package com.ftbqbridge.fabric;

import com.ftbqbridge.FtbQuestsBridge;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class FtbQuestsBridgeFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FtbQuestsBridge.init();
        ServerLifecycleEvents.SERVER_STARTED.register(FtbQuestsBridge::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(FtbQuestsBridge::onServerStopping);
    }
}
