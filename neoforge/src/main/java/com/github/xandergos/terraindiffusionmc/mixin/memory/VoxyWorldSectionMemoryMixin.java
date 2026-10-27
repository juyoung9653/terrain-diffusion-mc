package com.github.xandergos.terraindiffusionmc.mixin.memory;

import com.github.xandergos.terraindiffusionmc.compat.memory.VoxyMemoryBudget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Bounds the lossless in-memory long-array reuse pool after secondary-LRU eviction. */
@Mixin(targets = "me.cortex.voxy.common.world.WorldSection")
public abstract class VoxyWorldSectionMemoryMixin {
    @ModifyConstant(method = "_releaseArray()V", constant = @Constant(intValue = 400), require = 1, allow = 1)
    private static int terrainDiffusion$limitArrayReusePool(int upstreamDefault) {
        return VoxyMemoryBudget.arrayReuseSections(upstreamDefault);
    }
}
