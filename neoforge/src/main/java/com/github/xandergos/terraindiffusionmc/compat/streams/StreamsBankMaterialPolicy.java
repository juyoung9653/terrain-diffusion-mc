package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.ShoreClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeClassifier;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.world.ecology.FeatureEcologyMath;
import com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankShape;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankMargin;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankSurface;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import java.lang.reflect.Method;
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
        TerrainDiffusionBiomeSource source = TerrainDiffusionBiomeSources.find(
                level.getLevel().getChunkSource().getGenerator().getBiomeSource());
        if ((!sand && !coherentPointBarCandidate) || source == null || !source.naturalBiomes()) {
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

    /** Exposed-top writes only: the audited native helper and protected dry-soil refinement. */
    public static BlockState climateBankTop(WorldGenLevel level, int x, int y, int z, BlockState proposed) {
        if (proposed == null) return null;
        boolean mud = proposed.is(Blocks.MUD);
        int version = WorldNaturalRiverState.version(level.getLevel());
        boolean v9 = version >= 9;
        boolean sediment = proposed.is(Blocks.GRAVEL) || proposed.is(Blocks.SAND);
        if (!mud && !proposed.is(Blocks.GRASS_BLOCK) && !(v9 && sediment)) return proposed;
        BlockPos top = new BlockPos(x, y, z);
        if (!level.getFluidState(top).isEmpty() || !level.getFluidState(top.above()).isEmpty()) return proposed;
        BankSample bank = bankSample(level, x, z);
        float waterDistance = bank == null ? (v9 ? Float.POSITIVE_INFINITY : nearbyWaterDistance(level, top))
                : NaturalRiverBankMargin.moistureDistance(bank.distance, bank.width, x, z);
        if (v9 && sediment && (bank == null || bank.channel
                || NaturalRiverBankMargin.drySediment(bank.distance, bank.width, y, bank.water, x, z)))
            return proposed;
        // Keep the channel and inner wet apron. Only the outer exposed mud top
        // fades through soil into the surrounding climate's grass or forest floor.
        if (mud && (bank == null || bank.channel || waterDistance <= 2)) return proposed;
        var generator = level.getLevel().getChunkSource().getGenerator();
        var surface = NaturalEcology.surfaceSample(generator, x, z);
        if (surface == null) return proposed;
        var sample = surface.climate();
        if (surface.terrainBiomeId() == ShoreClassifier.STONY_SHORE) return Blocks.STONE.defaultBlockState();
        if (surface.terrainBiomeId() == GRAVEL_BEACH)
            return supportedMineralTop(level, top, Blocks.GRAVEL.defaultBlockState());
        // Snow, ocean and other retained terrain are not ordinary river soil.
        if (version < 10 && sample.biomeId() == 0
                && surface.terrainBiomeId() != ShoreClassifier.BEACH) return proposed;
        boolean dryShore = surface.terrainBiomeId() == ShoreClassifier.BEACH
                && FeatureEcologyMath.dryMineralShore(sample.temperature(), sample.moisture());
        var material = version >= 10
                ? NaturalRiverBankSurface.material(sample, surface.terrainBiomeId(), surface.elevation(),
                        waterDistance, level.getSeed(), x, z, WorldScaleManager.getCurrentScale())
                : dryShore ? FeatureEcologyMath.SurfaceMaterial.SAND
                : FeatureEcologyMath.bankSurfaceMaterial(sample.temperature(), sample.moisture(), sample.slope(),
                        sample.treeCover(), surface.elevation(), waterDistance,
                        level.getSeed(), x, z, WorldScaleManager.getCurrentScale());
        if (material == null) return proposed;
        return switch (material) {
            // A supported top is required so selecting sand cannot alter the bank geometry by falling.
            case SAND -> supportedMineralTop(level, top, Blocks.SAND.defaultBlockState());
            case COARSE_DIRT -> Blocks.COARSE_DIRT.defaultBlockState();
            case PODZOL -> Blocks.PODZOL.defaultBlockState();
            case GRAVEL -> v9 ? supportedMineralTop(level, top, Blocks.GRAVEL.defaultBlockState())
                    : Blocks.GRAVEL.defaultBlockState();
            case STONE -> Blocks.STONE.defaultBlockState();
            case GRASS -> mud ? (waterDistance < 3.25F ? Blocks.COARSE_DIRT.defaultBlockState()
                    : Blocks.GRASS_BLOCK.defaultBlockState())
                    : v9 && sediment ? Blocks.GRASS_BLOCK.defaultBlockState() : proposed;
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
     * V7 limits dry mud skinning to a width-aware apron with observed water nearby. Returning
     * {@code null} is intentional: Streams treats a null zone state as no skin,
     * which skips both its top write and its native depth-fill loop.
     */
    public static BlockState preserveV7NaturalShoreSubstrate(WorldGenLevel level,
                                                              BlockPos.MutableBlockPos surface,
                                                              int zone, boolean pointBar,
                                                              BlockState proposed) {
        if (proposed == null) return null;
        if (WorldNaturalRiverState.version(level.getLevel()) >= 9) {
            return preserveV9NaturalShoreSubstrate(level, surface, zone, pointBar, proposed);
        }
        if (proposed.is(Blocks.MUD)) {
            BankSample bank = bankSample(level, surface.getX(), surface.getZ());
            int radius = 2;
            if (bank != null && !bank.channel && level.getFluidState(surface).isEmpty()
                    && level.getFluidState(surface.above()).isEmpty()) {
                double margin = NaturalRiverBankMargin.width(bank.width, surface.getX(), surface.getZ());
                if (margin <= 0 || !Double.isFinite(bank.distance)
                        || bank.distance - bank.width > margin) return null;
                radius = Math.max(1, Math.min(6, (int) Math.ceil(margin)));
            }
            if (!waterNearSurface(level, surface, radius)) return null;
        }
        return preserveNaturalShoreSubstrate(level, surface.getX(), surface.getZ(), zone, pointBar, proposed);
    }

    private static BlockState preserveV9NaturalShoreSubstrate(WorldGenLevel level, BlockPos surface,
                                                              int zone, boolean pointBar, BlockState proposed) {
        boolean sediment = proposed.is(Blocks.MUD) || proposed.is(Blocks.GRAVEL) || proposed.is(Blocks.SAND);
        if (sediment && level.getFluidState(surface).isEmpty()
                && level.getFluidState(surface.above()).isEmpty()) {
            BankSample bank = bankSample(level, surface.getX(), surface.getZ());
            // A lake/non-river style is outside this river-width policy. Inside a river,
            // reject the entire dry skin (including native depth fill) beyond its apron.
            if (bank != null && !bank.channel && !NaturalRiverBankMargin.drySediment(
                    bank.distance, bank.width, surface.getY(), bank.water, surface.getX(), surface.getZ()))
                return null;
        }
        return preserveNaturalShoreSubstrate(level, surface.getX(), surface.getZ(), zone, pointBar, proposed);
    }

    private static boolean waterNearSurface(WorldGenLevel level, BlockPos surface, int radius) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        // Near-first rings keep the common waterline case at the old small probe cost.
        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    if (dx * dx + dz * dz > radius * radius) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        probe.set(surface.getX() + dx, surface.getY() + dy, surface.getZ() + dz);
                        if (level.hasChunkAt(probe) && level.getFluidState(probe).is(FluidTags.WATER)) return true;
                    }
                }
            }
        }
        return false;
    }

    private record BankSample(boolean channel, double width, double distance, int water) { }

    /** Cached API lookup; one existing engine plan per material candidate, no neighbour plans. */
    private static BankSample bankSample(WorldGenLevel level, int x, int z) {
        try {
            Object engine = BankAccess.ENGINE.invoke(null, level);
            Object plan = BankAccess.PLAN.invoke(engine, x, z);
            if (!(Boolean) BankAccess.CORRIDOR.invoke(plan)) return null;
            return new BankSample((Boolean) BankAccess.CHANNEL.invoke(plan),
                    ((Number) BankAccess.WIDTH.invoke(plan)).doubleValue(),
                    ((Number) BankAccess.DISTANCE.invoke(plan)).doubleValue(),
                    ((Number) BankAccess.WATER.invoke(plan)).intValue());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Streams bank material adapter", failure);
        }
    }

    private static final class BankAccess {
        static final Method ENGINE, PLAN, CORRIDOR, CHANNEL, WIDTH, DISTANCE, WATER;
        static {
            try {
                ENGINE = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                        .getMethod("engine", WorldGenLevel.class);
                PLAN = Class.forName("dev.streamsreflowing.core.RiverEngine")
                        .getMethod("plan", int.class, int.class);
                Class<?> column = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
                CORRIDOR = column.getMethod("inCorridor");
                CHANNEL = column.getMethod("inChannel");
                WIDTH = StreamsRecordAccess.accessor(column, "width");
                DISTANCE = StreamsRecordAccess.accessor(column, "distance");
                WATER = column.getMethod("waterSurfaceY");
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
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
        TerrainDiffusionBiomeSource source = TerrainDiffusionBiomeSources.find(
                level.getLevel().getChunkSource().getGenerator().getBiomeSource());
        if (source == null || !source.naturalBiomes()) return 0;
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
