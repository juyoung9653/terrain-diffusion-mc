package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsSamplerPriority;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Streams 2.13.8 demanded builds also run on the prefetch executor. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.core.build.BuildGate", remap = false)
public final class StreamsDemandPriorityMixin {
    @WrapOperation(method = "asDemanded(Ljava/lang/Runnable;)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"),
            require = 1, expect = 1, remap = false)
    private static void terrainDiffusion$demanded(Runnable action, Operation<Void> original) {
        StreamsSamplerPriority.run(0, () -> original.call(action));
    }
}
