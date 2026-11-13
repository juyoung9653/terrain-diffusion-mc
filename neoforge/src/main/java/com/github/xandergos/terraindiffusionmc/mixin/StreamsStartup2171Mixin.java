package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsStartupPolicy;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Selects TD's exact host sampler in Streams Reflowing 2.17.1. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.StreamsWorldgen", remap = false)
public final class StreamsStartup2171Mixin {
    @Inject(
            method = "proprietary1(Ldev/streamsreflowing/core/terrain/TerrainSampler;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/server/level/ServerLevel;J)Ldev/streamsreflowing/core/terrain/TerrainSampler;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            remap = false)
    private static void terrainDiffusion$selectHostTerrain(@Coerce Object host, ChunkGenerator generator,
            RandomState random, ServerLevel level, long seed, CallbackInfoReturnable<Object> cir) {
        if (StreamsStartupPolicy.useExactTerrain(level)) {
            cir.setReturnValue(host);
        }
    }

    // The 2.17.1 calibration selector reads this platform setting before it chooses
    // an estimated terrain sampler. Exact TD terrain must retain the native OFF
    // branch, while all calibration and cache wiring remains upstream-owned.
    @ModifyExpressionValue(
            method = "proprietary1(Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeSource;Lnet/minecraft/world/level/biome/Climate$Sampler;ILdev/streamsreflowing/core/terrain/TerrainSampler;Lnet/minecraft/server/level/ServerLevel;Z)Ldev/streamsreflowing/worldgen/StreamsWorldgen$AllRightsReservedNiceJohn_001;",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/streamsreflowing/platform/services/IPlatformHelper;allRightsReserved7()Z",
                    remap = false),
            require = 1,
            expect = 1,
            remap = false)
    private static boolean terrainDiffusion$selectExactCalibration(boolean original,
            @Local(argsOnly = true) ServerLevel level) {
        return original && !StreamsStartupPolicy.useExactTerrain(level);
    }
}