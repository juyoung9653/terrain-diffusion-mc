package com.github.xandergos.terraindiffusionmc.compat.ecology;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.WorldGenLevel;
import java.util.concurrent.atomic.AtomicLong;

/** Tree ground decoration must retain the already-generated substrate under water. */
public final class TreeGroundMaterialPolicy {
    private static final AtomicLong SKIPPED_WRITES = new AtomicLong();

    private TreeGroundMaterialPolicy() { }

    public static boolean preserveGround(LevelSimulatedReader reader, BlockPos pos) {
        if (!(reader instanceof WorldGenLevel level)
                || !level.getFluidState(pos.above()).is(FluidTags.WATER)
                || !level.getLevel().dimension().equals(Level.OVERWORLD)) return false;
        var source = TerrainDiffusionBiomeSources.find(
                level.getLevel().getChunkSource().getGenerator().getBiomeSource());
        return source != null && source.naturalBiomes();
    }

    public static void recordSkippedWrite() { SKIPPED_WRITES.incrementAndGet(); }
    public static long skippedWrites() { return SKIPPED_WRITES.get(); }
}
