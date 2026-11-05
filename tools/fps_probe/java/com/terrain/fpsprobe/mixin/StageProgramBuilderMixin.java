package com.terrain.fpsprobe.mixin;

import com.google.common.collect.ImmutableSet;
import com.terrain.fpsprobe.StageLabels;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records Iris's shader-pack name when its actual OpenGL program is created. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.ProgramBuilder", remap = false)
abstract class StageProgramBuilderMixin {
    @Inject(
        method = "<init>(Ljava/lang/String;ILcom/google/common/collect/ImmutableSet;)V",
        at = @At("RETURN"),
        require = 1
    )
    private void td$rememberProgram(
        String name,
        int id,
        ImmutableSet<?> ignoredReservedSamplers,
        CallbackInfo ci
    ) {
        StageLabels.program(id, name);
    }
}
