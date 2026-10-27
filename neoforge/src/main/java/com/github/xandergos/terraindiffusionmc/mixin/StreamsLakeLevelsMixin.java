package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsLakeLevels;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** The six-argument assembly boundary is shared by native builds and FileNetworkStore.load. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.core.river.NetworkTracer", remap = false)
public final class StreamsLakeLevelsMixin {
    @ModifyVariable(method = "assemble(Ldev/streamsreflowing/core/plate/PlateKey;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ldev/streamsreflowing/core/Relief;Ldev/streamsreflowing/core/config/StreamsConfig;)Ldev/streamsreflowing/core/river/RiverNetwork;",
            at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 1, remap = false)
    private static List<?> terrainDiffusion$oneWaterline(List<?> lakes, @Coerce Object key,
            List<?> segments, List<?> originalLakes, List<?> sinks, @Coerce Object relief, @Coerce Object config) {
        return StreamsLakeLevels.resolve(lakes, config);
    }
}
