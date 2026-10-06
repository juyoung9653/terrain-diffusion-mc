package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.ShoreClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeClassifier;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.world.ecology.FeatureEcologyMath;
import com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankShape;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
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

    /** Only called for the audited exposed-top write, never zone selection or depth fills. */
    public static BlockState climateBankTop(WorldGenLevel level, int x, int y, int z, BlockState proposed) {
        if (proposed == null || !proposed.is(Blocks.GRASS_BLOCK)) return proposed;
        BlockPos top = new BlockPos(x, y, z);
        if (!level.getFluidState(top).isEmpty() || !level.getFluidState(top.above()).isEmpty()) return proposed;
        var generator = level.getLevel().getChunkSource().getGenerator();
        var surface = NaturalEcology.surfaceSample(generator, x, z);
        if (surface == null) return proposed;
        var sample = surface.climate();
        if (surface.terrainBiomeId() == ShoreClassifier.STONY_SHORE) return Blocks.STONE.defaultBlockState();
        if (surface.terrainBiomeId() == GRAVEL_BEACH)
            return supportedMineralTop(level, top, Blocks.GRAVEL.defaultBlockState());
        // Snow, ocean and other retained terrain are not ordinary river soil.
        if (sample.biomeId() == 0 && surface.terrainBiomeId() != ShoreClassifier.BEACH) return proposed;
        // Humid/cold bank grasses need no dry-soil correction or additional block probes.
        if (sample.temperature() <= 10 || sample.moisture() >= .60F) return proposed;
        boolean dryShore = surface.terrainBiomeId() == ShoreClassifier.BEACH
                && FeatureEcologyMath.dryMineralShore(sample.temperature(), sample.moisture());
        var material = dryShore ? FeatureEcologyMath.SurfaceMaterial.SAND
                : FeatureEcologyMath.bankSurfaceMaterial(sample.temperature(), sample.moisture(), sample.slope(),
                        sample.treeCover(), surface.elevation(), nearbyWaterDistance(level, top),
                        level.getSeed(), x, z, WorldScaleManager.getCurrentScale());
        return switch (material) {
            // A supported top is required so selecting sand cannot alter the bank geometry by falling.
            case SAND -> supportedMineralTop(level, top, Blocks.SAND.defaultBlockState());
            case COARSE_DIRT -> Blocks.COARSE_DIRT.defaultBlockState();
            case PODZOL -> Blocks.PODZOL.defaultBlockState();
            case GRASS -> proposed;
        };
    }

    private static BlockState supportedMineralTop(WorldGenLevel level, BlockPos top, BlockState mineral) {
        BlockPos below = top.below();
        BlockState support = level.getBlockState(below);
        // Streams' top helper creates stone under falling blocks if the support contains fluid.
        // Avoid that branch, including waterlogged full blocks, so this hook only changes the top.
        return support.getFluidState().isEmpty()
                && support.isFaceSturdy(level, below, net.minecraft.core.Direction.UP)
                ? mineral : Blocks.COARSE_DIRT.defaultBlockState();
    }

    /** Bounded observations within four blocks; no heightmap or neighbouring AI tile queries. */
    private static float nearbyWaterDistance(WorldGenLevel level, BlockPos top) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int radius = 1; radius <= 4; radius++) {
            for (int axis = 0; axis < 4; axis++) {
                int dx = axis == 0 ? radius : axis == 1 ? -radius : 0;
                int dz = axis == 2 ? radius : axis == 3 ? -radius : 0;
                for (int dy = -2; dy <= 1; dy++) {
                    probe.set(top.getX() + dx, top.getY() + dy, top.getZ() + dz);
                    if (level.hasChunkAt(probe) && level.getFluidState(probe).is(FluidTags.WATER)) return radius;
                }
            }
        }
        return Float.POSITIVE_INFINITY;
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

    /**
     * V7 limits mud skinning to surfaces with observed water nearby.  Returning
     * {@code null} is intentional: Streams treats a null zone state as no skin,
     * which skips both its top write and its native depth-fill loop.
     */
    public static BlockState preserveV7NaturalShoreSubstrate(WorldGenLevel level,
                                                              BlockPos.MutableBlockPos surface,
                                                              int zone, boolean pointBar,
                                                              BlockState proposed) {
        if (proposed == null) return null;
        if (proposed.is(Blocks.MUD) && !waterNearSurface(level, surface)) return null;
        return preserveNaturalShoreSubstrate(level, surface.getX(), surface.getZ(), zone, pointBar, proposed);
    }

    private static boolean waterNearSurface(WorldGenLevel level, BlockPos surface) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz > 4) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    if (level.getBlockState(surface.offset(dx, dy, dz)).getFluidState().is(FluidTags.WATER)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Selects only the exposed top of a TD v5 narrow-bank shelf.  Unlike the
     * point-bar interception above, this runs after Streams has finished its
     * geometry pass, so it deliberately never changes a depth fill.
     */
    public static BlockState refinedBankTop(WorldGenLevel level, int blockX, int blockZ,
                                            BlockState retainedSoil, boolean nearEdge) {
        if (retainedSoil.is(Blocks.MUD)) return retainedSoil;
        short terrainBiomeId = terrainBiome(level, blockX, blockZ);
        if (terrainBiomeId == ShoreClassifier.STONY_SHORE) return Blocks.STONE.defaultBlockState();
        if (terrainBiomeId == GRAVEL_BEACH) return Blocks.GRAVEL.defaultBlockState();
        if (!nearEdge) return retainedSoil;
        double exposure = NaturalRiverBankShape.exposure(blockX, blockZ);
        if (exposure >= .72) return Blocks.GRAVEL.defaultBlockState();
        if (exposure >= .54) return Blocks.COARSE_DIRT.defaultBlockState();
        return retainedSoil;
    }

    private static FastNoiseLite pointBarSediment() {
        FastNoiseLite noise = new FastNoiseLite(419731);
        noise.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        noise.SetFrequency(1f / 96f);
        return noise;
    }

    private static short terrainBiome(WorldGenLevel level, int blockX, int blockZ) {
        if (!(level.getLevel().getChunkSource().getGenerator().getBiomeSource()
                instanceof TerrainDiffusionBiomeSource source) || !source.naturalBiomes()) return 0;
        int size = TerrainDiffusionConfig.tileSize();
        int tileX = Math.floorDiv(blockX, size) * size;
        int tileZ = Math.floorDiv(blockZ, size) * size;
        LocalTerrainProvider.HeightmapData tile = LocalTerrainProvider.getInstance()
                .fetchHeightmap(tileZ, tileX, tileZ + size, tileX + size);
        return tile == null || tile.ecology == null ? 0
                : tile.ecology.terrainBiomeId(blockX - tileX, blockZ - tileZ);
    }

    private static short terrainId(String id) {
        int index = TerralithBiomeClassifier.biomeNames().indexOf(id);
        if (index < 0) throw new IllegalStateException("Missing terrain biome " + id);
        return (short) (index + 1);
    }
}
