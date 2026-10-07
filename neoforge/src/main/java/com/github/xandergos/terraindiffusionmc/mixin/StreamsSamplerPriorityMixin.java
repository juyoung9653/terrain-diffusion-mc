package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsSamplerPriority;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Streams 2.13.8 captures 0 (urgent) or 1 (prefetch) before submitting each row job. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.core.grid.LowResGrid$PrioritizedTask", remap = false)
public abstract class StreamsSamplerPriorityMixin {
    @Shadow @Final private int a;

    @WrapOperation(method = "run()V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"),
            require = 1, expect = 1, remap = false)
    private void terrainDiffusion$workerPriority(Runnable action, Operation<Void> original) {
        StreamsSamplerPriority.run(a, () -> original.call(action));
    }
}
