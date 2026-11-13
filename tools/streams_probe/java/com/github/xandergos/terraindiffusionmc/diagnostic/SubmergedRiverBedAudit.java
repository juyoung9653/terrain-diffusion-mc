package com.github.xandergos.terraindiffusionmc.diagnostic;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Reads completed, forced chunks after the native water-settlement lifecycle.
 * A water cell contributes only when its block immediately below is not water,
 * so tall water columns and aquatic plants do not inflate the physical-bed count.
 */
final class SubmergedRiverBedAudit {
    private SubmergedRiverBedAudit() { }

    static String run(ServerLevel level, List<ChunkPos> chunks, long settledTicks) {
        if (settledTicks < 100) throw new IllegalArgumentException("requires 100 settled ticks");
        long waterBeds = 0;
        long surfaceWaterBeds = 0;
        long grassBeds = 0;
        long dryGrass = 0;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        for (ChunkPos chunk : chunks) {
            int minX = chunk.getMinBlockX(), minZ = chunk.getMinBlockZ();
            for (int x = minX; x < minX + 16; x++) for (int z = minZ; z < minZ + 16; z++) {
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    if (level.getBlockState(pos).is(Blocks.GRASS_BLOCK)
                            && level.getFluidState(pos.above()).isEmpty()) dryGrass++;
                    if (!level.getFluidState(pos).is(FluidTags.WATER) || y == minY) continue;
                    below.set(x, y - 1, z);
                    if (level.getFluidState(below).is(FluidTags.WATER)) continue;
                    waterBeds++;
                    if (y > level.getSeaLevel()) surfaceWaterBeds++;
                    if (level.getBlockState(below).is(Blocks.GRASS_BLOCK)) grassBeds++;
                }
            }
        }
        if (waterBeds == 0 || surfaceWaterBeds == 0)
            throw new IllegalStateException("no above-sea physical water beds in selected chunks");
        if (grassBeds != 0) throw new IllegalStateException("submerged grass beds=" + grassBeds);
        long treeGroundWritesSkipped = com.github.xandergos.terraindiffusionmc.compat.ecology
                .TreeGroundMaterialPolicy.skippedWrites();
        return "{\"event\":\"submerged_river_bed_audit\",\"status\":\"passed\","
                + "\"method\":\"actual_loaded_water_bottom_after_settlement\","
                + "\"settledTicks\":" + settledTicks + ",\"fullChunks\":" + chunks.size()
                + ",\"waterBeds\":" + waterBeds + ",\"surfaceWaterBeds\":" + surfaceWaterBeds
                + ",\"submergedGrassBeds\":0,"
                + "\"dryExposedGrassBlocks\":" + dryGrass + ",\"treeGroundWritesSkipped\":"
                + treeGroundWritesSkipped + "}";
    }
}
