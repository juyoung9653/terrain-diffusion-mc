package com.github.xandergos.terraindiffusionmc.diagnostic.mixin;

import com.github.xandergos.terraindiffusionmc.diagnostic.TerrainReplayStore;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Intercepts computation only; native cache admission and publication remain intact. */
@Mixin(value = LocalTerrainProvider.class, remap = false)
public abstract class TerrainReplayMixin {
    @Shadow private long seed;

    @Inject(method = "handle1x", at = @At("HEAD"), cancellable = true)
    private void replay1x(int i1, int j1, int i2, int j2, CallbackInfoReturnable<HeightmapData> result) {
        HeightmapData data = TerrainReplayStore.replay(seed, i1, j1, i2, j2, 1);
        if (data != null) result.setReturnValue(data);
    }
    @Inject(method = "handle1x", at = @At("RETURN"))
    private void record1x(int i1, int j1, int i2, int j2, CallbackInfoReturnable<HeightmapData> result) {
        TerrainReplayStore.record(seed, i1, j1, i2, j2, 1, result.getReturnValue());
    }
    @Inject(method = "handleUpsampled", at = @At("HEAD"), cancellable = true)
    private void replayUpsampled(int i1, int j1, int i2, int j2, int scale, CallbackInfoReturnable<HeightmapData> result) {
        HeightmapData data = TerrainReplayStore.replay(seed, i1, j1, i2, j2, scale);
        if (data != null) result.setReturnValue(data);
    }
    @Inject(method = "handleUpsampled", at = @At("RETURN"))
    private void recordUpsampled(int i1, int j1, int i2, int j2, int scale, CallbackInfoReturnable<HeightmapData> result) {
        TerrainReplayStore.record(seed, i1, j1, i2, j2, scale, result.getReturnValue());
    }
}
