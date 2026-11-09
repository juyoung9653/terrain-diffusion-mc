package com.github.xandergos.terraindiffusionmc.mixin.vanillakelp;

import com.github.xandergos.terraindiffusionmc.compat.vanillakelp.VanillaKelpPlacementPolicy;
import com.github.xandergos.terraindiffusionmc.compat.vanillakelp.VanillaKelpPlacementScope;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(NoiseBasedCountPlacement.class)
public abstract class VanillaKelpNoiseCountMixin {
    @ModifyReturnValue(method = "count", at = @At("RETURN"))
    private int terrainDiffusion$fixedVanillaKelpCandidateCount(int original) {
        return VanillaKelpPlacementScope.replacesNoiseCount()
                ? VanillaKelpPlacementPolicy.CANDIDATES_PER_CHUNK
                : original;
    }
}
