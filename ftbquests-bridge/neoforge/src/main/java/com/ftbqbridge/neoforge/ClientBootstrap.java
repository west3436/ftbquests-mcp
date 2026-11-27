package com.ftbqbridge.neoforge;

import com.ftbqbridge.Protocol;
import com.ftbqbridge.neoforge.client.ClientVisuals;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Distribution-gated so a dedicated server never links Minecraft client classes. */
@EventBusSubscriber(modid = Protocol.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientBootstrap {
    private ClientBootstrap() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(ClientVisuals::initialize);
    }
}
