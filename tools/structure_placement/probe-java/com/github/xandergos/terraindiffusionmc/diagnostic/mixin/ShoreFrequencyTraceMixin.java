package com.github.xandergos.terraindiffusionmc.diagnostic.mixin;

import com.github.xandergos.terraindiffusionmc.diagnostic.ShoreFrequencyTrace;

import com.github.xandergos.terraindiffusionmc.pipeline.ShoreClassifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=ShoreClassifier.class,remap=false)
public abstract class ShoreFrequencyTraceMixin {
    @Inject(method="select",at=@At("RETURN"))
    private static void trace(int x,int z,float elevation,float slope,float temperature,float moisture,
            float distance,float openWater,float relief,CallbackInfoReturnable<Short> result) {
        ShoreFrequencyTrace.record(x,z,elevation,slope,temperature,moisture,distance,openWater,relief,result.getReturnValue());
    }
}
