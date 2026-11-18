package com.terrain.chunkprofile.mixin;

import com.terrain.chunkprofile.GenerationStageTrace;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fixture-only snapshots around vanilla's surface and carver phases. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class GenerationStageTraceMixin {
    @Inject(method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V", at = @At("HEAD"))
    private void traceNoiseComplete(WorldGenRegion region, StructureManager structures, RandomState random,
                                    ChunkAccess chunk, CallbackInfo ci) {
        GenerationStageTrace.capture("noise-complete", chunk);
    }

    @Inject(method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V", at = @At("TAIL"))
    private void traceSurfaceComplete(WorldGenRegion region, StructureManager structures, RandomState random,
                                      ChunkAccess chunk, CallbackInfo ci) {
        GenerationStageTrace.capture("surface-complete", chunk);
    }

    @Inject(method = "applyCarvers", at = @At("HEAD"))
    private void traceCarversBegin(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes,
                                   StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step,
                                   CallbackInfo ci) {
        if (step == GenerationStep.Carving.AIR) GenerationStageTrace.capture("carvers-begin", chunk);
    }

    @Inject(method = "applyCarvers", at = @At("TAIL"))
    private void traceCarversComplete(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes,
                                      StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step,
                                      CallbackInfo ci) {
        if (step == GenerationStep.Carving.AIR) GenerationStageTrace.capture("carvers-complete", chunk);
    }
}
