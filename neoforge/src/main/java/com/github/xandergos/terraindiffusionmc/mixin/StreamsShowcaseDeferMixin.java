package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Avoids Streams' CARVERS/FEATURES plate deferral in an ecology showcase. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.ChunkStepDefer", remap = false)
public final class StreamsShowcaseDeferMixin {
    @Inject(
            method = "defer(Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/world/level/chunk/status/WorldGenContext;Lnet/minecraft/util/StaticCache2D;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"), cancellable = true, require = 1, expect = 1, remap = false)
    private static void terrainDiffusion$skipStreamsDeferForShowcase(
            ChunkStep step, WorldGenContext context, StaticCache2D<?> cache, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
        if (isEcologyShowcase(context.level())) {
            callback.setReturnValue(null);
        }
    }

    private static boolean isEcologyShowcase(ServerLevel level) {
        return level.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource source
                && !source.ecologyShowcase().isEmpty();
    }
}
