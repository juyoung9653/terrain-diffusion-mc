package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankShape;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankMargin;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Conservative post-carve shaping: legacy v5 narrow banks and v7 wide dry slopes. */
public final class StreamsNaturalBanks {
    private static final Logger LOG = LoggerFactory.getLogger("TerrainDiffusion/NaturalRivers");
    private static final AtomicLong SHAPED_COLUMNS = new AtomicLong();
    private static final AtomicLong REMOVED_BLOCKS = new AtomicLong();

    private StreamsNaturalBanks() { }

    public static void refine(WorldGenLevel level, int minX, int minZ, Object engine, int sea,
                              int[] protection, Method shielded, Method blockedBiome)
            throws ReflectiveOperationException {
        refine(level, minX, minZ, engine, sea, protection, shielded, blockedBiome, false);
    }

    public static void refine(WorldGenLevel level, int minX, int minZ, Object engine, int sea,
                              int[] protection, Method shielded, Method blockedBiome, boolean wideBanks)
            throws ReflectiveOperationException {
        StreamsBankLakeMask lakes = WorldNaturalRiverState.version(level.getLevel()) >= 9
                ? new StreamsBankLakeMask(engine, minX, minZ) : null;
        refine(level, minX, minZ, engine, sea, protection, shielded, blockedBiome, wideBanks, lakes);
    }

    static void refine(WorldGenLevel level, int minX, int minZ, Object engine, int sea,
                       int[] protection, Method shielded, Method blockedBiome, boolean wideBanks,
                       StreamsBankLakeMask lakes) throws ReflectiveOperationException {
        Method plan = engine.getClass().getMethod("plan", int.class, int.class);
        Class<?> column = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
        Method channel = column.getMethod("inChannel"), corridor = column.getMethod("inCorridor"),
                surface = column.getMethod("waterSurfaceY"), width = column.getMethod("width"),
                distance = column.getMethod("distance"), slope = column.getMethod("slope"),
                source = column.getMethod("source"), terminal = column.getMethod("terminal"),
                junction = column.getMethod("distToJunction");
        int radius = wideBanks ? 8 : 3;
        int size = 16 + radius * 2;
        Object[][] plans = new Object[size][size];
        boolean[][] channels = new boolean[size][size];
        int[][] waters = new int[size][size];
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) {
            Object p = plan.invoke(engine, minX + x - radius, minZ + z - radius);
            plans[x][z] = p;
            channels[x][z] = (Boolean) channel.invoke(p);
            waters[x][z] = ((Number) surface.invoke(p)).intValue();
        }

        for (int x = radius; x < radius + 16; x++) for (int z = radius; z < radius + 16; z++) {
            Object p = plans[x][z];
            boolean inChannel = channels[x][z];
            double halfWidth = ((Number) width.invoke(p)).doubleValue();
            double lateralDistance = ((Number) distance.invoke(p)).doubleValue();
            double edge = lateralDistance - halfWidth;
            double junctionDistance = ((Number) junction.invoke(p)).doubleValue();
            int plannedWater = waters[x][z];
            double apron = wideBanks ? NaturalRiverBankShape.wideCorridor(halfWidth) : 5.0;
            boolean eligibleWidth = wideBanks ? halfWidth >= 4.0 : halfWidth > 0.0 && halfWidth < 4.0;
            if (inChannel || !(Boolean) corridor.invoke(p) || (Boolean) source.invoke(p) || (Boolean) terminal.invoke(p)
                    || !eligibleWidth || !(edge > .5 && edge < apron)
                    || plannedWater <= sea + 2 || (Double.isFinite(junctionDistance) && junctionDistance >= 0.0 && junctionDistance < 8.0)) continue;

            int safeWater = plannedWater;
            boolean nearbyChannel = false;
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (channels[x + dx][z + dz]) {
                    nearbyChannel = true;
                    safeWater = Math.max(safeWater, waters[x + dx][z + dz]);
                }
            }
            if (!nearbyChannel) continue;

            int blockX = minX + x - radius, blockZ = minZ + z - radius;
            if (lakes != null && lakes.excludes(blockX, blockZ, radius)) continue;
            // This feature runs at CARVERS.  ProtoChunk maintains FINAL_HEIGHTMAPS
            // here, but OCEAN_FLOOR_WG remains the pre-carve map and can point at
            // air after Streams' own cut.  NO_LEAVES also avoids a canopy top.
            int originalTop = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ) - 1;
            BlockState originalSurface = level.getBlockState(new BlockPos(blockX, originalTop, blockZ));
            double plannedSlope = ((Number) slope.invoke(p)).doubleValue();
            int target = wideBanks
                    ? NaturalRiverBankShape.wideTargetHeight(originalTop, safeWater, sea, lateralDistance,
                            halfWidth, plannedSlope, blockX, blockZ)
                    : NaturalRiverBankShape.targetHeight(originalTop, safeWater, sea, lateralDistance, halfWidth, plannedSlope);
            if (target >= originalTop || originalTop - target > (wideBanks ? 2 : 3)) continue;
            if (!clearableSoil(level, blockX, blockZ, originalTop, target, wideBanks)) continue;
            if (protectedSpan(level, blockX, blockZ, target, originalTop, protection, shielded, blockedBiome)) continue;
            if (!clearCover(level, blockX, blockZ, originalTop)) continue;

            int removed = 0;
            for (int y = originalTop; y > target; y--) {
                level.setBlock(new BlockPos(blockX, y, blockZ), Blocks.AIR.defaultBlockState(), 2);
                removed++;
            }
            BlockPos top = new BlockPos(blockX, target, blockZ);
            BlockState retained = level.getBlockState(top);
            BlockState defaultTop = retained.is(Blocks.MUD) ? retained : originalSurface;
            BlockState finalMaterial = wideBanks
                    ? StreamsBankMaterialPolicy.climateBankTop(level, blockX, target, blockZ, defaultTop)
                    : StreamsBankMaterialPolicy.refinedBankTop(level, blockX, blockZ, defaultTop, edge <= 2.0);
            level.setBlock(top, finalMaterial, 2);
            SHAPED_COLUMNS.incrementAndGet();
            REMOVED_BLOCKS.addAndGet(removed);
            if (Boolean.getBoolean("td.rivers.bankTrace")) {
                LOG.info("[TD-BANK-SHAPE] x={} z={} old={} new={} water={} width={} slope={} material={}",
                        blockX, blockZ, originalTop, target, safeWater, halfWidth,
                        ((Number) slope.invoke(p)).doubleValue(), finalMaterial);
            }
        }
    }

    public static long shapedColumns() { return SHAPED_COLUMNS.get(); }
    public static long removedBlocks() { return REMOVED_BLOCKS.get(); }

    /** V9 lake-only shore finishing, using the same native wetting footprint as the carver. */
    static void refineLakeShore(WorldGenLevel level, int minX, int minZ, Object engine,
                                int[] protection, Method shielded, Method blockedBiome,
                                StreamsBankLakeMask lakes) throws ReflectiveOperationException {
        if (!lakes.hasLake()) return;
        lakes.prepareWetting(engine);
        Method plan = engine.getClass().getMethod("plan", int.class, int.class);
        Class<?> column = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
        Method channel = column.getMethod("inChannel"), surface = column.getMethod("waterSurfaceY");
        int[][] channelWater = null;
        for (int x = minX; x < minX + 16; x++) for (int z = minZ; z < minZ + 16; z++) {
            // Leave the native lake footprint, including dry islands, untouched.
            StreamsBankLakeMask.Shore shore = lakes.shore(x, z);
            if (shore == null) continue;
            int safeWater = shore.water();
            double distance = shore.distance();
            int originalTop = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockState originalSurface = level.getBlockState(new BlockPos(x, originalTop, z));
            // Terrain Slabs may decorate this chunk before Streams. Its generated
            // dry cap is cover over soil, not a reason to preserve the abrupt rim.
            if (generatedDrySlab(originalSurface)) {
                originalTop--;
                originalSurface = level.getBlockState(new BlockPos(x, originalTop, z));
            }
            if (!soil(originalSurface) || !originalSurface.getFluidState().isEmpty()) continue;
            if (NaturalRiverBankMargin.lakeShoreTarget(originalTop, safeWater, distance, x, z) >= originalTop) continue;
            if (channelWater == null) {
                channelWater = new int[22][22];
                for (int hx = 0; hx < 22; hx++) for (int hz = 0; hz < 22; hz++) {
                    Object p = plan.invoke(engine, minX + hx - 3, minZ + hz - 3);
                    channelWater[hx][hz] = (Boolean) channel.invoke(p)
                            ? ((Number) surface.invoke(p)).intValue() : Integer.MIN_VALUE;
                }
            }
            int lx = x - minX + 3, lz = z - minZ + 3;
            if (channelWater[lx][lz] != Integer.MIN_VALUE) continue;
            // Include higher nearby river water where a river joins this lake.
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++)
                safeWater = Math.max(safeWater, channelWater[lx + dx][lz + dz]);
            int target = NaturalRiverBankMargin.lakeShoreTarget(originalTop, safeWater, distance, x, z);
            if (target >= originalTop || !clearableSoil(level, x, z, originalTop, target, true)
                    || protectedSpan(level, x, z, target, originalTop, protection, shielded, blockedBiome)
                    || !clearCover(level, x, z, originalTop, true)) continue;
            for (int y = originalTop; y > target; y--)
                level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(new BlockPos(x, target, z), originalSurface, 2);
            SHAPED_COLUMNS.incrementAndGet();
            REMOVED_BLOCKS.addAndGet(originalTop - target);
            if (Boolean.getBoolean("td.rivers.bankTrace"))
                LOG.info("[TD-LAKE-SHORE] x={} z={} old={} new={} water={} distance={}",
                        x, z, originalTop, target, safeWater, distance);
        }
    }

    private static boolean clearableSoil(WorldGenLevel level, int x, int z, int top, int target,
                                         boolean wideBanks) {
        BlockState support = level.getBlockState(new BlockPos(x, target - 1, z));
        // Streams lays two soil blocks over native rock. A retained soil cap over that
        // rock still contains water; requiring a third soil block rejects the lower target.
        boolean supported = soil(support) || wideBanks && support.is(BlockTags.BASE_STONE_OVERWORLD)
                && support.getFluidState().isEmpty();
        if (!soil(level.getBlockState(new BlockPos(x, target, z)))
                || !supported) return false;
        for (int y = top; y > target; y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (!soil(state) || !state.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    private static boolean protectedSpan(WorldGenLevel level, int x, int z, int target, int top,
                                         int[] protection, Method shielded, Method blockedBiome)
            throws ReflectiveOperationException {
        int checkedTop = Math.min(level.getMaxBuildHeight() - 1, top + 3);
        for (int y = target; y <= checkedTop; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if ((Boolean) shielded.invoke(null, protection, x, z, y)
                    || (Boolean) blockedBiome.invoke(null, level.getBiome(pos))) return true;
        }
        return false;
    }

    /** Cover scan is deliberately capped at three cells and clears dry replaceable plants only. */
    private static boolean clearCover(WorldGenLevel level, int x, int z, int top) {
        return clearCover(level, x, z, top, false);
    }

    private static boolean clearCover(WorldGenLevel level, int x, int z, int top, boolean lakeShore) {
        int cover = Math.min(3, Math.max(0, level.getMaxBuildHeight() - 1 - top));
        for (int dy = 1; dy <= cover; dy++) {
            BlockPos pos = new BlockPos(x, top + dy, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (lakeShore && dy == 1 && generatedDrySlab(state)) continue;
            if (!state.getFluidState().isEmpty() || !state.canBeReplaced()) return false;
        }
        for (int dy = 1; dy <= cover; dy++) {
            BlockPos pos = new BlockPos(x, top + dy, z);
            if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
        return true;
    }

    private static boolean generatedDrySlab(BlockState state) {
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock)
                || !state.getFluidState().isEmpty()
                || state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE)
                        != net.minecraft.world.level.block.state.properties.SlabType.BOTTOM
                || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())
                        .getNamespace().equals("terrain_slabs")) return false;
        return state.getValues().entrySet().stream()
                .anyMatch(entry -> entry.getKey().getName().equals("generated") && Boolean.TRUE.equals(entry.getValue()));
    }

    private static boolean soil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.MUD);
    }
}
