package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.ecology.SubmergedPlantPlacementGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.SeagrassFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Called after vanilla chooses the candidate and short/tall form, before either block is written. */
@Mixin(SeagrassFeature.class)
public final class SeagrassFeatureMixin {
    @WrapOperation(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;canSurvive(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"),
            require = 1)
    private boolean terrainDiffusion$limitGeneratedSeagrass(BlockState plant, LevelReader reader,
                                                              BlockPos pos, Operation<Boolean> original) {
        boolean survives = original.call(plant, reader, pos);
        return survives && (!(reader instanceof WorldGenLevel level)
                || SubmergedPlantPlacementGuard.allowsPlacement(level, pos));
    }
}
