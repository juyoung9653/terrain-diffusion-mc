package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.ShoreClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.WetlandEcology;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Applies climate-driven soils before vegetation, including naturally flooded wetland beds. */
public final class EcoSurfaceFeature extends Feature<NoneFeatureConfiguration> {
    private static final short GRAVEL_BEACH = (short) (com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeClassifier
            .biomeNames().indexOf("terralith:gravel_beach") + 1);
    private static final TagKey<Block> GRASS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("greeneries", "grass"));
    private static final TagKey<Block> FERN = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("greeneries", "fern"));
    private static final ResourceLocation IW_SANDY_DIRT =
            ResourceLocation.fromNamespaceAndPath("immersive_weathering", "sandy_dirt");
    private static final ResourceLocation IW_GRASSY_SANDY_DIRT =
            ResourceLocation.fromNamespaceAndPath("immersive_weathering", "grassy_sandy_dirt");
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
        NaturalEcology.PlannedWaterLevels plannedWater = NaturalEcology.plannedWaterLevels(generator);
        // Wetland water is now shaped with the terrain density before carvers and
        // surface generation. Do not add post-surface single-chunk pools here:
        // they cannot cross a chunk edge and make a biome-sized water table look
        // like repeated decoration.
        boolean changed = false;
        int chunkX = Math.floorDiv(startX, 16);
        int chunkZ = Math.floorDiv(startZ, 16);
        ChunkAccess owner = level.getChunk(chunkX, chunkZ);
        // A structure reference covers the whole chunk in its bookkeeping, but
        // its pieces do not.  Protect only actual template cells: an underground
        // reference must not leave a 16-by-16 natural wetland bed unpainted.
        List<BoundingBox> protectedStructurePieces = structurePieces(level, owner, chunkX, chunkZ);
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int x = startX + localX;
                int z = startZ + localZ;
                int topY = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
                BlockPos top = new BlockPos(x, topY, z);
                boolean waterCover = surfaceWaterCover(level, top, surfaceY);
                if (intersectsStructure(protectedStructurePieces, top)
                        || !level.getFluidState(top).isEmpty()) continue;
                NaturalEcology.SurfaceSample surfaceSample = NaturalEcology.surfaceSample(generator, x, z);
                if (surfaceSample == null) continue;
                EcologyClassifier.Sample sample = surfaceSample.climate();
                boolean dryShore = surfaceSample.terrainBiomeId() == ShoreClassifier.BEACH
                        && FeatureEcologyMath.dryMineralShore(sample.temperature(), sample.moisture());
                boolean dryGravelShore = com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState.version(level.getLevel()) >= 10
                        && surfaceSample.terrainBiomeId() == GRAVEL_BEACH
                        && FeatureEcologyMath.dryMineralShore(sample.temperature(), sample.moisture());
                ResourceLocation currentBiome = biomeId(level, top);
                boolean wetlandBiome = isWetlandBiomeId(currentBiome);
                int localWaterLevel = plannedWater == null ? 0 : plannedWater.levelAt(x, z);
                boolean modeledWetlandWater = localWaterLevel > 0
                        && modeledWetlandWater(level, top, localWaterLevel);
                // Quart biome assignment and boundary dithering can select a beach
                // over a column whose original climate is arid land. Keep this
                // exact beach mineral without reclassifying inland ecotones.
                boolean dryOriginalLandBeach = surfaceSample.terrainBiomeId() == 0
                        && sample.biomeId() != 0
                        && net.minecraft.world.level.biome.Biomes.BEACH.location().equals(currentBiome)
                        && FeatureEcologyMath.dryMineralShore(sample.temperature(), sample.moisture());
                // River biomes occupy quart cells and can cover adjacent dry land.
                // Resolve those columns from their original land climate, without
                // repainting retained ocean/shore biomes or any submerged surface.
                boolean riverOverlay = com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState.version(level.getLevel()) >= 10
                        && surfaceSample.terrainBiomeId() == 0 && sample.biomeId() != 0
                        && level.getBiome(top).is(net.minecraft.tags.BiomeTags.IS_RIVER);
                if (surfaceY > topY && !wetlandBiome && !modeledWetlandWater) continue;
                if (surfaceY > topY && !waterCover) continue;
                if (!dryShore && !dryGravelShore && !dryOriginalLandBeach && !riverOverlay
                        && (currentBiome == null || !isEcologyBiomeId(currentBiome))) continue;
                BlockState surface = level.getBlockState(top);
                if (!isExposedSoil(surface, true)) continue;
                // Water in a labelled wetland is part of its shaped habitat.
                // Repaint every natural submerged bed, including a three-block
                // coastal or river-connected column; the old one/two-block gate
                // left ordinary grass and dirt visibly underwater.
                if ((wetlandBiome && waterCover) || modeledWetlandWater) {
                    changed |= placeWetlandSediment(level, top, protectedStructurePieces);
                    continue;
                }
                if (!dryShore && !dryOriginalLandBeach && !riverOverlay && surfaceSample.terrainBiomeId() == 0) {
                    sample = boundaryClimate(level, top, currentBiome, surfaceSample,
                            boundarySamples, level.getSeed());
                }
                FeatureEcologyMath.SurfaceMaterial material = dryGravelShore ? FeatureEcologyMath.SurfaceMaterial.GRAVEL
                        : (dryShore || dryOriginalLandBeach) ? FeatureEcologyMath.SurfaceMaterial.SAND
                        : FeatureEcologyMath.surfaceMaterial(
                        sample.temperature(), sample.moisture(), sample.slope(), sample.treeCover(), surfaceSample.elevation(),
                        level.getSeed(), x, z, WorldScaleManager.getCurrentScale());
                // IW raw soil can occur on either side of the beach quart
                // boundary. Only admit it where the existing ecology policy
                // itself selects the mineral sandy surface.
                boolean allowImmersiveWeatheringSandySoil = material == FeatureEcologyMath.SurfaceMaterial.SAND;
                if (!isExposedSoil(surface, allowImmersiveWeatheringSandySoil)) continue;
                WetlandEcology.Weights wetland = WetlandEcology.weights(sample);
                int nearbyWaterLevel = localWaterLevel;
                if (nearbyWaterLevel <= 0 && plannedWater != null
                        && (wetlandBiome || FeatureEcologyMath.wetlandSedimentCore(
                        wetland.freshwaterSwamp(), wetland.mangrove(), sample.wetness(), sample.slope())))
                    nearbyWaterLevel = plannedWater.nearestLevel(x, z, 8);
                FeatureEcologyMath.PlannedWetlandMargin wetlandMargin =
                        FeatureEcologyMath.plannedWetlandMargin(topY, nearbyWaterLevel);
                boolean preserveMineralShore = surface.is(Blocks.SAND)
                        || material == FeatureEcologyMath.SurfaceMaterial.SAND
                        || dryShore || dryGravelShore;
                if (wetlandMargin == FeatureEcologyMath.PlannedWetlandMargin.MUD && !preserveMineralShore) {
                    // Dry wetland cores remain mud.  This happens before the
                    // climate palette so coastal mud reaches mineral sand without
                    // an artificial grass ribbon.
                    changed |= placeWetlandSediment(level, top, protectedStructurePieces);
                } else if (material == FeatureEcologyMath.SurfaceMaterial.SAND) {
                    changed |= placeSupportedSand(level, top, allowImmersiveWeatheringSandySoil);
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
                // A neighbouring patch can arrive before this chunk paints its soil.
                // Use the same shaped-water factor as the later native Greeneries writer.
                if (GreeneriesSoilPolicy.nativeWorldgenPolicyEnabled()) {
                    int coverWaterLevel = nearbyWaterLevel > 0 || plannedWater == null
                            ? nearbyWaterLevel : plannedWater.nearestLevel(x, z, 8);
                    float chance = GreeneriesSoilPolicy.acceptance(level.getBlockState(top))
                            * FeatureEcologyMath.coldGroundcoverAcceptance(surfaceSample.climate().temperature())
                            * FeatureEcologyMath.plannedWetlandGroundcoverFactor(topY, coverWaterLevel);
                    if (!GreeneriesSoilPolicy.acceptsColumn(level.getSeed(), x, z, chance))
                        changed |= removeUnsupportedGrass(level, top.above());
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

    static List<BoundingBox> structurePieces(WorldGenLevel level, ChunkAccess owner,
                                                      int chunkX, int chunkZ) {
        List<BoundingBox> boxes = new ArrayList<>();
        addStructurePieces(boxes, owner.getAllStarts().values());
        for (var entry : owner.getAllReferences().entrySet()) {
            for (long packedReference : entry.getValue()) {
                int referenceX = ChunkPos.getX(packedReference);
                int referenceZ = ChunkPos.getZ(packedReference);
                ChunkAccess source = referenceX == chunkX && referenceZ == chunkZ
                        ? owner : level.getChunk(referenceX, referenceZ, ChunkStatus.STRUCTURE_STARTS, false);
                if (source == null) continue;
                StructureStart start = source.getStartForStructure(entry.getKey());
                if (start != null) addStructurePieces(boxes, List.of(start));
            }
        }
        return boxes;
    }

    private static void addStructurePieces(List<BoundingBox> boxes, Iterable<StructureStart> starts) {
        for (StructureStart start : starts) {
            if (start == null || !start.isValid()) continue;
            for (StructurePiece piece : start.getPieces()) boxes.add(piece.getBoundingBox());
        }
    }

    static boolean intersectsStructure(List<BoundingBox> boxes, BlockPos pos) {
        for (BoundingBox box : boxes) if (box.isInside(pos)) return true;
        return false;
    }

    private static boolean isEcologyBiomeId(ResourceLocation id) {
        return id.getNamespace().equals(TerrainDiffusionLifecycle.MOD_ID) && id.getPath().startsWith("ecology/");
    }

    private static boolean isWetlandBiomeId(ResourceLocation id) {
        return id != null && id.getNamespace().equals(TerrainDiffusionLifecycle.MOD_ID)
                && (id.getPath().equals("ecology/freshwater_swamp")
                || id.getPath().equals("ecology/mangrove_swamp"));
    }

    /** True for a continuous surface-connected water column over the target bed.
     * The caller still confines its use to an actual TD wetland biome, so this
     * never claims an ordinary river, aquifer, or ocean bed.
     */
    private static boolean surfaceWaterCover(WorldGenLevel level, BlockPos bed, int surfaceY) {
        int depth = surfaceY - bed.getY();
        if (depth < 1) return false;
        for (int y = bed.getY() + 1; y <= surfaceY; y++) {
            var fluid = level.getFluidState(new BlockPos(bed.getX(), y, bed.getZ()));
            if (!fluid.is(net.minecraft.world.level.material.Fluids.WATER)) return false;
        }
        return true;
    }

    /** Confirms that a modeled wetland table is still an exposed actual water column. */
    private static boolean modeledWetlandWater(WorldGenLevel level, BlockPos bed, int exclusiveWaterLevel) {
        for (int y = bed.getY() + 1; y < exclusiveWaterLevel; y++) {
            if (!level.getBlockState(new BlockPos(bed.getX(), y, bed.getZ())).is(Blocks.WATER)) return false;
        }
        return !level.getBlockState(new BlockPos(bed.getX(), exclusiveWaterLevel, bed.getZ())).is(Blocks.WATER);
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

    private static boolean placeSupportedSand(WorldGenLevel level, BlockPos top,
                                              boolean allowImmersiveWeatheringSandySoil) {
        return placeSupportedMineral(level, top, Blocks.SAND.defaultBlockState(),
                Blocks.SANDSTONE.defaultBlockState(), allowImmersiveWeatheringSandySoil);
    }

    private static boolean placeSupportedMineral(WorldGenLevel level, BlockPos top, BlockState mineral, BlockState rock) {
        return placeSupportedMineral(level, top, mineral, rock, false);
    }

    private static boolean placeSupportedMineral(WorldGenLevel level, BlockPos top, BlockState mineral,
                                                  BlockState rock, boolean allowImmersiveWeatheringSandySoil) {
        int layers = 0;
        BlockPos cursor = top;
        while (layers < 3 && isExposedSoil(level.getBlockState(cursor), allowImmersiveWeatheringSandySoil)
                && level.getFluidState(cursor).isEmpty()) {
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

    /**
     * Saturated wetland sediment has a shallow profile rather than a one-block
     * cosmetic cap.  This stays deliberately narrower than mineral surface
     * painting: it only follows already selected mud columns and only replaces
     * contiguous natural soft soil beneath their surface.
     */
    private static boolean placeWetlandSediment(WorldGenLevel level, BlockPos top,
                                                List<BoundingBox> protectedStructurePieces) {
        boolean changed = false;
        BlockPos cursor = top;
        for (int layer = 0; layer < 3; layer++) {
            if (intersectsStructure(protectedStructurePieces, cursor)
                    || !isNaturalWetlandSedimentSoil(level.getBlockState(cursor))
                    || !level.getFluidState(cursor).isEmpty()) break;
            changed |= replace(level, cursor, Blocks.MUD.defaultBlockState());
            cursor = cursor.below();
        }
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
        return isExposedSoil(state, false);
    }

    private static boolean isExposedSoil(BlockState state, boolean allowImmersiveWeatheringSandySoil) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.SAND)
                || allowImmersiveWeatheringSandySoil && isImmersiveWeatheringSandySoil(state);
    }

    private static boolean isNaturalWetlandSedimentSoil(BlockState state) {
        return state.is(Blocks.MUD) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL);
    }

    private static boolean isImmersiveWeatheringSandySoil(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return IW_SANDY_DIRT.equals(id) || IW_GRASSY_SANDY_DIRT.equals(id);
    }

    private static boolean replace(WorldGenLevel level, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos).equals(state)) return false;
        return level.setBlock(pos, state, 2);
    }
}
