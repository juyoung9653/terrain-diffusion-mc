package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** V6-only retention of original dry soil at the native dry-bank cut site. */
public final class StreamsShallowBanks {
    private static final AtomicLong RETAINED_WRITES = new AtomicLong();

    private StreamsShallowBanks() { }

    /**
     * Returns true only when the audited native dry-bank AIR write must be
     * suppressed.  It never writes a block, fluid, or heightmap itself.
     */
    public static boolean preserve(WorldGenLevel level, BlockPos pos, BlockState replacement) {
        if (!replacement.isAir() || WorldNaturalRiverState.version(level.getLevel()) < 6) return false;
        try {
            Object engine = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                    .getMethod("engine", WorldGenLevel.class).invoke(null, level);
            Object config = engine.getClass().getMethod("config").invoke(engine);
            if (StreamsNaturalRiverAdapter.version(config) < 6
                    || (Boolean) engine.getClass().getMethod("lakeNearChunk", int.class, int.class, int.class)
                    .invoke(engine, pos.getX() & ~15, pos.getZ() & ~15, 8)) return false;

            Method plan = engine.getClass().getMethod("plan", int.class, int.class);
            Object centre = plan.invoke(engine, pos.getX(), pos.getZ());
            Class<?> column = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
            Method channel = column.getMethod("inChannel"), corridor = column.getMethod("inCorridor"),
                    width = column.getMethod("width"), distance = column.getMethod("distance"),
                    surface = column.getMethod("waterSurfaceY"), source = column.getMethod("source"),
                    terminal = column.getMethod("terminal");
            double halfWidth = ((Number) width.invoke(centre)).doubleValue();
            double lateralDistance = ((Number) distance.invoke(centre)).doubleValue();
            if ((Boolean) channel.invoke(centre) || !(Boolean) corridor.invoke(centre)
                    || (Boolean) source.invoke(centre) || (Boolean) terminal.invoke(centre)
                    || !Double.isFinite(halfWidth) || !Double.isFinite(lateralDistance)
                    || !(halfWidth > 0.0 && halfWidth < 4.0) || lateralDistance > halfWidth + 3.0) return false;

            boolean nearbyChannel = false;
            int safeWater = Integer.MIN_VALUE;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                Object neighbor = plan.invoke(engine, pos.getX() + dx, pos.getZ() + dz);
                if ((Boolean) channel.invoke(neighbor)) {
                    nearbyChannel = true;
                    safeWater = Math.max(safeWater, ((Number) surface.invoke(neighbor)).intValue());
                }
            }
            if (!nearbyChannel) return false;

            BlockState existing = level.getBlockState(pos);
            // The intercepted operation can only remove this existing block.
            // Retaining dry soil at or below freeboard cannot place or raise
            // terrain, and avoids querying an unprimed WG heightmap at CARVERS.
            if (pos.getY() > safeWater + 1 || !drySoil(existing)) return false;
            RETAINED_WRITES.incrementAndGet();
            return true;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Streams v6 shallow-bank adapter", failure);
        }
    }

    public static long retainedWrites() { return RETAINED_WRITES.get(); }

    private static boolean drySoil(BlockState state) {
        return state.getFluidState().isEmpty() && (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.MUD));
    }
}
