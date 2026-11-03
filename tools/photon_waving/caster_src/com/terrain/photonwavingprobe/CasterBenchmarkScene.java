package com.terrain.photonwavingprobe;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Deterministic vegetation/architecture stress scene in the cloned world only. */
public final class CasterBenchmarkScene {
    private CasterBenchmarkScene() {}

    public static void build(ServerLevel level) {
        int floor = 160;
        for (int x = 64; x < 104; x++) for (int z = -600; z < -540; z++) {
            put(level, x, floor, z, Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = floor + 1; y <= floor + 10; y++) put(level, x, y, z, Blocks.AIR.defaultBlockState());
            if ((x + z) % 3 != 0) put(level, x, floor + 1, z, Blocks.SHORT_GRASS.defaultBlockState());
        }
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int x = 68; x <= 100; x += 4) for (int z = -596; z <= -544; z += 4) {
            put(level, x, floor + 1, z, Blocks.OAK_LOG.defaultBlockState());
            put(level, x, floor + 2, z, Blocks.OAK_LOG.defaultBlockState());
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                put(level, x + dx, floor + 3, z + dz, leaf);
                if (Math.abs(dx) + Math.abs(dz) <= 1) put(level, x + dx, floor + 4, z + dz, leaf);
            }
            put(level, x + 2, floor + 1, z, Blocks.STONE_BRICK_STAIRS.defaultBlockState());
            put(level, x + 2, floor + 2, z, Blocks.STONE_BRICK_SLAB.defaultBlockState());
            put(level, x + 2, floor + 1, z + 1, Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            put(level, x + 2, floor + 1, z + 2, Blocks.OAK_FENCE.defaultBlockState());
        }
        // Visible isolated witnesses ahead of the forest: cube, half slab, stair, fence.
        put(level, 66, floor + 3, -564, Blocks.STONE.defaultBlockState());
        put(level, 66, floor + 3, -560, Blocks.STONE_BRICK_SLAB.defaultBlockState());
        put(level, 66, floor + 3, -556, Blocks.STONE_BRICK_STAIRS.defaultBlockState());
        put(level, 66, floor + 3, -552, Blocks.OAK_FENCE.defaultBlockState());
    }

    private static void put(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, 2);
    }
}
