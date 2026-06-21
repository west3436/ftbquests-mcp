package com.ftbqbridge.neoforge;

import com.ftbqbridge.FtbQuestsBridge;
import com.ftbqbridge.Protocol;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(Protocol.MOD_ID)
public final class FtbQuestsBridgeNeoForge {
    public FtbQuestsBridgeNeoForge() {
        FtbQuestsBridge.init();
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e)  -> FtbQuestsBridge.onServerStarted(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> FtbQuestsBridge.onServerStopping(e.getServer()));
    }
}
