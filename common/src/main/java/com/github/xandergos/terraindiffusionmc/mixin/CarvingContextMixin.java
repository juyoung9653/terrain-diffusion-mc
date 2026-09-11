package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.LowlandCarvingContext;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stores immutable generator identity on the per-carving-pass context. */
@Mixin(CarvingContext.class)
public class CarvingContextMixin implements LowlandCarvingContext {
    @Unique private boolean terrainDiffusion$world;
    @Unique private int terrainDiffusion$seaLevel;
    @Unique private ChunkAccess terrainDiffusion$snapshotChunk;
    @Unique private int[] terrainDiffusion$originalSurfaceY;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void terrainDiffusion$captureGenerator(NoiseBasedChunkGenerator generator, RegistryAccess registryAccess,
                                                   LevelHeightAccessor level, NoiseChunk noiseChunk,
                                                   RandomState randomState, SurfaceRules.RuleSource surfaceRule,
                                                   CallbackInfo ci) {
        this.terrainDiffusion$world = generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource;
        this.terrainDiffusion$seaLevel = generator.getSeaLevel();
    }

    @Unique
    @Override
    public boolean terrainDiffusion$isWorld() {
        return this.terrainDiffusion$world;
    }

    @Unique
    @Override
    public int terrainDiffusion$seaLevel() {
        return this.terrainDiffusion$seaLevel;
    }

    @Override
    public int terrainDiffusion$originalSurfaceY(ChunkAccess chunk, int localX, int localZ) {
        if (this.terrainDiffusion$snapshotChunk != chunk) {
            int[] heights = new int[256];
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    // ChunkAccess#getHeight already returns the highest occupied block.
                    heights[(z << 4) | x] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
                }
            }
            this.terrainDiffusion$snapshotChunk = chunk;
            this.terrainDiffusion$originalSurfaceY = heights;
        }
        return this.terrainDiffusion$originalSurfaceY[(localZ << 4) | localX];
    }
}
