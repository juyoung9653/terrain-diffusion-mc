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

/** Audited for 2.13.8 and 2.15.2. Selects native exact sampling. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.StreamsWorldgen", remap = false)
public final class StreamsStartupMixin {
    @Inject(method = "a(Ldev/streamsreflowing/core/terrain/TerrainSampler;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/server/level/ServerLevel;J)Ldev/streamsreflowing/core/terrain/TerrainSampler;",
            at = @At("HEAD"), cancellable = true, require = 1, expect = 1, remap = false)
    private static void terrainDiffusion$selectHostTerrain(@Coerce Object host, ChunkGenerator generator,
            RandomState random, ServerLevel level, long seed, CallbackInfoReturnable<Object> cir) {
        if (StreamsStartupPolicy.useExactTerrain(level)) cir.setReturnValue(host);
    }

    // False takes Streams' existing exact-mode branch: FastCal(host, null, false, 0, null, null).
    // Its original constructor and all engine wiring remain native.
    @ModifyExpressionValue(method = {
            "a(Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeSource;Lnet/minecraft/world/level/biome/Climate$Sampler;ILdev/streamsreflowing/core/terrain/TerrainSampler;Lnet/minecraft/server/level/ServerLevel;)Ldev/streamsreflowing/worldgen/StreamsWorldgen$FastCal;",
            "a(Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeSource;Lnet/minecraft/world/level/biome/Climate$Sampler;ILdev/streamsreflowing/core/terrain/TerrainSampler;Lnet/minecraft/server/level/ServerLevel;Z)Ldev/streamsreflowing/worldgen/StreamsWorldgen$FastCal;"},
            at = @At(value = "INVOKE", target = "Ldev/streamsreflowing/platform/services/IPlatformHelper;fastTerrainEstimate()Z", remap = false),
            require = 1, expect = 1, remap = false)
    private static boolean terrainDiffusion$selectExactCalibration(boolean original,
            @Local(argsOnly = true) ServerLevel level) {
        return original && !StreamsStartupPolicy.useExactTerrain(level);
    }
}
