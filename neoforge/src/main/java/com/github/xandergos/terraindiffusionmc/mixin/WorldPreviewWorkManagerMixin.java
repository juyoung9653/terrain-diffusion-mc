package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.preview.WorldPreviewScheduler;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces only Terrain Diffusion's unbounded World Preview queue with its bounded tile queue. */
@Pseudo
@Mixin(targets = "com.caeruleusTait.world.preview.backend.WorkManager", remap = false)
public abstract class WorldPreviewWorkManagerMixin {
    @Inject(method = "queueRangeReal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void terrainDiffusion$queueBoundedTiles(BlockPos from, BlockPos to, CallbackInfo callback) {
        if (WorldPreviewScheduler.queueRange(this, from, to)) callback.cancel();
    }

    @Inject(method = "cancel", at = @At("HEAD"), require = 1, remap = false)
    private void terrainDiffusion$closePreviewSession(CallbackInfo callback) {
        WorldPreviewScheduler.close(this);
    }

    @Inject(method = "changeWorldGenState", at = @At("HEAD"), require = 1, remap = false)
    private void terrainDiffusion$replacePreviewSessionOnWorldChange(CallbackInfo callback) {
        WorldPreviewScheduler.close(this);
    }
}
