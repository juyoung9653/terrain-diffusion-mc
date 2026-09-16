package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRiverRaster;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalRiverAdapter;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="dev.streamsreflowing.core.RiverEngine",remap=false)
public final class StreamsRiverRasterMixin {
    // Only the planner's lake-aware inset calculation changes. The global helper
    // is also used while building native networks and deliberately stays native.
    @WrapOperation(method={"a(DDDDDD)I","a(DDDDDD[Ldev/streamsreflowing/core/plate/PlateKey;)I"},
            at=@At(value="INVOKE",target="Ldev/streamsreflowing/core/RiverEngine;insetFor(DLdev/streamsreflowing/core/config/StreamsConfig;)I"),
            require=1,remap=false)
    private int terrainDiffusion$stableInset(double width,@Coerce Object config,Operation<Integer> original) {
        return original.call(StreamsNaturalRiverAdapter.version(config)>=4 ? 3.0 : width,config);
    }
    @Inject(method={"a(DDLdev/streamsreflowing/core/river/RiverSample;Z)Ldev/streamsreflowing/core/compose/ColumnPlan;",
            "a(DDLdev/streamsreflowing/core/river/RiverSample;Z[Ldev/streamsreflowing/core/plate/PlateKey;)Ldev/streamsreflowing/core/compose/ColumnPlan;"},
            at=@At("RETURN"),cancellable=true,require=1,remap=false)
    private void terrainDiffusion$continuousNarrowChannel(CallbackInfoReturnable<Object> result,
            @Local(argsOnly=true,ordinal=0) double x,@Local(argsOnly=true,ordinal=1) double z) {
        Object original = result.getReturnValue();
        Object resolved = StreamsRiverRaster.resolve(this,original,(int)Math.floor(x),(int)Math.floor(z));
        if (resolved != original) result.setReturnValue(resolved);
    }
}
