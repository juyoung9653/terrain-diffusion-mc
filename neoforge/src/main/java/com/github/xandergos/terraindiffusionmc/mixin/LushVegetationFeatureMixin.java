package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.lush.LushVegetationPlacementScope;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.rhysr6s.lush.worldgen.feature.VegetationFeature", remap = false)
public abstract class LushVegetationFeatureMixin {
    @WrapMethod(method = "place")
    private boolean terrainDiffusion$scopeSoilPolicy(
            FeaturePlaceContext<NoneFeatureConfiguration> context, Operation<Boolean> original) {
        return LushVegetationPlacementScope.place(context.level(), context.origin(), context.random(),
                () -> original.call(context));
    }

    @Inject(method = "canGrowOn", at = @At("RETURN"), cancellable = true, require = 1)
    private static void terrainDiffusion$recognizeEcologySoil(BlockState support, boolean mushroom,
                                                               CallbackInfoReturnable<Boolean> callback) {
        if (LushVegetationPlacementScope.canGrowOn(support, mushroom)) callback.setReturnValue(true);
    }

    @Inject(method = "placeShort", at = @At("HEAD"), cancellable = true, require = 1)
    private void terrainDiffusion$filterShortPlant(WorldGenLevel level, BlockPos pos, BlockState plant,
                                                    CallbackInfoReturnable<Boolean> callback) {
        if (!LushVegetationPlacementScope.allowsPlant(level, pos)) callback.setReturnValue(false);
    }

    @Inject(method = "placeTall", at = @At("HEAD"), cancellable = true, require = 1)
    private void terrainDiffusion$filterTallPlant(WorldGenLevel level, BlockPos pos, Block plant,
                                                   CallbackInfoReturnable<Boolean> callback) {
        // One decision guards the lower and upper writes performed by Lush.
        if (!LushVegetationPlacementScope.allowsPlant(level, pos)) callback.setReturnValue(false);
    }
}
