package com.terrain.chunkprofile.mixin;

import com.terrain.chunkprofile.StreamsBuildTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only ledger for actual native Streams network construction. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.core.river.NetworkTracer", remap = false)
public final class StreamsBuildTraceMixin {
    private static final String BUILD = "build(Ldev/streamsreflowing/core/plate/PlateDomain;"
            + "Ldev/streamsreflowing/core/terrain/TerrainSampler;"
            + "Ldev/streamsreflowing/core/terrain/WetnessSampler;"
            + "Ldev/streamsreflowing/core/config/StreamsConfig;J)"
            + "Ldev/streamsreflowing/core/river/RiverNetwork;";

    @Inject(method = BUILD, at = @At("HEAD"), require = 1, remap = false)
    private static void tdChunkProfile$buildStart(@Coerce Object domain, @Coerce Object terrain,
                                                   @Coerce Object wetness, @Coerce Object config, long seed,
                                                   CallbackInfoReturnable<Object> cir) {
        StreamsBuildTrace.begin(domain);
    }

    // Run before TD's default-order cancellable RETURN refinement hook so the summary is native.
    @Inject(method = BUILD, at = @At("RETURN"), require = 1, order = 900, remap = false)
    private static void tdChunkProfile$buildReturn(@Coerce Object domain, @Coerce Object terrain,
                                                    @Coerce Object wetness, @Coerce Object config, long seed,
                                                    CallbackInfoReturnable<Object> cir) {
        StreamsBuildTrace.finish(cir.getReturnValue());
    }
}
