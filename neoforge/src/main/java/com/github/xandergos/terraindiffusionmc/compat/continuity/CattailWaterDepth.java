package com.github.xandergos.terraindiffusionmc.compat.continuity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;

/** Read-only mesh-build check. No column scans, world caches, chunk loads, or optional-mod classes. */
public final class CattailWaterDepth {
    private CattailWaterDepth() { }

    public static boolean allows(BlockAndTintGetter view, BlockPos pos, BlockState state) {
        var fluid = state.getFluidState();
        if (fluid.getType() != Fluids.WATER || !fluid.isSource()) return false;
        // A fluid cell above means the plant is submerged more deeply (or covered by another fluid).
        if (!view.getFluidState(pos.above()).isEmpty()) return false;

        double rootOffset;
        if (state.is(Blocks.SEAGRASS)) {
            BlockPos below = pos.below();
            // A bottom slab BELOW seagrass is not a half-depth root. Never subtract its height.
            if (!view.getBlockState(below).isFaceSturdy(view, below, Direction.UP)) return false;
            rootOffset = 0.0;
        } else if (state.getBlock() instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) {
            // Only relevant if a pack supplies a plant model rooted ON this same waterlogged slab.
            // Nature X's stock seagrass models do not supply that geometry.
            rootOffset = 0.5;
        } else {
            return false;
        }
        // Source water has nominal surface Y+1; its rendered 8/9 depression is not a shallower cell.
        return CattailDepthPolicy.allows(true, false, pos.getY() + rootOffset, pos.getY() + 1.0);
    }
}
