package com.terrain.fpsprobe.mixin;

import com.terrain.fpsprobe.FpsProbeClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The verified 1.21.1 bytecode calls GLFW.glfwSwapBuffers(long) exactly once. */
@Mixin(targets = "com.mojang.blaze3d.systems.RenderSystem")
abstract class RenderSystemFlipMixin {
    @Inject(method = "flipFrame(J)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", shift = At.Shift.BEFORE), require = 1)
    private static void td$beforeSwap(long window, CallbackInfo ci) { FpsProbeClient.swapBefore(); }
    @Inject(method = "flipFrame(J)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", shift = At.Shift.AFTER), require = 1)
    private static void td$afterSwap(long window, CallbackInfo ci) { FpsProbeClient.swapAfter(); }
}
