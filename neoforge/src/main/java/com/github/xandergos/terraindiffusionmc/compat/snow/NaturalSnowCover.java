package com.github.xandergos.terraindiffusionmc.compat.snow;

import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.reflect.Method;

/** Final surface snow pass; only the current 16x16 footprint and cached ecology are read. */
public final class NaturalSnowCover {
    private static final int MAX_DEPTH = 96;
    private static final Method CONVERT = optional("snownee.snow.Hooks", "convert", LevelAccessor.class,
            BlockPos.class, BlockState.class, int.class, int.class, boolean.class);
    private static final Method RAW = optional("snownee.snow.block.SnowVariant", "srm$getRaw",
            BlockState.class, BlockGetter.class, BlockPos.class);

    private NaturalSnowCover() { }

    public static boolean isNatural(ChunkGenerator generator) {
        TerrainDiffusionBiomeSource source = TerrainDiffusionBiomeSources.find(generator.getBiomeSource());
        return source != null && source.naturalBiomes();
    }

    public static void finish(WorldGenLevel level, ChunkGenerator generator, BlockPos origin) {
        if (!isNatural(generator)) return;
        int x0 = origin.getX(), z0 = origin.getZ();
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int x = x0 + dx, z = z0 + dz;
            if (!eligible(generator, x, z)) continue;
            // One ground-climate decision caps the exposed crown and the sheltered floor.
            BlockPos exposed = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
            BlockPos ground = floor(level, x, z);
            place(level, exposed);
            if (ground != null && !ground.equals(exposed)) place(level, ground);
        }
    }

    public static boolean eligible(ChunkGenerator generator, int x, int z) {
        var surface = NaturalEcology.surfaceSample(generator, x, z);
        return surface != null && EcologyClassifier.naturalSnowCover(x, z, surface.elevation(),
                surface.climate().temperature(), surface.snowRetention());
    }

    /** Null delegates to vanilla outside TD; weather must never start model inference. */
    public static Boolean weatherEligible(LevelReader reader, BlockPos pos, boolean freezing) {
        ServerLevel level = reader instanceof WorldGenLevel worldgen ? worldgen.getLevel() : null;
        if (level == null) return null;
        var generator = level.getChunkSource().getGenerator();
        if (!isNatural(generator)) return null;
        var surface = NaturalEcology.cachedSurfaceSample(generator, pos.getX(), pos.getZ());
        if (surface == null) {
            // Preserve already generated snow/ice without repainting a label's entire transition.
            BlockState existing = reader.getBlockState(pos);
            return freezing ? existing.is(Blocks.ICE) : isSnow(existing)
                    || reader.getBlockState(pos.below()).is(Blocks.SNOW_BLOCK);
        }
        return freezing
                ? EcologyClassifier.naturalIceCover(pos.getX(), pos.getZ(), surface.elevation(),
                        surface.climate().temperature(), surface.snowRetention())
                : EcologyClassifier.naturalSnowCover(pos.getX(), pos.getZ(), surface.elevation(),
                        surface.climate().temperature(), surface.snowRetention());
    }

    /** Retain vanilla water freezing while replacing its biome-label-driven snow placement. */
    public static void freeze(WorldGenLevel level, BlockPos origin) {
        int x0 = origin.getX(), z0 = origin.getZ();
        // Generation may arrive through partial sampling or after cache eviction. Warm the same
        // reference tile used by the following floor/crown pass before cache-only weather checks.
        // Runtime weather never enters this generation-only feature pass.
        int referenceSize = TerrainDiffusionConfig.tileSize();
        int warmedX = Integer.MIN_VALUE, warmedZ = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int x = x0 + dx, z = z0 + dz;
            int referenceX = Math.floorDiv(x, referenceSize), referenceZ = Math.floorDiv(z, referenceSize);
            if (referenceX != warmedX || referenceZ != warmedZ) {
                NaturalEcology.surfaceSample(level.getLevel().getChunkSource().getGenerator(), x, z);
                warmedX = referenceX; warmedZ = referenceZ;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            below.set(x, y - 1, z);
            // Vanilla evaluates both freeze and snow against the biome at the upper position.
            if (level.getBiome(below.above()).value().shouldFreeze(level, below, false))
                level.setBlock(below, Blocks.ICE.defaultBlockState(), 2);
        }
    }

    /** Skip every leaf/snow/plant layer, not just snow above the highest canopy. */
    public static BlockPos floor(WorldGenLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, top, z);
        int minimum = Math.max(level.getMinBuildHeight(), top - MAX_DEPTH);
        for (int y = top; y >= minimum; y--) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            if (!state.getFluidState().isEmpty()) return null;
            BlockState raw = raw(level, cursor, state);
            if (raw.is(BlockTags.LOGS)) return null;
            if (state.is(Blocks.SNOW) || raw.is(BlockTags.LEAVES) || raw.isAir() || raw.canBeReplaced()) continue;
            // Unsupported shapes are not a license to search through the terrain into a cave.
            return cursor.above().immutable();
        }
        return null;
    }

    /** Shared placement guards for the final pass and optional tree-crown snow producers. */
    public static boolean canPlace(WorldGenLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && !isSnow(state)
                && level.getBrightness(LightLayer.BLOCK, pos) < 10
                && Blocks.SNOW.defaultBlockState().canSurvive(level, pos);
    }

    public static boolean place(WorldGenLevel level, BlockPos pos) {
        if (!canPlace(level, pos)) return false;
        BlockState state = level.getBlockState(pos);
        boolean placed = false;
        if (state.isAir()) placed = level.setBlock(pos, Blocks.SNOW.defaultBlockState(), 2);
        else if (CONVERT != null) {
            try { placed = (boolean) CONVERT.invoke(null, level, pos, state, 1, 2, true); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException("Snow compatibility failed", error); }
        }
        // Without the optional plant-preserving adapter, leave existing plants intact.
        if (placed) {
            BlockPos below = pos.below();
            BlockState support = level.getBlockState(below);
            if (support.hasProperty(BlockStateProperties.SNOWY))
                level.setBlock(below, support.setValue(BlockStateProperties.SNOWY, true), 2);
        }
        return placed;
    }

    public static boolean isSnow(BlockState state) {
        return state.is(Blocks.SNOW) || RAW != null && RAW.getDeclaringClass().isInstance(state.getBlock());
    }

    public static BlockState raw(WorldGenLevel level, BlockPos pos, BlockState state) {
        if (RAW == null || !RAW.getDeclaringClass().isInstance(state.getBlock())) return state;
        try { return (BlockState) RAW.invoke(state.getBlock(), state, level, pos); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Snow floor lookup failed", error); }
    }

    private static Method optional(String type, String name, Class<?>... args) {
        try { return Class.forName(type).getMethod(name, args); }
        catch (ClassNotFoundException | NoSuchMethodException ignored) { return null; }
    }
}
