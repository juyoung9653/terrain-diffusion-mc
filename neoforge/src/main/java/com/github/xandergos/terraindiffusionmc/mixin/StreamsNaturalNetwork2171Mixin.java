package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.Streams2171NetworkAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Refines only fresh V14 TD graphs after Streams 2.17.1 finishes native routing. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.core.river.NoDecompilePermissionEver_001", remap = false)
public final class StreamsNaturalNetwork2171Mixin {
    @Inject(method = "proprietary1(Ldev/streamsreflowing/core/plate/ProprietaryNiceJohn_001;"
            + "Ldev/streamsreflowing/core/terrain/TerrainSampler;"
            + "Ldev/streamsreflowing/core/terrain/SourceGivenToAuthorizedOnly_002;"
            + "Ldev/streamsreflowing/core/config/StreamsConfig;J)"
            + "Ldev/streamsreflowing/core/river/RiverNetwork;",
            at = @At("RETURN"), cancellable = true, require = 1, expect = 1, remap = false)
    private static void terrainDiffusion$refineNativeNetwork(@Coerce Object domain,
            @Coerce Object terrain, @Coerce Object wetness, @Coerce Object config, long seed,
            CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(Streams2171NetworkAdapter.refine(domain, terrain, config, seed,
                cir.getReturnValue()));
    }
}
