package com.ftbqbridge.fabric;

import com.ftbqbridge.FtbQuestsBridge;
import com.ftbqbridge.client.ClientVisuals;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Fabric client entrypoint: installs quest-book screenshots once the client has started, which also
 * starts the client-owned bridge (see {@link FtbQuestsBridge}). Fabric API has no whole-frame event,
 * so {@link com.ftbqbridge.fabric.mixin.GameRendererMixin} forwards the frame hooks.
 */
public final class FtbQuestsBridgeFabricClient implements ClientModInitializer {
    private static volatile ClientVisuals visuals;

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> visuals = ClientVisuals.initialize());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> FtbQuestsBridge.shutdown());
        ClientTickEvents.START_CLIENT_TICK.register(client -> { ClientVisuals v = visuals; if (v != null) v.beforeTick(); });
        // May fire off the client thread; ClientVisuals.loggingOut() hops back onto it.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { ClientVisuals v = visuals; if (v != null) v.loggingOut(); });
    }

    /** Called by the mixin at the start of {@code GameRenderer.render}. */
    public static void beforeFrame() { ClientVisuals v = visuals; if (v != null) v.beforeFrame(); }

    /** Called by the mixin at every return of {@code GameRenderer.render}. */
    public static void afterFrame() { ClientVisuals v = visuals; if (v != null) v.afterFrame(); }
}
