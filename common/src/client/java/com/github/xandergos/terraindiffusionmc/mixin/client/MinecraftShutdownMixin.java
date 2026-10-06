package com.github.xandergos.terraindiffusionmc.mixin.client;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** destroy() disconnects/saves first, but calls System.exit from its finally block. */
@Mixin(Minecraft.class)
public abstract class MinecraftShutdownMixin {
    @Inject(method = "destroy", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;close()V"))
    private void terrainDiffusion$drainBeforeClientClose(CallbackInfo ci) {
        TerrainDiffusionLifecycle.onClientStopping();
    }

    // Also covers exceptions in screen teardown before close(). Repeated shutdown is safe.
    @Inject(method = "destroy", at = @At(value = "INVOKE",
            target = "Ljava/lang/System;exit(I)V", remap = false))
    private void terrainDiffusion$drainBeforeExit(CallbackInfo ci) {
        TerrainDiffusionLifecycle.onClientStopping();
    }
}
