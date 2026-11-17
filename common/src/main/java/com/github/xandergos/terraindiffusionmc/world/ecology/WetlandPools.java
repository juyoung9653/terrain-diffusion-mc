package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.WetlandEcology;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.List;

/** Small contained water-table depressions for broad inland wet habitat. */
public final class WetlandPools {
    private static final long SALT=0x5745545F504F4F4CL;
    private WetlandPools() { }
    /** Makes at most one locally validated pool before ordinary vegetation writes. */
    public static boolean place(WorldGenLevel level, ChunkGenerator generator, BlockPos origin) {
        int chunkX = Math.floorDiv(origin.getX(), 16) * 16;
        int chunkZ = Math.floorDiv(origin.getZ(), 16) * 16;
        ChunkAccess chunk = level.getChunk(Math.floorDiv(chunkX, 16), Math.floorDiv(chunkZ, 16));
        boolean hasStart = chunk.getAllStarts().values().stream().anyMatch(start -> start.isValid());
        boolean hasReference = chunk.getAllReferences().values().stream().anyMatch(set -> !set.isEmpty());
        if (hasStart || hasReference) return false;
        for (int attempt = 0; attempt < 2; attempt++) {
            long hash = mix(level.getSeed(), chunkX, chunkZ, attempt);
            int radius = 3 + (int) ((hash >>> 7) & 1L);
            int range = 16 - 2 * (radius + 1);
            int cx = chunkX + radius + 1 + Math.floorMod((int) hash, range);
            int cz = chunkZ + radius + 1 + Math.floorMod((int) (hash >>> 32), range);
            List<WetlandPoolPlanner.Cell> plan = WetlandPoolPlanner.plan(chunkX, chunkZ, cx, cz, radius, hash,
                    new Surface(level, generator));
            if (plan.isEmpty()) continue;
            int waterY = plan.stream().mapToInt(WetlandPoolPlanner.Cell::topY).min().orElseThrow();
            for (WetlandPoolPlanner.Cell cell : plan) {
                BlockPos water = new BlockPos(cell.x(), waterY, cell.z());
                if (cell.topY() > waterY) level.setBlock(water.above(), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(water.below(), Blocks.MUD.defaultBlockState(), 2);
                level.setBlock(water, Blocks.WATER.defaultBlockState(), 2);
            }
            return true;
        }
        return false;
    }
    private static final class Surface implements WetlandPoolPlanner.Surface {
        private final WorldGenLevel level;
        private final ChunkGenerator generator;
        Surface(WorldGenLevel level, ChunkGenerator generator) { this.level = level; this.generator = generator; }
        @Override public int topY(int x, int z) { return level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1; }
        @Override public boolean eligible(int x, int z) {
            NaturalEcology.SurfaceSample surface = NaturalEcology.surfaceSample(generator, x, z);
            if (surface == null || surface.terrainBiomeId() != 0) return false;
            EcologyClassifier.Sample climate = surface.climate();
            WetlandEcology.Weights w = WetlandEcology.weights(climate);
            return w.mangrove() == 0 && w.reedMarsh() + w.freshwaterSwamp() >= .50F;
        }
        @Override public boolean naturalTop(int x, int y, int z) { return natural(level.getBlockState(new BlockPos(x, y, z))); }
        @Override public boolean naturalBed(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            return level.getFluidState(pos).isEmpty() && (natural(state) || state.is(Blocks.STONE)
                    || state.is(Blocks.DEEPSLATE) || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY));
        }
        @Override public boolean clear(int x, int y, int z) { BlockPos p = new BlockPos(x, y, z); return !level.isOutsideBuildHeight(p) && level.getBlockState(p).isAir() && level.getFluidState(p).isEmpty(); }
        @Override public boolean fluid(int x, int y, int z) { return !level.getFluidState(new BlockPos(x, y, z)).isEmpty(); }
        private static boolean natural(BlockState s) { return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.MUD); }
    }
    private static long mix(long seed,int x,int z,int attempt) { long v=seed^SALT^((long)x*0x9E3779B97F4A7C15L)^((long)z*0xC2B2AE3D27D4EB4FL)^attempt; v^=v>>>30;v*=0xBF58476D1CE4E5B9L;v^=v>>>27;v*=0x94D049BB133111EBL;return v^v>>>31; }
}
