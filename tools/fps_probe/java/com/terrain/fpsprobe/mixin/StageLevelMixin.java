package com.terrain.fpsprobe.mixin;
import com.terrain.fpsprobe.RenderStageProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="net.minecraft.client.renderer.LevelRenderer", remap=false)
abstract class StageLevelMixin {
    @Inject(method="renderLevel", at=@At("HEAD"), require=1)
    private void td$begin(CallbackInfo ci) { RenderStageProfiler.begin("minecraft.level"); }
    @Inject(method="renderLevel", at=@At("RETURN"), require=1)
    private void td$end(CallbackInfo ci) { RenderStageProfiler.end("minecraft.level"); }
}
