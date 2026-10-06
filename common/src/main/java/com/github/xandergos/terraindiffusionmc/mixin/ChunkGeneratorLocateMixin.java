package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.AsyncStructureLocate;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorLocateMixin {
    @WrapOperation(method = "getStructureGeneratingAt", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/LevelReader;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;)Lnet/minecraft/world/level/chunk/ChunkAccess;"))
    private static ChunkAccess terrainDiffusion$awaitLocateOffThread(LevelReader reader, int x, int z,
            ChunkStatus status, Operation<ChunkAccess> original) {
        if (reader instanceof ServerLevel level && !level.getServer().isSameThread()
                && status == ChunkStatus.STRUCTURE_STARTS
                && level.getChunkSource().getGenerator().getBiomeSource().getClass() == TerrainDiffusionBiomeSource.class) {
            return AsyncStructureLocate.getChunk(level, x, z);
        }
        return original.call(reader, x, z, status);
    }
}
