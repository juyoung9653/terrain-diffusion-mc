package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.ShoreClassifier;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** Applies coherent climate-driven soil changes without changing terrain height or waterways. */
public final class EcoSurfaceFeature extends Feature<NoneFeatureConfiguration> {
    private static final TagKey<Block> GRASS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("greeneries", "grass"));
    private static final TagKey<Block> FERN = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("greeneries", "fern"));
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
                if (surfaceY > topY
                        || !level.getFluidState(top).isEmpty() || !level.getFluidState(top.above()).isEmpty()) continue;
                NaturalEcology.SurfaceSample surfaceSample = NaturalEcology.surfaceSample(generator, x, z);
                if (surfaceSample == null) continue;
                EcologyClassifier.Sample sample = surfaceSample.climate();
                boolean dryShore = surfaceSample.terrainBiomeId() == ShoreClassifier.BEACH
                        && FeatureEcologyMath.dryMineralShore(sample.temperature(), sample.moisture());
                if (!dryShore && !isEcologyBiome(level, top)) continue;
                BlockState surface = level.getBlockState(top);
                if (!isExposedSoil(surface)) continue;
                FeatureEcologyMath.SurfaceMaterial material = dryShore ? FeatureEcologyMath.SurfaceMaterial.SAND
                        : FeatureEcologyMath.surfaceMaterial(
                        sample.temperature(), sample.moisture(), sample.slope(), sample.treeCover(), surfaceSample.elevation(),
                        level.getSeed(), x, z, WorldScaleManager.getCurrentScale());
                if (material == FeatureEcologyMath.SurfaceMaterial.SAND) {
                    changed |= placeSupportedSand(level, top);
                } else {
                    BlockState target = switch (material) {
                        case COARSE_DIRT -> Blocks.COARSE_DIRT.defaultBlockState();
                        case PODZOL -> Blocks.PODZOL.defaultBlockState();
                        default -> Blocks.GRASS_BLOCK.defaultBlockState();
                    };
                    changed |= replace(level, top, target);
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
        // Neighbour decoration can spill here before this chunk's surface pass.
        // WG heightmaps stop updating after CARVERS; flags=2 does not run plant survival.
        // Remove only unsupported grass/ferns, never desert shrubs or aquatic plants.
        if (level.getBlockState(top).is(Blocks.SAND)) changed |= removeUnsupportedGrass(level, top.above());
        return changed;
    }

    private static boolean removeUnsupportedGrass(WorldGenLevel level, BlockPos pos) {
        BlockState plant = level.getBlockState(pos);
        if (!(plant.is(Blocks.SHORT_GRASS) || plant.is(Blocks.TALL_GRASS)
                || plant.is(Blocks.FERN) || plant.is(Blocks.LARGE_FERN) || plant.is(GRASS) || plant.is(FERN)))
            return false;
        if (plant.hasProperty(DoublePlantBlock.HALF)
                && plant.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER) {
            BlockState upper = level.getBlockState(pos.above());
            if (upper.is(plant.getBlock()) && upper.hasProperty(DoublePlantBlock.HALF)
                    && upper.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER)
                level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 2);
        }
        return level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
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
