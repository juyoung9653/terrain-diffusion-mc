package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsTerrainPolicy;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Defers Streams' startup-only origin warmup for Terrain Diffusion worlds. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.StreamsWorldgen", remap = false)
public final class StreamsWorldgenPrefetchMixin {
    @WrapWithCondition(
            method = "engine(Lnet/minecraft/world/level/WorldGenLevel;)Ldev/streamsreflowing/core/RiverEngine;",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/streamsreflowing/worldgen/StreamPrefetcher;warmWorldOrigin(Ldev/streamsreflowing/core/RiverEngine;)V",
                    remap = false),
            require = 1,
            expect = 1,
            remap = false)
    private static boolean terrainDiffusion$warmWorldOrigin(@Coerce Object engine, WorldGenLevel level) {
        if (!StreamsTerrainPolicy.useDemandDrivenGeneration(level)) {
            return true;
        }
        StreamsTerrainPolicy.recordSkippedOriginWarmup();
        return false;
    }
}
