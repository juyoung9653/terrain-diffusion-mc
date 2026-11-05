package com.terrain.fpsprobe.mixin;

import com.terrain.fpsprobe.RenderStageProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer", remap = false)
abstract class StageCompositeMixin {
    private static final String LABEL = "iris.composite.renderAll";
    @Inject(method = "renderAll()V", at = @At("HEAD"), require = 1)
    private void td$stageBegin(CallbackInfo ci) { RenderStageProfiler.begin(LABEL); }
    @Inject(method = "renderAll()V", at = @At("RETURN"), require = 1)
    private void td$stageEnd(CallbackInfo ci) { RenderStageProfiler.end(LABEL); }
}
