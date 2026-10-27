package com.github.xandergos.terraindiffusionmc.mixin.memory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * BitSet grows on its first set, so allocating its full chunk-height capacity for an empty
 * aquifer override mask only retains zero words.  The index and all set/get operations stay in
 * YUNG's implementation.
 */
@Mixin(targets = "com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.aquiferoverride.AquiferOverrideMask")
public abstract class YungAquiferOverrideMaskMixin {
    @ModifyArg(
            method = "<init>(II)V",
            at = @At(value = "INVOKE", target = "Ljava/util/BitSet;<init>(I)V"),
            index = 0,
            require = 1,
            allow = 1)
    private static int terrainDiffusion$deferEmptyMaskWords(int originalBitCapacity) {
        return 0;
    }
}
