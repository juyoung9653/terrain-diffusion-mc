package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsTerrainPolicy;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Defers Streams' speculative player plate work for Terrain Diffusion worlds. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.StreamPrefetcher", remap = false)
public final class StreamsPlayerPrefetchMixin {
    @WrapWithCondition(
            method = "onServerTick(Lnet/minecraft/server/MinecraftServer;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/streamsreflowing/worldgen/StreamPrefetcher;a(Ldev/streamsreflowing/core/RiverEngine;Lnet/minecraft/core/BlockPos;I)V",
                    remap = false),
            require = 1,
            expect = 1,
            remap = false)
    private static boolean terrainDiffusion$prefetchPlayerHorizon(
            @Coerce Object engine, BlockPos playerPosition, int radius, MinecraftServer server) {
        if (!StreamsTerrainPolicy.useDemandDrivenGeneration(server.overworld())) {
            return true;
        }
        StreamsTerrainPolicy.recordSkippedPlayerPrefetch();
        return false;
    }

    @WrapWithCondition(
            method = "onServerTick(Lnet/minecraft/server/MinecraftServer;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/streamsreflowing/worldgen/StreamPrefetcher;a(Ldev/streamsreflowing/core/RiverEngine;Ldev/streamsreflowing/core/plate/PlateKey;I)V",
                    remap = false),
            require = 2,
            expect = 2,
            remap = false)
    private static boolean terrainDiffusion$schedulePlayerPlate(
            @Coerce Object engine, @Coerce Object plateKey, int priority, MinecraftServer server) {
        if (!StreamsTerrainPolicy.useDemandDrivenGeneration(server.overworld())) {
            return true;
        }
        StreamsTerrainPolicy.recordSkippedPlayerPrefetch();
        return false;
    }

    @WrapWithCondition(
            method = "onServerTick(Lnet/minecraft/server/MinecraftServer;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/streamsreflowing/worldgen/StreamPrefetcher;a(Ldev/streamsreflowing/core/RiverEngine;Lnet/minecraft/server/level/ServerLevel;I)V",
                    remap = false),
            require = 1,
            expect = 1,
            remap = false)
    private static boolean terrainDiffusion$prefetchPlayerRadius(
            @Coerce Object engine, ServerLevel level, int radius, MinecraftServer server) {
        if (!StreamsTerrainPolicy.useDemandDrivenGeneration(level)) {
            return true;
        }
        StreamsTerrainPolicy.recordSkippedPlayerPrefetch();
        return false;
    }

    @WrapWithCondition(
            method = "a(Lnet/minecraft/server/level/ServerLevel;Ldev/streamsreflowing/core/RiverEngine;Lnet/minecraft/server/MinecraftServer;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/streamsreflowing/worldgen/StreamPrefetcher;submitGridBuild(Lnet/minecraft/server/level/ServerLevel;Ldev/streamsreflowing/core/RiverEngine;Ldev/streamsreflowing/core/river/RiverFlowField$BiomeClass;Ldev/streamsreflowing/core/river/RiverFlowField$OrientationField;ZIIZLjava/lang/Runnable;)V",
                    remap = false),
            require = 1,
            expect = 1,
            remap = false)
    private static boolean terrainDiffusion$flowLoadedChunkOnly(
            ServerLevel level, @Coerce Object engine, @Coerce Object biomeClass, @Coerce Object orientation,
            boolean flowVanillaRivers, int chunkX, int chunkZ, boolean urgent, Runnable callback) {
        if (!StreamsTerrainPolicy.useDemandDrivenGeneration(level)
                || level.getChunkSource().hasChunk(chunkX, chunkZ)) {
            return true;
        }
        StreamsTerrainPolicy.recordSkippedUnloadedFlowGrid();
        return false;
    }
}
