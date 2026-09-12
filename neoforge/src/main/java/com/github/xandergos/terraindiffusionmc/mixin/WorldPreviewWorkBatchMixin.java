package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.preview.WorldPreviewScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/** Holds the bounded scheduler's generation lock through World Preview's final publication call. */
@Pseudo
@Mixin(targets = "com.caeruleusTait.world.preview.backend.worker.WorkBatch", remap = false)
public abstract class WorldPreviewWorkBatchMixin {
    @Redirect(method = "process", at = @At(value = "INVOKE",
            target = "Lcom/caeruleusTait/world/preview/backend/worker/WorkBatch;applyChunkResult(Ljava/util/List;)V",
            remap = false), require = 1, remap = false)
    private void terrainDiffusion$publishCurrentGeneration(@Coerce Object batch, List<?> results) {
        // Invoking reflectively reaches the original private method, rather than this transformed call site.
        // The scheduler lock remains held until that method returns, including exceptional exits.
        WorldPreviewScheduler.publishBatch(batch, results);
    }
}
