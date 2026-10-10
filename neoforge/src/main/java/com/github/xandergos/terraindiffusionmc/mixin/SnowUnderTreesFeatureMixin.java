package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.snow.NaturalSnowCover;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The optional later pass must share the same floor/crown mask as freeze_top_layer. */
@Pseudo
@Mixin(targets = "bl4ckscor3.mod.snowundertrees.SnowUnderTreesFeature", remap = false)
public final class SnowUnderTreesFeatureMixin {
    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void terrainDiffusion$sharedSnowCover(FeaturePlaceContext<NoneFeatureConfiguration> context,
                                                CallbackInfoReturnable<Boolean> callback) {
        if (!NaturalSnowCover.isNatural(context.chunkGenerator())) return;
        // Idempotent if freeze_top_layer already ran; also handles an earlier optional pass.
        NaturalSnowCover.finish(context.level(), context.chunkGenerator(), context.origin());
        callback.setReturnValue(true);
    }
}
