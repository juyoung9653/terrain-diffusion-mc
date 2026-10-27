package com.github.xandergos.terraindiffusionmc.mixin.memory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Avoids two tiny common-pool submissions for the accessory and cosmetic cape lookups. */
@Mixin(targets = "com.aetherteam.aether.mixin.AetherMixinHooks", remap = false)
public abstract class AetherCapeStreamMixin {
    @ModifyArg(
            method = "isCapeVisible(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE", target = "Ljava/util/stream/StreamSupport;stream(Ljava/util/Spliterator;Z)Ljava/util/stream/Stream;"),
            index = 1,
            require = 2,
            allow = 2)
    private static boolean terrainDiffusion$makeCapeLookupsSequential(boolean originalParallel) {
        return false;
    }
}
