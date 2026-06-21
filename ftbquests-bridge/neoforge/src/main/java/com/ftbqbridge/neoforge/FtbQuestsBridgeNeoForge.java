package com.ftbqbridge.neoforge;

import com.ftbqbridge.FtbQuestsBridge;
import com.ftbqbridge.Protocol;
import net.neoforged.fml.common.Mod;

@Mod(Protocol.MOD_ID)
public final class FtbQuestsBridgeNeoForge {
    public FtbQuestsBridgeNeoForge() {
        FtbQuestsBridge.init();
    }
}
