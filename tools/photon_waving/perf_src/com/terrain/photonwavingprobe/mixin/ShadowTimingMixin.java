package com.terrain.photonwavingprobe.mixin;

import com.terrain.photonwavingprobe.ShadowGpuTimer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Iris 1.8.14-beta.1+mc1.21.1 renderShadows timing boundary. */
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public final class ShadowTimingMixin {
    @Inject(
            method = "renderShadows(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;Lnet/minecraft/client/Camera;)V",
            at = @At("HEAD"),
            require = 1,
            remap = false)
    private void photonwaving$shadowStart(CallbackInfo callback) {
        ShadowGpuTimer.shadowPassStart();
    }

    @Inject(
            method = "renderShadows(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;Lnet/minecraft/client/Camera;)V",
            at = @At("RETURN"),
            require = 1,
            remap = false)
    private void photonwaving$shadowEnd(CallbackInfo callback) {
        ShadowGpuTimer.shadowPassEnd();
    }
}
