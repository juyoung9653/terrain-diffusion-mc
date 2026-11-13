package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.ecology.SubmergedPlantPlacementGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.CoralFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Coral features write their own pickles; coral blocks and fans pass through. */
@Mixin(CoralFeature.class)
public final class CoralSeaPickleMixin {
    @WrapOperation(method = "placeCoralBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/LevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
            require = 1)
    private boolean terrainDiffusion$limitReefSeaPickle(LevelAccessor reader, BlockPos pos,
                                                       BlockState plant, int flags,
                                                       Operation<Boolean> original) {
        return (!plant.is(Blocks.SEA_PICKLE) || !(reader instanceof WorldGenLevel level)
                || SubmergedPlantPlacementGuard.allowsSeaPickle(level, pos))
                && original.call(reader, pos, plant, flags);
    }
}
