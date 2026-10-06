package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.greeneries.GreeneriesPlacementGuard;
import com.github.xandergos.terraindiffusionmc.compat.greeneries.ShorePlantPlacementGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SimpleBlockFeature.class)
public final class GreeneriesSimpleBlockFeatureMixin {
    @WrapOperation(
            method = "place",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;canSurvive(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"
            )
    )
    private boolean terrainDiffusion$rejectSandGrass(BlockState plant, LevelReader level, BlockPos pos,
                                                      Operation<Boolean> original,
                                                      @Local(argsOnly = true) FeaturePlaceContext<SimpleBlockConfiguration> context) {
        boolean survives = original.call(plant, level, pos);
        if (!survives || !(level instanceof WorldGenLevel worldgenLevel)) return survives;
        return GreeneriesPlacementGuard.allowsPlacement(worldgenLevel, pos, plant)
                && ShorePlantPlacementGuard.allowsPlacement(worldgenLevel, pos, plant)
                && GreeneriesPlacementGuard.acceptsNativeAttempt(worldgenLevel, pos, plant, context.random());
    }
}
