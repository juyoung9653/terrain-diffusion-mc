package com.terrain.fpsprobe.mixin;

import com.terrain.fpsprobe.RenderStageProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
abstract class StageShadowMixin {
    private static final String LABEL = "iris.shadow.renderShadows";
    @Inject(method = "renderShadows(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;Lnet/minecraft/client/Camera;)V", at = @At("HEAD"), require = 1)
    private void td$stageBegin(CallbackInfo ci) { RenderStageProfiler.begin(LABEL); }
    @Inject(method = "renderShadows(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;Lnet/minecraft/client/Camera;)V", at = @At("RETURN"), require = 1)
    private void td$stageEnd(CallbackInfo ci) { RenderStageProfiler.end(LABEL); }
}
