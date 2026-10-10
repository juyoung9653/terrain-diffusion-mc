package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.structure.StructurePlacementRuntime;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class StructureTerrainCarverMixin {
    @Inject(method="applyCarvers",at=@At("HEAD"))
    private void terrainDiffusion$protectSupport(WorldGenRegion region,long seed,RandomState random,
            BiomeManager biomes,StructureManager manager,ChunkAccess chunk,GenerationStep.Carving step,
            CallbackInfo ci) {
        StructurePlacementRuntime.protectCarving(region,(NoiseBasedChunkGenerator)(Object)this,random,chunk,step);
    }
}
