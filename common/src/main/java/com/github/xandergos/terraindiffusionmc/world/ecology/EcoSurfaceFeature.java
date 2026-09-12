package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** Applies broad moisture-driven soil changes without changing terrain height or waterways. */
public final class EcoSurfaceFeature extends Feature<NoneFeatureConfiguration> {
    public EcoSurfaceFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        ChunkGenerator generator = context.chunkGenerator();
        int startX = context.origin().getX();
        int startZ = context.origin().getZ();
        boolean changed = false;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int x = startX + localX;
                int z = startZ + localZ;
                int topY = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
                BlockPos top = new BlockPos(x, topY, z);
                if (!isEcologyBiome(level, top) || surfaceY > topY
                        || !level.getFluidState(top).isEmpty() || !level.getFluidState(top.above()).isEmpty()) continue;
                EcologyClassifier.Sample sample = NaturalEcology.sample(generator, x, z);
                if (sample == null) continue;
                BlockState surface = level.getBlockState(top);
                if (!isExposedSoil(surface)) continue;
                float moisture = FeatureEcologyMath.surfaceMoisture(sample.moisture(), level.getSeed(), x, z,
                        WorldScaleManager.getCurrentScale());
                if (moisture < 0.22F) {
                    changed |= placeSupportedSand(level, top);
                } else if (moisture < 0.40F) {
                    changed |= replace(level, top, Blocks.COARSE_DIRT.defaultBlockState());
                } else if (!surface.is(Blocks.GRASS_BLOCK)) {
                    changed |= replace(level, top, Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
        }
        return changed;
    }

    private static boolean isEcologyBiome(WorldGenLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey()
                .map(key -> key.location())
                .filter(EcoSurfaceFeature::isEcologyBiomeId)
                .isPresent();
    }

    private static boolean isEcologyBiomeId(ResourceLocation id) {
        return id.getNamespace().equals(TerrainDiffusionLifecycle.MOD_ID) && id.getPath().startsWith("ecology/");
    }

    private static boolean placeSupportedSand(WorldGenLevel level, BlockPos top) {
        int layers = 0;
        BlockPos cursor = top;
        while (layers < 3 && isExposedSoil(level.getBlockState(cursor)) && level.getFluidState(cursor).isEmpty()) {
            layers++;
            cursor = cursor.below();
        }
        if (layers == 0 || !level.getBlockState(cursor).isFaceSturdy(level, cursor, Direction.UP)) return false;
        boolean changed = false;
        cursor = top;
        for (int layer = 0; layer < layers; layer++) {
            changed |= replace(level, cursor, Blocks.SAND.defaultBlockState());
            cursor = cursor.below();
        }
        return changed;
    }

    private static boolean isExposedSoil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.SAND);
    }

    private static boolean replace(WorldGenLevel level, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos).equals(state)) return false;
        return level.setBlock(pos, state, 2);
    }
}
