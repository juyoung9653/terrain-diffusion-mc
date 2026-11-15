package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverRaisedCapPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;

/**
 * Narrowly rejects Streams 2.17.3's redundant dry cap above a TD water column.
 *
 * <p>The native carver may put a soil cap one block above water even where TD's
 * immutable pre-carve terrain was already at that water level.  That is neither
 * a channel floor nor containment: retaining it produces the detached green
 * berm recorded by the bank trace.  All other native caps pass through.</p>
 */
public final class StreamsBankTop2173Policy {
    private StreamsBankTop2173Policy() {
    }

    public static boolean suppressUnnecessaryRaisedCap(WorldGenLevel level, int x, int y, int z,
                                                        BlockState proposed) {
        if (!soilCap(proposed)) return false;
        try {
            Object engine = Access.ENGINE.invoke(null, level);
            Object config = Access.CONFIG.invoke(engine);
            if (!StreamsNaturalRiverAdapter.active(config)
                    || StreamsNaturalRiverAdapter.version(config) != 14) return false;
            Object plan = Access.PLAN.invoke(engine, x, z);
            boolean inCorridor = (Boolean) Access.IN_CORRIDOR.invoke(plan);
            boolean inChannel = (Boolean) Access.IN_CHANNEL.invoke(plan);
            int water = ((Number) Access.WATER.invoke(plan)).intValue();
            int originalGround = (int) Math.floor(
                    StreamsNaturalRiverAdapter.originalTerrain(config).height(x, z)) - 1;
            // Do not erase a native cap that is retaining an existing block, fluid,
            // structure, or any other non-air terrain at the exact write position.
            return NaturalRiverRaisedCapPolicy.suppress(StreamsNaturalRiverAdapter.version(config),
                    inCorridor, inChannel, originalGround, water, y,
                    level.getBlockState(new BlockPos(x, y, z)).isAir(), soilCap(proposed));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Streams 2.17.3 bank-top adapter", failure);
        }
    }

    private static boolean soilCap(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.MUD);
    }

    private static final class Access {
        static final Method ENGINE, CONFIG, PLAN, IN_CORRIDOR, IN_CHANNEL, WATER;
        static {
            try {
                Class<?> worldgen = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen");
                Class<?> engine = Class.forName("dev.streamsreflowing.core.RiverEngine");
                Class<?> plan = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
                ENGINE = worldgen.getMethod("engine", WorldGenLevel.class);
                CONFIG = engine.getMethod("config");
                PLAN = engine.getMethod("plan", int.class, int.class);
                IN_CORRIDOR = plan.getMethod("inCorridor");
                IN_CHANNEL = plan.getMethod("inChannel");
                WATER = plan.getMethod("waterSurfaceY");
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
