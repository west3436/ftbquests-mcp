package com.ftbqbridge.neoforge;

import com.ftbqbridge.Protocol;
import com.ftbqbridge.client.ClientVisuals;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Distribution-gated so a dedicated server never links Minecraft client classes. */
@EventBusSubscriber(modid = Protocol.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientBootstrap {
    private ClientBootstrap() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ClientVisuals visuals = ClientVisuals.initialize();
            // RenderFrameEvent wraps GameRenderer.render — the same points Fabric hooks with a mixin.
            NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e) -> visuals.beforeFrame());
            NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post e) -> visuals.afterFrame());
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre e) -> visuals.beforeTick());
            NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> visuals.loggingOut());
        });
    }
}
