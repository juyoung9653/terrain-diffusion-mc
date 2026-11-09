package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankFillPolicy;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Read-only admission for Streams' private native bank fill.  The native
 * routine still performs its own writes; this only suppresses detached or
 * over-deep fills before that routine can start.
 */
public final class StreamsNativeBankFill {
    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private StreamsNativeBankFill() { }

    public static boolean allows(WorldGenLevel level, int x, int z, int crestY,
                                 int minY, boolean mayAddLip) {
        if (level == null) return false;
        int profile = WorldNaturalRiverState.version(level.getLevel());
        if (profile < NaturalRiverBankFillPolicy.FIRST_SAFE_PROFILE_VERSION) return true;

        int floor = Math.max(minY, level.getMinBuildHeight());
        int ceiling = level.getMaxBuildHeight();
        if (crestY < floor || crestY >= ceiling || (mayAddLip && crestY + 1 >= ceiling)) {
            return false;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, crestY, z);
        BlockState cap = level.getBlockState(cursor);
        // Audited native guard rejects solid and wet caps. Other dry non-solid
        // blocks (for example decorations) are not permission to replace them.
        if (cap.isSolid() || !cap.getFluidState().isEmpty()) return true;
        if (!clearable(cap)) return false;

        int fillDepth = 0;
        for (int offset = 0; offset < 3 && crestY - offset >= floor; offset++) {
            cursor.set(x, crestY - offset, z);
            BlockState state = level.getBlockState(cursor);
            if (clearable(state)) {
                fillDepth++;
                continue;
            }
            if (!naturalFooting(state) || crestY - offset - 1 < floor) return false;
            cursor.set(x, crestY - offset - 1, z);
            if (!naturalFooting(level.getBlockState(cursor))) return false;
            return NaturalRiverBankFillPolicy.allowsNative(profile, fillDepth, mayAddLip,
                    crestSupportNeighbors(level, x, crestY, z));
        }
        return false;
    }

    public static boolean allowsLip(WorldGenLevel level, Object engine, int x, int y, int z) {
        int profile = WorldNaturalRiverState.version(level.getLevel());
        if (profile < NaturalRiverBankFillPolicy.FIRST_SAFE_PROFILE_VERSION) return true;
        // The containment helper receives a neighboring channel's plan. Admission
        // must use the actual bank coordinate, not that source channel.
        Object plan;
        try {
            plan = engine.getClass().getMethod("plan", int.class, int.class).invoke(engine, x, z);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Streams bank plan accessor", e);
        }
        boolean channel = (Boolean) StreamsRecordAccess.read(plan, "inChannel");
        int waterY = (int) StreamsRecordAccess.number(plan, "waterSurfaceY");
        return NaturalRiverBankFillPolicy.allowsNativeLip(profile, channel, y, waterY);
    }

    private static int crestSupportNeighbors(WorldGenLevel level, int x, int y, int z) {
        int supported = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int[] direction : CARDINALS) {
            cursor.set(x + direction[0], y, z + direction[1]);
            if (!naturalFooting(level.getBlockState(cursor))) continue;
            cursor.set(x + direction[0], y - 1, z + direction[1]);
            if (naturalFooting(level.getBlockState(cursor))) supported++;
        }
        return supported;
    }

    private static boolean clearable(BlockState state) {
        return state.isAir() || (state.getBlock() instanceof BushBlock && state.canBeReplaced()
                && !state.hasBlockEntity() && state.getFluidState().isEmpty());
    }

    private static boolean naturalFooting(BlockState state) {
        return state.getFluidState().isEmpty() && !state.hasBlockEntity() && state.isSolid()
                && (state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL));
    }
}
