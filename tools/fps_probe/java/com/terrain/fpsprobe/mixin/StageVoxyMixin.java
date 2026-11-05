package com.terrain.fpsprobe.mixin;
import com.terrain.fpsprobe.RenderStageProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets="me.cortex.voxy.client.core.AbstractRenderPipeline", remap=false)
abstract class StageVoxyMixin {
    @Inject(method="runPipeline", at=@At("HEAD"), require=1)
    private void td$begin(CallbackInfo ci) { RenderStageProfiler.begin("voxy.runPipeline"); }
    @Inject(method="runPipeline", at=@At("RETURN"), require=1)
    private void td$end(CallbackInfo ci) { RenderStageProfiler.end("voxy.runPipeline"); }
}
