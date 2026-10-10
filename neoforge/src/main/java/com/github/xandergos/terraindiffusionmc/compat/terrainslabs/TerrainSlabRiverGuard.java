package com.github.xandergos.terraindiffusionmc.compat.terrainslabs;

import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.WorldGenLevel;

/** Protects river water and its containing banks without reading neighbouring chunks. */
public final class TerrainSlabRiverGuard {
    private TerrainSlabRiverGuard() { }

    public static boolean protects(WorldGenLevel level, BlockPos pos) {
        int version = WorldNaturalRiverState.version(level.getLevel());
        if (version < 9) return false;
        if (level.getFluidState(pos).is(FluidTags.WATER)) return true;
        try {
            Object engine = Access.ENGINE.invoke(null, level.getLevel());
            if (engine == null) return false;
            Object plan = Access.PLAN.invoke(engine, pos.getX(), pos.getZ());
            return TerrainSlabRiverPolicy.protect(version, false,
                    (Boolean) Access.CORRIDOR.invoke(plan), (Boolean) Access.CHANNEL.invoke(plan),
                    ((Number) Access.DISTANCE.invoke(plan)).doubleValue(),
                    ((Number) Access.WIDTH.invoke(plan)).doubleValue(), pos.getY(),
                    ((Number) Access.BED.invoke(plan)).intValue(), ((Number) Access.WATER.invoke(plan)).intValue(),
                    pos.getX(), pos.getZ());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Terrain Slabs river protection adapter", failure);
        }
    }

    private static final class Access {
        static final Method ENGINE, PLAN, CORRIDOR, CHANNEL, DISTANCE, WIDTH, BED, WATER;
        static {
            try {
                ENGINE = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                        .getMethod("engineIfPresent", ServerLevel.class);
                PLAN = Class.forName("dev.streamsreflowing.core.RiverEngine").getMethod("plan", int.class, int.class);
                Class<?> column = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
                CORRIDOR = column.getMethod("inCorridor");
                CHANNEL = column.getMethod("inChannel");
                DISTANCE = column.getMethod("distance");
                WIDTH = column.getMethod("width");
                BED = column.getMethod("bedY");
                WATER = column.getMethod("waterSurfaceY");
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
