package com.github.xandergos.terraindiffusionmc.world.structure;

/** Optional worldgen control signals must reach their owning scheduler, never the cache. */
public final class StructurePreflightControl {
    private StructurePreflightControl() { }

    public static boolean isDeferred(RuntimeException failure) {
        // Audited Streams Reflowing 2.13.8; match the exact type without a hard dependency.
        String type = failure.getClass().getName();
        return type.equals("dev.streamsreflowing.worldgen.StreamsWorldgen$StructurePlatesPending")
                || type.equals("dev.streamsreflowing.worldgen.StreamsWorldgen$SeeAiNoticeConstant_002");
    }
}
