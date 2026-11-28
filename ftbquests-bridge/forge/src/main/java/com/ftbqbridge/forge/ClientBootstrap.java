package com.ftbqbridge.forge;

import com.ftbqbridge.Protocol;
import com.ftbqbridge.client.ClientVisuals;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Distribution-gated so a dedicated server never links Minecraft client classes. */
@Mod.EventBusSubscriber(modid = Protocol.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientBootstrap {
    private ClientBootstrap() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ClientVisuals visuals = ClientVisuals.initialize();
            // Forge 47 fires RenderTickEvent START/END around GameRenderer.render — the same points
            // NeoForge's RenderFrameEvent and the Fabric mixin hook.
            MinecraftForge.EVENT_BUS.addListener((TickEvent.RenderTickEvent e) -> {
                if (e.phase == TickEvent.Phase.START) visuals.beforeFrame();
                else visuals.afterFrame();
            });
            MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
                if (e.phase == TickEvent.Phase.START) visuals.beforeTick();
            });
            MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> visuals.loggingOut());
        });
    }
}
