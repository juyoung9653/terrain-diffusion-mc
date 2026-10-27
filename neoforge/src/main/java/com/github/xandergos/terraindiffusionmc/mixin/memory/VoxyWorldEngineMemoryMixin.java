package com.github.xandergos.terraindiffusionmc.mixin.memory;

import com.github.xandergos.terraindiffusionmc.compat.memory.VoxyMemoryBudget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Reduces only Voxy's secondary LRU capacity; active sections and persistence are unchanged. */
@Mixin(targets = "me.cortex.voxy.common.world.WorldEngine")
public abstract class VoxyWorldEngineMemoryMixin {
    private static final String VOXY_ENGINE_CONSTRUCTOR =
            "<init>(Lme/cortex/voxy/common/config/section/SectionStorage;Lme/cortex/voxy/commonImpl/VoxyInstance;)V";

    @ModifyConstant(method = VOXY_ENGINE_CONSTRUCTOR, constant = @Constant(intValue = 1024), require = 1, allow = 1)
    private static int terrainDiffusion$limitLowMemorySecondaryCache(int upstreamDefault) {
        return VoxyMemoryBudget.secondaryCacheSections(upstreamDefault);
    }

    @ModifyConstant(method = VOXY_ENGINE_CONSTRUCTOR, constant = @Constant(intValue = 2048), require = 1, allow = 1)
    private static int terrainDiffusion$limitHighMemorySecondaryCache(int upstreamDefault) {
        return VoxyMemoryBudget.secondaryCacheSections(upstreamDefault);
    }
}
