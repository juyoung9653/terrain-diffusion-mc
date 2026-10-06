package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.greeneries.GreeneriesNativePatchScope;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PlacedFeature.class)
public abstract class GreeneriesPlacedFeatureMixin {
    @Shadow @Final private Holder<ConfiguredFeature<?, ?>> feature;

    @WrapMethod(method = "placeWithContext")
    private boolean terrainDiffusion$nativePatchScope(PlacementContext context, RandomSource random,
                                                      BlockPos pos, Operation<Boolean> original) {
        if (!feature.unwrapKey().map(key -> GreeneriesNativePatchScope.isNativePatch(key.location()))
                .orElse(false)) return original.call(context, random, pos);
        return GreeneriesNativePatchScope.place(() -> original.call(context, random, pos));
    }
}
