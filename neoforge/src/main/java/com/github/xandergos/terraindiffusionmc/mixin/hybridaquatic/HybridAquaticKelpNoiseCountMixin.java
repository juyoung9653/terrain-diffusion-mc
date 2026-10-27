package com.github.xandergos.terraindiffusionmc.mixin.hybridaquatic;

import com.github.xandergos.terraindiffusionmc.compat.hybridaquatic.HybridAquaticKelpPlacementPolicy;
import com.github.xandergos.terraindiffusionmc.compat.hybridaquatic.HybridAquaticKelpPlacementScope;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Converts only a scoped, original HA kelp chain to bounded candidate attempts. */
@Mixin(NoiseBasedCountPlacement.class)
public abstract class HybridAquaticKelpNoiseCountMixin {
    @ModifyReturnValue(method = "count", at = @At("RETURN"))
    private int terrainDiffusion$fixedKelpCandidateCount(int original) {
        return HybridAquaticKelpPlacementScope.replacesNoiseCount()
                ? HybridAquaticKelpPlacementPolicy.CANDIDATES_PER_CHUNK
                : original;
    }
}
