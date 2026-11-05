package com.terrain.fpsprobe.mixin;
import com.terrain.fpsprobe.RenderStageProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Pseudo
@Mixin(targets="com.purescale.client.photon.PurescalePhotonBridge", remap=false)
abstract class StagePhotonBridgeMixin {
 @Inject(method="beforeQuad", at=@At("HEAD"), require=1, remap=false)
 private static void td$beforeQuad$begin(CallbackInfo ci) { RenderStageProfiler.begin("PurescalePhotonBridge.beforeQuad"); }
 @Inject(method="beforeQuad", at=@At("RETURN"), require=1, remap=false)
 private static void td$beforeQuad$end(CallbackInfo ci) { RenderStageProfiler.end("PurescalePhotonBridge.beforeQuad"); }
 @Inject(method="afterQuad", at=@At("HEAD"), require=1, remap=false)
 private static void td$afterQuad$begin(CallbackInfo ci) { RenderStageProfiler.begin("PurescalePhotonBridge.afterQuad"); }
 @Inject(method="afterQuad", at=@At("RETURN"), require=1, remap=false)
 private static void td$afterQuad$end(CallbackInfo ci) { RenderStageProfiler.end("PurescalePhotonBridge.afterQuad"); }
 @Inject(method="pack", at=@At("HEAD"), require=1, remap=false)
 private static void td$pack$begin(CallbackInfo ci) { RenderStageProfiler.begin("PurescalePhotonBridge.pack"); }
 @Inject(method="pack", at=@At("RETURN"), require=1, remap=false)
 private static void td$pack$end(CallbackInfo ci) { RenderStageProfiler.end("PurescalePhotonBridge.pack"); }
 @Inject(method="makeMotion", at=@At("HEAD"), require=1, remap=false)
 private static void td$makeMotion$begin(CallbackInfo ci) { RenderStageProfiler.begin("PurescalePhotonBridge.makeMotion"); }
 @Inject(method="makeMotion", at=@At("RETURN"), require=1, remap=false)
 private static void td$makeMotion$end(CallbackInfo ci) { RenderStageProfiler.end("PurescalePhotonBridge.makeMotion"); }
}
