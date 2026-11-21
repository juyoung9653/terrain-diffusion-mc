package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsWetlandHandoff2173;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes every native consumer of the coordinate-based ColumnPlan accessors observe a wetland handoff. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.core.RiverEngine", remap = false)
public abstract class StreamsWetlandHandoffPlan2173Mixin {
    @Inject(method = "plan(II)Ldev/streamsreflowing/core/compose/ColumnPlan;",
            at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private void terrainDiffusion$handoffWetland(int x, int z, CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(StreamsWetlandHandoff2173.maskPlan(this, cir.getReturnValue(), x, z));
    }

    @Inject(method = "planWithin(Ldev/streamsreflowing/core/plate/PlateKey;II)"
            + "Ldev/streamsreflowing/core/compose/ColumnPlan;",
            at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private void terrainDiffusion$handoffWetlandWithin(@Coerce Object ignoredPlate, int x, int z,
            CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(StreamsWetlandHandoff2173.maskPlan(this, cir.getReturnValue(), x, z));
    }

    @Inject(method = "licensed1(II)Ldev/streamsreflowing/core/compose/ColumnPlan;",
            at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private void terrainDiffusion$handoffWetlandCached(int x, int z, CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(StreamsWetlandHandoff2173.maskPlan(this, cir.getReturnValue(), x, z));
    }

    @Inject(method = "sourceGivenToAuthorizedOnly1(II)Ldev/streamsreflowing/core/compose/ColumnPlan;",
            at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private void terrainDiffusion$handoffWetlandAuxiliary(int x, int z, CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(StreamsWetlandHandoff2173.maskPlan(this, cir.getReturnValue(), x, z));
    }
}
