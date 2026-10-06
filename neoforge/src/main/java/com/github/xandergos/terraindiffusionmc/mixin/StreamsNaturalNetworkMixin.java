package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalNetwork;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalRiverAdapter;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsShallowNetwork;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsConfluenceNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** One versioned boundary; native routing, lake handling and cross-region assembly stay native. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.core.river.NetworkTracer", remap = false)
public final class StreamsNaturalNetworkMixin {
    @Inject(method = "build(Ldev/streamsreflowing/core/plate/PlateDomain;Ldev/streamsreflowing/core/terrain/TerrainSampler;Ldev/streamsreflowing/core/terrain/WetnessSampler;Ldev/streamsreflowing/core/config/StreamsConfig;J)Ldev/streamsreflowing/core/river/RiverNetwork;",
            at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private static void terrainDiffusion$refineGrade(@Coerce Object domain, @Coerce Object terrain,
            @Coerce Object wetness, @Coerce Object config, long seed, CallbackInfoReturnable<Object> cir) {
        if (!StreamsNaturalRiverAdapter.active(config)) return;
        Object network = cir.getReturnValue();
        if (StreamsNaturalRiverAdapter.version(config) >= 3) {
            network = StreamsNaturalRiverAdapter.selectWatersheds(network, domain, terrain, config);
        }
        network = StreamsNaturalNetwork.refine(network, terrain, config);
        network = StreamsConfluenceNetwork.refine(network, domain, config);
        cir.setReturnValue(StreamsShallowNetwork.refine(network, domain, config));
    }
}


