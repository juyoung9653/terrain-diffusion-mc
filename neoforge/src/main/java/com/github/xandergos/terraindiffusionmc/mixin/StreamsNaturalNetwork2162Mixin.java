package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsConfluenceNetwork;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalNetwork;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalRiverAdapter;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsShallowNetwork;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsPlanformNetwork;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsShortRiverShortcutNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 2.16.2 builds a graph through its new plate-local builder before indexing it. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.core.river.NoDecompilePermissionEver_001", remap = false)
public final class StreamsNaturalNetwork2162Mixin {
    @Inject(method = "proprietary1(Ldev/streamsreflowing/core/plate/ProprietaryNiceJohn_001;"
            + "Ldev/streamsreflowing/core/terrain/TerrainSampler;"
            + "Ldev/streamsreflowing/core/terrain/SourceGivenToAuthorizedOnly_002;"
            + "Ldev/streamsreflowing/core/config/StreamsConfig;J)"
            + "Ldev/streamsreflowing/core/river/RiverNetwork;",
            at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private static void terrainDiffusion$refineNativeNetwork(@Coerce Object domain,
            @Coerce Object terrain, @Coerce Object wetness, @Coerce Object config, long seed,
            CallbackInfoReturnable<Object> cir) {
        if (!StreamsNaturalRiverAdapter.active(config)) return;
        Object network = cir.getReturnValue();
        if (StreamsNaturalRiverAdapter.version(config) >= 3) {
            network = StreamsNaturalRiverAdapter.selectWatersheds(network, domain, terrain, config);
        }
        network = StreamsNaturalNetwork.refine(network, terrain, config);
        network = StreamsConfluenceNetwork.refine(network, domain, config);
        network = StreamsShallowNetwork.refine(network, domain, config);
        int profile = StreamsNaturalRiverAdapter.version(config);
        if (profile >= 11) {
            network = StreamsPlanformNetwork.refine(network, domain,
                    StreamsNaturalRiverAdapter.originalTerrain(config), config, seed, profile);
        }
        if (profile >= 12) {
            network = StreamsShortRiverShortcutNetwork.refine(network, domain,
                    StreamsNaturalRiverAdapter.originalTerrain(config), config, profile);
        }
        cir.setReturnValue(network);
    }
}
