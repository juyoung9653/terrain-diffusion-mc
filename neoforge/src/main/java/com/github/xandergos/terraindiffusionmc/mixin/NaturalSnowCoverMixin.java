package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.snow.NaturalSnowCover;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SnowAndFreezeFeature.class)
public abstract class NaturalSnowCoverMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$finishSnow(FeaturePlaceContext<NoneFeatureConfiguration> context,
                                            CallbackInfoReturnable<Boolean> callback) {
        if (!NaturalSnowCover.isNatural(context.chunkGenerator())) return;
        // SRM also places snow as a side effect of shouldSnow(false). Owning this natural-world
        // pass prevents either vanilla or that wrapper from restoring a hard biome boundary.
        NaturalSnowCover.freeze(context.level(), context.origin());
        NaturalSnowCover.finish(context.level(), context.chunkGenerator(), context.origin());
        callback.setReturnValue(true);
    }
}
