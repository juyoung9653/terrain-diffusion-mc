package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.ShoreClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeClassifier;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Preserves the terrain-selected shore substrate when Streams selects point-bar sand. */
public final class StreamsBankMaterialPolicy {
    private static final short GRAVEL_BEACH = terrainId("terralith:gravel_beach");
    private static final FastNoiseLite POINT_BAR_SEDIMENT = pointBarSediment();
    private static final AtomicLong GRAVEL_SUBSTITUTIONS = new AtomicLong();
    private static final AtomicLong STONE_SUBSTITUTIONS = new AtomicLong();
    private static final AtomicLong COHERENT_POINT_BAR_EVALUATIONS = new AtomicLong();

    private StreamsBankMaterialPolicy() {
    }

    /**
     * Uses the same tile key and ecology field that assigned the original TD surface biome.
     * Streams may have subsequently rebiomed the column to {@code minecraft:river}, so the
     * current world biome is intentionally not consulted here.
     */
    public static BlockState preserveNaturalShoreSubstrate(WorldGenLevel level, int blockX, int blockZ,
                                                            int zone, boolean pointBar, BlockState proposed) {
        boolean sand = proposed.is(Blocks.SAND);
        boolean coherentPointBarCandidate = pointBar && zone == 0 && proposed.is(Blocks.GRAVEL);
        if ((!sand && !coherentPointBarCandidate)
                || !(level.getLevel().getChunkSource().getGenerator().getBiomeSource()
                instanceof TerrainDiffusionBiomeSource source)
                || !source.naturalBiomes()) {
            return proposed;
        }

        int size = TerrainDiffusionConfig.tileSize();
        int tileX = Math.floorDiv(blockX, size) * size;
        int tileZ = Math.floorDiv(blockZ, size) * size;
        LocalTerrainProvider.HeightmapData tile = LocalTerrainProvider.getInstance()
                .fetchHeightmap(tileZ, tileX, tileZ + size, tileX + size);
        if (tile == null || tile.ecology == null) return proposed;

        short terrainBiomeId = tile.ecology.terrainBiomeId(blockX - tileX, blockZ - tileZ);
        if (terrainBiomeId == GRAVEL_BEACH) {
            if (sand) {
                GRAVEL_SUBSTITUTIONS.incrementAndGet();
                return Blocks.GRAVEL.defaultBlockState();
            }
            return proposed;
        }
        if (terrainBiomeId == ShoreClassifier.STONY_SHORE) {
            if (sand) {
                STONE_SUBSTITUTIONS.incrementAndGet();
                return Blocks.STONE.defaultBlockState();
            }
            return proposed;
        }
        if (pointBar && zone == 0) {
            COHERENT_POINT_BAR_EVALUATIONS.incrementAndGet();
            return POINT_BAR_SEDIMENT.GetNoise(blockX, blockZ) > .10f
                    ? Blocks.SAND.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
        }
        return proposed;
    }

    public static long gravelSubstitutions() {
        return GRAVEL_SUBSTITUTIONS.get();
    }

    public static long stoneSubstitutions() {
        return STONE_SUBSTITUTIONS.get();
    }

    public static long coherentPointBarEvaluations() {
        return COHERENT_POINT_BAR_EVALUATIONS.get();
    }

    private static FastNoiseLite pointBarSediment() {
        FastNoiseLite noise = new FastNoiseLite(419731);
        noise.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        noise.SetFrequency(1f / 96f);
        return noise;
    }

    private static short terrainId(String id) {
        int index = TerralithBiomeClassifier.biomeNames().indexOf(id);
        if (index < 0) throw new IllegalStateException("Missing terrain biome " + id);
        return (short) (index + 1);
    }
}
