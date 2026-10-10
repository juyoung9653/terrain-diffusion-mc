package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.structure.StructurePlacementRuntime;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class StructureTerrainSurfaceMixin {
    @Inject(method="buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V", at=@At("HEAD"))
    private void terrainDiffusion$shapeFoundations(WorldGenRegion region, StructureManager manager, RandomState random,
            ChunkAccess chunk, CallbackInfo ci) {
        StructurePlacementRuntime.surface(region,(NoiseBasedChunkGenerator)(Object)this,random,chunk);
    }
}
