package com.ftbqbridge.fabric.mixin;

import com.ftbqbridge.fabric.FtbQuestsBridgeFabricClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Fabric equivalent of Forge's RenderTickEvent START/END, which wrap this same call. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void ftbqbridge$beforeFrame(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        FtbQuestsBridgeFabricClient.beforeFrame();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void ftbqbridge$afterFrame(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        FtbQuestsBridgeFabricClient.afterFrame();
    }
}
