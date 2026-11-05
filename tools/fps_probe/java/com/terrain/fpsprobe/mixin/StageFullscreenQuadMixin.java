package com.terrain.fpsprobe.mixin;

import com.terrain.fpsprobe.RenderStageProfiler;
import com.terrain.fpsprobe.StageLabels;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Higher priority intentionally brackets PureScale's default-priority Photon quad hook. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.FullScreenQuadRenderer", remap = false, priority = 2000)
abstract class StageFullscreenQuadMixin {
    @Inject(method = "renderQuad()V", at = @At("HEAD"), require = 1)
    private void td$stageBegin(CallbackInfo ci) { RenderStageProfiler.begin(StageLabels.quadBegin()); }
    @Inject(method = "renderQuad()V", at = @At("RETURN"), require = 1)
    private void td$stageEnd(CallbackInfo ci) { RenderStageProfiler.end(StageLabels.quadEnd()); }
}
