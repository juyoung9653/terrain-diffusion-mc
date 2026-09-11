package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;

/**
 * Identifies worlds for which Streams' eager cache warming should be deferred until demand.
 */
public final class StreamsTerrainPolicy {
    private static final AtomicLong SKIPPED_ORIGIN_WARMUPS = new AtomicLong();
    private static final AtomicLong SKIPPED_PLAYER_PREFETCHES = new AtomicLong();
    private static final AtomicLong SKIPPED_UNLOADED_FLOW_GRIDS = new AtomicLong();

    private StreamsTerrainPolicy() {
    }

    public static boolean useDemandDrivenGeneration(WorldGenLevel level) {
        return level != null && useDemandDrivenGeneration(level.getLevel());
    }

    public static boolean useDemandDrivenGeneration(ServerLevel level) {
        return level != null
                && TerrainDiffusionConfig.streamsLazyPrefetch()
                && level.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource;
    }

    public static void recordSkippedOriginWarmup() {
        SKIPPED_ORIGIN_WARMUPS.incrementAndGet();
    }

    public static void recordSkippedPlayerPrefetch() {
        SKIPPED_PLAYER_PREFETCHES.incrementAndGet();
    }

    public static void recordSkippedUnloadedFlowGrid() {
        SKIPPED_UNLOADED_FLOW_GRIDS.incrementAndGet();
    }

    public static long skippedOriginWarmups() {
        return SKIPPED_ORIGIN_WARMUPS.get();
    }

    public static long skippedPlayerPrefetches() {
        return SKIPPED_PLAYER_PREFETCHES.get();
    }

    public static long skippedUnloadedFlowGrids() {
        return SKIPPED_UNLOADED_FLOW_GRIDS.get();
    }
}
