package com.terrain.fpsprobe.mixin;
import com.terrain.fpsprobe.StageLabels;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.ProgramUniforms", remap = false)
abstract class StageProgramUniformsMixin {
    @Inject(method = "builder(Ljava/lang/String;I)Lnet/irisshaders/iris/gl/program/ProgramUniforms$Builder;", at = @At("HEAD"), require = 1)
    private static void td$rememberProgram(String name, int id, CallbackInfoReturnable<?> cir) { StageLabels.program(id, name); }
}
