package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import java.util.List;

/**
 * Late, once-per-chunk normalization of the shallow mangrove marine apron.
 * The source is a fixed kernel over immutable ecology data, so quart biome
 * ownership and pre-existing sand/dirt cannot make one-block material checks.
 */
public final class EcoCoastalSedimentFeature extends Feature<NoneFeatureConfiguration> {
    private static final ResourceLocation IMMERSIVE_WEATHERING_SILT =
            ResourceLocation.fromNamespaceAndPath("immersive_weathering", "silt");

    public EcoCoastalSedimentFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        ChunkGenerator generator = context.chunkGenerator();
        var source = TerrainDiffusionBiomeSources.find(generator.getBiomeSource());
        if (source == null || !source.naturalBiomes() || !source.ecologyShowcase().isEmpty()) return false;
        int startX = context.origin().getX();
        int startZ = context.origin().getZ();
        int chunkX = Math.floorDiv(startX, 16);
        int chunkZ = Math.floorDiv(startZ, 16);
        ChunkAccess owner = level.getChunk(chunkX, chunkZ);
        List<EcoSurfaceFeature.StructureProtection> protectedPieces =
                EcoSurfaceFeature.structurePieces(level, owner, chunkX, chunkZ);
        CoastalContext samples = new CoastalContext(generator);
        // Keep the feature load-order safe for worlds without Immersive
        // Weathering: clay is the closest vanilla non-falling fine sediment.
        BlockState silt = BuiltInRegistries.BLOCK.getOptional(IMMERSIVE_WEATHERING_SILT)
                .orElse(Blocks.CLAY).defaultBlockState();
        int seaLevel = generator.getSeaLevel();
        boolean changed = false;
        for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
            int x = startX + localX, z = startZ + localZ;
            Bed bed = shallowBed(level, x, z, seaLevel, silt);
            if (bed == null || EcoSurfaceFeature.intersectsSedimentProtection(protectedPieces, bed.pos())) continue;
            CoastalContext.Sample support = samples.sedimentSample(x, z, level.getSeed());
            // Mangrove habitat can extend inland. Its presence alone does not
            // turn an enclosed forest pool into a marine sand apron.
            if (!support.sedimentMarineBed()) {
                // A cave carver can open a riparian grass cap to aquifer water.
                // Dress only its exposed soil (or the single-block sand veneer
                // on soil); keep deep mineral deposits and natural gravel/clay.
                if (isForestPoolBed(level, bed)) {
                    float fines = FeatureEcologyMath.broadPatch(level.getSeed() ^ 0x504f4e44434c4159L, x, z, 7);
                    BlockState target = fines > .71F ? Blocks.CLAY.defaultBlockState() : Blocks.MUD.defaultBlockState();
                    changed |= level.setBlock(bed.pos(), target, 2);
                }
                continue;
            }
            long seed = level.getSeed();
            FeatureEcologyMath.CoastalSediment material = sedimentAt(support, bed.depth(), seed, x, z);
            if (material == FeatureEcologyMath.CoastalSediment.NONE) continue;
            int sx = x + FeatureEcologyMath.contactOffset(seed ^ 0x53414e4458L, x, z);
            int sz = z + FeatureEcologyMath.contactOffset(seed ^ 0x53414e445aL, x, z);
            if (sx != x || sz != z) {
                CoastalContext.Sample nearby = samples.sedimentSample(sx, sz, seed);
                if (nearby.sedimentMarineBed()) {
                    // Use the destination depth on both samples. Dither the horizontal
                    // contact without importing a deep- or dry-bed material.
                    material = FeatureEcologyMath.mixSandContact(material,
                            sedimentAt(nearby, bed.depth(), seed, sx, sz));
                }
            }
            BlockState target = switch (material) {
                case MUD -> Blocks.MUD.defaultBlockState();
                case SILT -> silt;
                case SAND -> Blocks.SAND.defaultBlockState();
                case NONE -> throw new IllegalStateException("handled coastal sediment");
            };
            // Use the actual falling-block rule: mud supports sand despite its
            // recessed top face, while air, fluid and replaceable cells do not.
            if (target.getBlock() instanceof net.minecraft.world.level.block.FallingBlock && net.minecraft.world.level.block.FallingBlock.isFree(
                    level.getBlockState(bed.pos().below()))) continue;
            if (!bed.state().equals(target)) changed |= level.setBlock(bed.pos(), target, 2);
        }
        return changed;
    }

    private static FeatureEcologyMath.CoastalSediment sedimentAt(CoastalContext.Sample support, int depth,
                                                                  long seed, int x, int z) {
        return FeatureEcologyMath.ditheredCoastalSediment(support.weighted(), support.peak(), depth,
                support.concavity(), FeatureEcologyMath.broadPatch(seed ^ 0x434F4153544CL, x, z, 48),
                seed, x, z);
    }

    private static boolean isForestPoolBed(WorldGenLevel level, Bed bed) {
        BlockState soil = bed.state().is(Blocks.SAND) ? level.getBlockState(bed.pos().below()) : bed.state();
        if (!soil.is(Blocks.DIRT) && !soil.is(Blocks.GRASS_BLOCK)
                && !soil.is(Blocks.COARSE_DIRT) && !soil.is(Blocks.PODZOL)) return false;
        return level.getBiome(bed.pos()).unwrapKey().map(key -> key.location())
                .filter(id -> id.getNamespace().equals(TerrainDiffusionLifecycle.MOD_ID))
                .map(id -> switch (id.getPath()) {
                    case "ecology/riparian_forest", "ecology/freshwater_swamp", "ecology/mangrove_swamp" -> true;
                    default -> false;
                }).orElse(false);
    }

    private static Bed shallowBed(WorldGenLevel level, int x, int z, int seaLevel, BlockState silt) {
        // Sea level is the exclusive top boundary: generated ocean water ends at seaLevel - 1.
        if (level.getBlockState(new BlockPos(x, seaLevel, z)).is(Blocks.WATER)) return null;
        for (int y = seaLevel - 2; y >= seaLevel - 5; y--) {
            BlockPos bed = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(bed);
            if (!naturalSoftBed(state, silt)) continue;
            boolean water = true;
            for (int waterY = y + 1; waterY < seaLevel; waterY++) {
                // Waterlogged roots/logs are deliberately not water here: they cover the bed.
                if (!level.getBlockState(new BlockPos(x, waterY, z)).is(Blocks.WATER)) {
                    water = false;
                    break;
                }
            }
            if (water) return new Bed(bed, state, seaLevel - 1 - y);
        }
        return null;
    }

    private static boolean naturalSoftBed(BlockState state, BlockState silt) {
        return state.is(Blocks.MUD) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.SAND)
                || state.is(Blocks.CLAY) || state.is(silt.getBlock());
    }

    private record Bed(BlockPos pos, BlockState state, int depth) { }
}
