package com.ftbqbridge.fabric;

import com.ftbqbridge.FtbQuestsBridge;
import net.fabricmc.api.ModInitializer;

public final class FtbQuestsBridgeFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FtbQuestsBridge.init();
    }
}
