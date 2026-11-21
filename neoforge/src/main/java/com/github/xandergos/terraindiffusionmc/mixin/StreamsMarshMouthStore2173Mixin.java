package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsMarshMouthNetwork2173;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adapts either plate result in memory, after the native store read or write. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.core.RiverEngine", remap = false)
public abstract class StreamsMarshMouthStore2173Mixin {
    private static final String LOAD = "noDecompilePermission2("
            + "Ldev/streamsreflowing/core/plate/PlateKey;)"
            + "Ldev/streamsreflowing/core/river/RiverNetwork;";

    @Inject(method = LOAD, at = @At("RETURN"), cancellable = true,
            require = 2, expect = 2, remap = false)
    private void terrainDiffusion$fitRiverToWetland(@Coerce Object key,
            CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(StreamsMarshMouthNetwork2173.adaptLoaded(this, key, cir.getReturnValue()));
    }
}
