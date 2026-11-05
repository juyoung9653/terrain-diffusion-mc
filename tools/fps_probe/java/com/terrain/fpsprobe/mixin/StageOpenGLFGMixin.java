package com.terrain.fpsprobe.mixin;
import com.terrain.fpsprobe.RenderStageProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Pseudo
@Mixin(targets="com.purescale.client.dlss.PurescaleOpenGLFG", remap=false)
abstract class StageOpenGLFGMixin {
 @Inject(method="sr", at=@At("HEAD"), require=1, remap=false)
 private static void td$sr$begin(CallbackInfoReturnable<Boolean> ci) { RenderStageProfiler.begin("PurescaleOpenGLFG.sr"); }
 @Inject(method="sr", at=@At("RETURN"), require=1, remap=false)
 private static void td$sr$end(CallbackInfoReturnable<Boolean> ci) { RenderStageProfiler.end("PurescaleOpenGLFG.sr"); }
 @Inject(method="tag", at=@At("HEAD"), require=1, remap=false)
 private static void td$tag$begin(CallbackInfoReturnable<Boolean> ci) { RenderStageProfiler.begin("PurescaleOpenGLFG.tag"); }
 @Inject(method="tag", at=@At("RETURN"), require=1, remap=false)
 private static void td$tag$end(CallbackInfoReturnable<Boolean> ci) { RenderStageProfiler.end("PurescaleOpenGLFG.tag"); }
 @Inject(method="present", at=@At("HEAD"), require=1, remap=false)
 private static void td$present$begin(CallbackInfoReturnable<Boolean> ci) { RenderStageProfiler.begin("PurescaleOpenGLFG.present"); }
 @Inject(method="present", at=@At("RETURN"), require=1, remap=false)
 private static void td$present$end(CallbackInfoReturnable<Boolean> ci) { RenderStageProfiler.end("PurescaleOpenGLFG.present"); }
 @Inject(method="options", at=@At("HEAD"), require=1, remap=false)
 private static void td$options$begin(CallbackInfoReturnable<Boolean> ci) { RenderStageProfiler.begin("PurescaleOpenGLFG.options"); }
 @Inject(method="options", at=@At("RETURN"), require=1, remap=false)
 private static void td$options$end(CallbackInfoReturnable<Boolean> ci) { RenderStageProfiler.end("PurescaleOpenGLFG.options"); }
 @Inject(method="constants", at=@At("HEAD"), require=1, remap=false)
 private static void td$constants$begin(CallbackInfoReturnable<Boolean> ci) { RenderStageProfiler.begin("PurescaleOpenGLFG.constants"); }
 @Inject(method="constants", at=@At("RETURN"), require=1, remap=false)
 private static void td$constants$end(CallbackInfoReturnable<Boolean> ci) { RenderStageProfiler.end("PurescaleOpenGLFG.constants"); }
 @Inject(method="marker", at=@At("HEAD"), require=1, remap=false)
 private static void td$marker$begin(CallbackInfo ci) { RenderStageProfiler.begin("PurescaleOpenGLFG.marker"); }
 @Inject(method="marker", at=@At("RETURN"), require=1, remap=false)
 private static void td$marker$end(CallbackInfo ci) { RenderStageProfiler.end("PurescaleOpenGLFG.marker"); }
}
