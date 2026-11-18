package com.terrain.chunkprofile.mixin;

import com.terrain.chunkprofile.GenerationStageTrace;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fixture-only snapshots immediately before and after placed features. */
@Mixin(ChunkGenerator.class)
public abstract class GenerationFeatureStageTraceMixin {
    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    private void traceFeaturesBegin(WorldGenLevel level, ChunkAccess chunk, StructureManager structures,
                                    CallbackInfo ci) {
        GenerationStageTrace.capture("features-begin", chunk);
    }

    @Inject(method = "applyBiomeDecoration", at = @At("TAIL"))
    private void traceFeaturesComplete(WorldGenLevel level, ChunkAccess chunk, StructureManager structures,
                                       CallbackInfo ci) {
        GenerationStageTrace.capture("features-complete", chunk);
    }
}
