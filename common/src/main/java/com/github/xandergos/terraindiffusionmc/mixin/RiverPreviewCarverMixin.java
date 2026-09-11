package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.river.RiverPreviewService;
import com.github.xandergos.terraindiffusionmc.world.river.WorldRiverPreviewState;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the bounded preview only after Minecraft has finished its carver pass. */
@Mixin(NoiseBasedChunkGenerator.class)
public class RiverPreviewCarverMixin {
    @Inject(method = "applyCarvers", at = @At("TAIL"))
    private void terrainDiffusion$applyRiverPreview(WorldGenRegion region, long seed, RandomState randomState,
                                                    BiomeManager biomeManager, StructureManager structureManager,
                                                    ChunkAccess chunk, GenerationStep.Carving carving,
                                                    CallbackInfo ci) {
        NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) (Object) this;
        if (!(generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource)
                || !WorldRiverPreviewState.settings(region.getLevel()).enabled()) {
            return;
        }
        RiverPreviewService.apply(region, chunk);
    }
}
