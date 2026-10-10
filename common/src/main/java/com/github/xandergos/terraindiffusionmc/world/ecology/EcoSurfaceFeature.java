package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
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

import java.util.HashMap;
import java.util.Map;

/** Applies coherent climate-driven soil changes without changing terrain height or waterways. */
public final class EcoSurfaceFeature extends Feature<NoneFeatureConfiguration> {
    private static final short GRAVEL_BEACH = (short) (com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeClassifier
            .biomeNames().indexOf("terralith:gravel_beach") + 1);
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
        BoundedSurfaceSampler boundarySamples = new BoundedSurfaceSampler();
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
                boolean dryGravelShore = com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState.version(level.getLevel()) >= 10
                        && surfaceSample.terrainBiomeId() == GRAVEL_BEACH
                        && FeatureEcologyMath.dryMineralShore(sample.temperature(), sample.moisture());
                ResourceLocation currentBiome = biomeId(level, top);
                // River biomes occupy quart cells and can cover adjacent dry land.
                // Resolve those columns from their original land climate, without
                // repainting retained ocean/shore biomes or any submerged surface.
                boolean riverOverlay = com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState.version(level.getLevel()) >= 10
                        && surfaceSample.terrainBiomeId() == 0 && sample.biomeId() != 0
                        && level.getBiome(top).is(net.minecraft.tags.BiomeTags.IS_RIVER);
                if (!dryShore && !dryGravelShore && !riverOverlay && (currentBiome == null || !isEcologyBiomeId(currentBiome))) continue;
                BlockState surface = level.getBlockState(top);
                if (!isExposedSoil(surface)) continue;
                if (!dryShore && !riverOverlay && surfaceSample.terrainBiomeId() == 0) {
                    sample = boundaryClimate(level, top, currentBiome, surfaceSample,
                            boundarySamples, level.getSeed());
                }
                FeatureEcologyMath.SurfaceMaterial material = dryGravelShore ? FeatureEcologyMath.SurfaceMaterial.GRAVEL
                        : dryShore ? FeatureEcologyMath.SurfaceMaterial.SAND
                        : FeatureEcologyMath.surfaceMaterial(
                        sample.temperature(), sample.moisture(), sample.slope(), sample.treeCover(), surfaceSample.elevation(),
                        level.getSeed(), x, z, WorldScaleManager.getCurrentScale());
                if (material == FeatureEcologyMath.SurfaceMaterial.SAND) {
                    changed |= placeSupportedSand(level, top);
                } else if (dryGravelShore) {
                    changed |= placeSupportedMineral(level, top, Blocks.GRAVEL.defaultBlockState(), Blocks.STONE.defaultBlockState());
                } else {
                    BlockState target = switch (material) {
                        case COARSE_DIRT -> Blocks.COARSE_DIRT.defaultBlockState();
                        case PODZOL -> Blocks.PODZOL.defaultBlockState();
                        case GRAVEL -> Blocks.GRAVEL.defaultBlockState();
                        case STONE -> Blocks.STONE.defaultBlockState();
                        default -> Blocks.GRASS_BLOCK.defaultBlockState();
                    };
                    changed |= replace(level, top, target);
                }
            }
        }
        return changed;
    }

    private static ResourceLocation biomeId(WorldGenLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey()
                .map(key -> key.location())
                .orElse(null);
    }

    private static boolean isEcologyBiomeId(ResourceLocation id) {
        return id.getNamespace().equals(TerrainDiffusionLifecycle.MOD_ID) && id.getPath().startsWith("ecology/");
    }

    private static EcologyClassifier.Sample boundaryClimate(WorldGenLevel level, BlockPos target,
                                                              ResourceLocation targetBiome,
                                                              NaturalEcology.SurfaceSample targetSample,
                                                              BoundedSurfaceSampler samples, long seed) {
        SurfaceBoundaryDither.Offset offset = SurfaceBoundaryDither.offset(seed, target.getX(), target.getZ());
        if (offset.x() == 0 && offset.z() == 0) return targetSample.climate();
        int donorX = target.getX() + offset.x(), donorZ = target.getZ() + offset.z();
        ResourceLocation donorBiome = biomeId(level, new BlockPos(donorX, target.getY(), donorZ));
        boolean crossesLandBoundary = donorBiome != null && isEcologyBiomeId(donorBiome)
                && !donorBiome.equals(targetBiome);
        if (!crossesLandBoundary) return targetSample.climate();

        NaturalEcology.SurfaceSample donor = SurfaceBoundaryDither.sampleDonorWithinReferenceTile(
                target.getX(), target.getZ(), donorX, donorZ, TerrainDiffusionConfig.tileSize(),
                () -> samples.sample(donorX, donorZ));
        if (donor == null || donor.terrainBiomeId() != 0 || donor.climate().biomeId() == 0)
            return targetSample.climate();
        return SurfaceBoundaryDither.selectClimate(true, targetSample.climate(), targetSample.elevation(),
                donor.climate(), donor.elevation());
    }

    /**
     * Reuses the same aligned biome/ecology tiles requested by the biome source. The caller rejects
     * cross-reference-tile donors before this cache can request them.
     */
    private static final class BoundedSurfaceSampler {
        private final int tileSize = LocalTerrainProvider.sampleTileSize();
        private final Map<Long, LocalTerrainProvider.HeightmapData> tiles = new HashMap<>();

        NaturalEcology.SurfaceSample sample(int x, int z) {
            int startX = Math.floorDiv(x, tileSize) * tileSize;
            int startZ = Math.floorDiv(z, tileSize) * tileSize;
            long key = ((long) startX << 32) ^ (startZ & 0xffffffffL);
            LocalTerrainProvider.HeightmapData data = tiles.get(key);
            if (data == null) {
                data = LocalTerrainProvider.getInstance().fetchBiomeHeightmap(
                        startZ, startX, startZ + tileSize, startX + tileSize);
                tiles.put(key, data);
            }
            if (data == null || data.ecology == null)
                throw new IllegalStateException("Natural ecology boundary tile unavailable");
            int localX = x - startX, localZ = z - startZ;
            return new NaturalEcology.SurfaceSample(data.ecology.sample(localX, localZ),
                    data.ecology.terrainBiomeId(localX, localZ), data.heightmap[localZ][localX],
                    data.ecology.snowRetention(localX, localZ));
        }
    }

    private static boolean placeSupportedSand(WorldGenLevel level, BlockPos top) {
        return placeSupportedMineral(level, top, Blocks.SAND.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState());
    }

    private static boolean placeSupportedMineral(WorldGenLevel level, BlockPos top, BlockState mineral, BlockState rock) {
        int layers = 0;
        BlockPos cursor = top;
        while (layers < 3 && isExposedSoil(level.getBlockState(cursor)) && level.getFluidState(cursor).isEmpty()) {
            layers++;
            cursor = cursor.below();
        }
        if (layers == 0) return false;
        BlockState support = level.getBlockState(cursor);
        boolean sandstoneBase = !support.isFaceSturdy(level, cursor, Direction.UP);
        if (sandstoneBase && (com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState.version(level.getLevel()) < 10
                || !support.isAir())) return false;
        boolean changed = false;
        cursor = top;
        for (int layer = 0; layer < layers; layer++) {
            // Keep a carved roof's existing shape: stabilize its lowest soil cell,
            // never fill the cave below it or leave a green roof in a mineral biome.
            // A one-cell roof exposes sandstone, as a natural rock overhang would.
            changed |= replace(level, cursor, sandstoneBase && layer == layers - 1
                    ? rock : mineral);
            cursor = cursor.below();
        }
        // Neighbour decoration can spill here before this chunk's surface pass.
        // WG heightmaps stop updating after CARVERS; flags=2 does not run plant survival.
        // Remove only unsupported grass/ferns, never desert shrubs or aquatic plants.
        if (level.getBlockState(top).equals(mineral) || level.getBlockState(top).equals(rock))
            changed |= removeUnsupportedGrass(level, top.above());
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
