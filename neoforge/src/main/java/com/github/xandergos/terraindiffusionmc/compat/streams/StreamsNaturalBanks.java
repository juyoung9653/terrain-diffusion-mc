package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankShape;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Conservative, post-carve v5 shaping for high soil banks beside narrow streams. */
public final class StreamsNaturalBanks {
    private static final Logger LOG = LoggerFactory.getLogger("TerrainDiffusion/NaturalRivers");
    private static final AtomicLong SHAPED_COLUMNS = new AtomicLong();
    private static final AtomicLong REMOVED_BLOCKS = new AtomicLong();

    private StreamsNaturalBanks() { }

    public static void refine(WorldGenLevel level, int minX, int minZ, Object engine, int sea,
                              int[] protection, Method shielded, Method blockedBiome)
            throws ReflectiveOperationException {
        Method plan = engine.getClass().getMethod("plan", int.class, int.class);
        Class<?> column = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
        Method channel = column.getMethod("inChannel"), corridor = column.getMethod("inCorridor"),
                surface = column.getMethod("waterSurfaceY"), width = column.getMethod("width"),
                distance = column.getMethod("distance"), slope = column.getMethod("slope"),
                source = column.getMethod("source"), terminal = column.getMethod("terminal"),
                junction = column.getMethod("distToJunction");
        Object[][] plans = new Object[22][22];
        for (int x = 0; x < 22; x++) for (int z = 0; z < 22; z++)
            plans[x][z] = plan.invoke(engine, minX + x - 3, minZ + z - 3);

        for (int x = 3; x < 19; x++) for (int z = 3; z < 19; z++) {
            Object p = plans[x][z];
            boolean inChannel = (Boolean) channel.invoke(p);
            double halfWidth = ((Number) width.invoke(p)).doubleValue();
            double lateralDistance = ((Number) distance.invoke(p)).doubleValue();
            double edge = lateralDistance - halfWidth;
            double junctionDistance = ((Number) junction.invoke(p)).doubleValue();
            int plannedWater = ((Number) surface.invoke(p)).intValue();
            if (inChannel || !(Boolean) corridor.invoke(p) || (Boolean) source.invoke(p) || (Boolean) terminal.invoke(p)
                    || !(halfWidth > 0.0 && halfWidth < 4.0) || !(edge > .5 && edge < 5.0)
                    || plannedWater <= sea + 2 || (Double.isFinite(junctionDistance) && junctionDistance >= 0.0 && junctionDistance < 8.0)) continue;

            int safeWater = plannedWater;
            boolean nearbyChannel = false;
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                Object neighbor = plans[x + dx][z + dz];
                if ((Boolean) channel.invoke(neighbor)) {
                    nearbyChannel = true;
                    safeWater = Math.max(safeWater, ((Number) surface.invoke(neighbor)).intValue());
                }
            }
            if (!nearbyChannel) continue;

            int blockX = minX + x - 3, blockZ = minZ + z - 3;
            // This feature runs at CARVERS.  ProtoChunk maintains FINAL_HEIGHTMAPS
            // here, but OCEAN_FLOOR_WG remains the pre-carve map and can point at
            // air after Streams' own cut.  NO_LEAVES also avoids a canopy top.
            int originalTop = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ) - 1;
            BlockState originalSurface = level.getBlockState(new BlockPos(blockX, originalTop, blockZ));
            int target = NaturalRiverBankShape.targetHeight(originalTop, safeWater, sea, lateralDistance, halfWidth,
                    ((Number) slope.invoke(p)).doubleValue());
            if (target >= originalTop || originalTop - target > 3) continue;
            if (!clearableSoil(level, blockX, blockZ, originalTop, target)) continue;
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
            BlockState finalMaterial = StreamsBankMaterialPolicy.refinedBankTop(level, blockX, blockZ, defaultTop, edge <= 2.0);
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

    private static boolean clearableSoil(WorldGenLevel level, int x, int z, int top, int target) {
        if (!soil(level.getBlockState(new BlockPos(x, target, z)))
                || !soil(level.getBlockState(new BlockPos(x, target - 1, z)))) return false;
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
        int cover = Math.min(3, Math.max(0, level.getMaxBuildHeight() - 1 - top));
        for (int dy = 1; dy <= cover; dy++) {
            BlockPos pos = new BlockPos(x, top + dy, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (!state.getFluidState().isEmpty() || !state.canBeReplaced()) return false;
        }
        for (int dy = 1; dy <= cover; dy++) {
            BlockPos pos = new BlockPos(x, top + dy, z);
            if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
        return true;
    }

    private static boolean soil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.MUD);
    }
}
