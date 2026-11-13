package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.ecology.SubmergedPlantPlacementGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.SeaPickleFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Filters natural candidates after vanilla survival checks, before writing. */
@Mixin(SeaPickleFeature.class)
public final class SeaPickleFeatureMixin {
    @WrapOperation(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
            require = 1)
    private boolean terrainDiffusion$limitGeneratedSeaPickle(WorldGenLevel level, BlockPos pos,
                                                            BlockState plant, int flags,
                                                            Operation<Boolean> original) {
        return SubmergedPlantPlacementGuard.allowsSeaPickle(level, pos)
                && original.call(level, pos, plant, flags);
    }
}
