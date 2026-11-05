package com.terrain.fpsprobe.mixin;
import com.terrain.fpsprobe.RenderStageProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Pseudo
@Mixin(targets="com.purescale.client.classic.PurescaleClassicNative", remap=false)
abstract class StageClassicNativeMixin {
 @Inject(method="reconstruct", at=@At("HEAD"), require=1, remap=false)
 private static void td$reconstruct$begin(CallbackInfoReturnable<Boolean> ci) { RenderStageProfiler.begin("PurescaleClassicNative.reconstruct"); }
 @Inject(method="reconstruct", at=@At("RETURN"), require=1, remap=false)
 private static void td$reconstruct$end(CallbackInfoReturnable<Boolean> ci) { RenderStageProfiler.end("PurescaleClassicNative.reconstruct"); }
 @Inject(method="afterUpscale", at=@At("HEAD"), require=1, remap=false)
 private static void td$afterUpscale$begin(CallbackInfo ci) { RenderStageProfiler.begin("PurescaleClassicNative.afterUpscale"); }
 @Inject(method="afterUpscale", at=@At("RETURN"), require=1, remap=false)
 private static void td$afterUpscale$end(CallbackInfo ci) { RenderStageProfiler.end("PurescaleClassicNative.afterUpscale"); }
 @Inject(method="finishFrame", at=@At("HEAD"), require=1, remap=false)
 private static void td$finishFrame$begin(CallbackInfo ci) { RenderStageProfiler.begin("PurescaleClassicNative.finishFrame"); }
 @Inject(method="finishFrame", at=@At("RETURN"), require=1, remap=false)
 private static void td$finishFrame$end(CallbackInfo ci) { RenderStageProfiler.end("PurescaleClassicNative.finishFrame"); }
 @Inject(method="endFrame", at=@At("HEAD"), require=1, remap=false)
 private static void td$endFrame$begin(CallbackInfo ci) { RenderStageProfiler.begin("PurescaleClassicNative.endFrame"); }
 @Inject(method="endFrame", at=@At("RETURN"), require=1, remap=false)
 private static void td$endFrame$end(CallbackInfo ci) { RenderStageProfiler.end("PurescaleClassicNative.endFrame"); }
 @Inject(method="geometry", at=@At("HEAD"), require=1, remap=false)
 private static void td$geometry$begin(CallbackInfo ci) { RenderStageProfiler.begin("PurescaleClassicNative.geometry"); }
 @Inject(method="geometry", at=@At("RETURN"), require=1, remap=false)
 private static void td$geometry$end(CallbackInfo ci) { RenderStageProfiler.end("PurescaleClassicNative.geometry"); }
}
