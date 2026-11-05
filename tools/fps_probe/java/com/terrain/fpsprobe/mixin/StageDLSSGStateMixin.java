package com.terrain.fpsprobe.mixin;

import com.terrain.fpsprobe.RenderPipelineState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.purescale.client.dlss.PurescaleDLSSG", remap = false)
abstract class StageDLSSGStateMixin {
    @Inject(method = "getState()[J", at = @At("RETURN"), require = 1)
    private static void tdProbe$state(CallbackInfoReturnable<long[]> ci) {
        RenderPipelineState.captureDlssgState(ci.getReturnValue());
    }
}
