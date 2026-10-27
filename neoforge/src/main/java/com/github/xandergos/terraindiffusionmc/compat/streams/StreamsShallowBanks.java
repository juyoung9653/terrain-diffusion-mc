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
            Object engine = BankAccess.ENGINE.invoke(null, level);
            Object config = BankAccess.CONFIG.invoke(engine);
            int version = StreamsNaturalRiverAdapter.version(config);
            if (version < 6
                    || version < 9 && (Boolean) BankAccess.LAKE_NEAR_CHUNK
                    .invoke(engine, pos.getX() & ~15, pos.getZ() & ~15, 8)) return false;

            // Only dry soil can be retained. Reject other blocks before requesting
            // the centre and its 25 neighbouring plans; this hook runs per AIR write.
            BlockState existing = level.getBlockState(pos);
            if (!drySoil(existing)) return false;

            Object centre = BankAccess.PLAN.invoke(engine, pos.getX(), pos.getZ());
            double halfWidth = ((Number) BankAccess.WIDTH.invoke(centre)).doubleValue();
            double lateralDistance = ((Number) BankAccess.DISTANCE.invoke(centre)).doubleValue();
            if ((Boolean) BankAccess.CHANNEL.invoke(centre) || !(Boolean) BankAccess.CORRIDOR.invoke(centre)
                    || (Boolean) BankAccess.SOURCE.invoke(centre) || (Boolean) BankAccess.TERMINAL.invoke(centre)
                    || !Double.isFinite(halfWidth) || !Double.isFinite(lateralDistance)
                    || !(halfWidth > 0.0 && halfWidth < 4.0) || lateralDistance > halfWidth + 3.0) return false;
            if (version >= 9 && StreamsBankLakeMask.nearColumn(engine, pos.getX(), pos.getZ(), 2)) return false;

            boolean nearbyChannel = false;
            int safeWater = Integer.MIN_VALUE;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                Object neighbor = BankAccess.PLAN.invoke(engine, pos.getX() + dx, pos.getZ() + dz);
                if ((Boolean) BankAccess.CHANNEL.invoke(neighbor)) {
                    nearbyChannel = true;
                    safeWater = Math.max(safeWater, ((Number) BankAccess.WATER.invoke(neighbor)).intValue());
                }
            }
            if (!nearbyChannel) return false;

            // The intercepted operation can only remove this existing block.
            // Retaining dry soil at or below freeboard cannot place or raise
            // terrain, and avoids querying an unprimed WG heightmap at CARVERS.
            if (pos.getY() > safeWater + 1) return false;
            RETAINED_WRITES.incrementAndGet();
            return true;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Streams v6 shallow-bank adapter", failure);
        }
    }

    public static long retainedWrites() { return RETAINED_WRITES.get(); }

    /** Metadata only: never retains a world, engine, column plan or block state. */
    private static final class BankAccess {
        static final Method ENGINE, CONFIG, PLAN, LAKE_NEAR_CHUNK;
        static final Method CHANNEL, CORRIDOR, WIDTH, DISTANCE, WATER, SOURCE, TERMINAL;
        static {
            try {
                ENGINE = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                        .getMethod("engine", WorldGenLevel.class);
                Class<?> engine = Class.forName("dev.streamsreflowing.core.RiverEngine");
                CONFIG = engine.getMethod("config");
                PLAN = engine.getMethod("plan", int.class, int.class);
                LAKE_NEAR_CHUNK = engine.getMethod("lakeNearChunk", int.class, int.class, int.class);
                Class<?> column = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
                CHANNEL = column.getMethod("inChannel");
                CORRIDOR = column.getMethod("inCorridor");
                WIDTH = column.getMethod("width");
                DISTANCE = column.getMethod("distance");
                WATER = column.getMethod("waterSurfaceY");
                SOURCE = column.getMethod("source");
                TERMINAL = column.getMethod("terminal");
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }

    private static boolean drySoil(BlockState state) {
        return state.getFluidState().isEmpty() && (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.MUD));
    }
}
