package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.ecology.SubmergedPlantPlacementGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.CoralFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Coral writes are checked at every target, including a boundary crossing. */
@Mixin(CoralFeature.class)
public final class CoralSeaPickleMixin {
    /** Reject an entire shallow colony before its builder can create a crown. */
    @Inject(method = "place", at = @At("HEAD"), cancellable = true, require = 1)
    private void terrainDiffusion$rejectShallowCoral(
            FeaturePlaceContext<?> context,
            CallbackInfoReturnable<Boolean> result) {
        WorldGenLevel level = context.level();
        if (!SubmergedPlantPlacementGuard.allowsCoralAnchor(level, context.origin())) {
            result.setReturnValue(false);
        }
    }

    /**
     * A failed inner setBlock is ignored by vanilla's feature builders. Stop a
     * rejected primary target before the builder can report a phantom anchor or
     * attempt its upper and side decoration.
     */
    @Inject(method = "placeCoralBlock", at = @At("HEAD"), cancellable = true, require = 1)
    private void terrainDiffusion$rejectCoralAnchor(LevelAccessor reader, RandomSource random,
                                                    BlockPos pos, BlockState coral,
                                                    CallbackInfoReturnable<Boolean> result) {
        if (reader instanceof WorldGenLevel level
                && !SubmergedPlantPlacementGuard.allowsCoral(level, pos)) {
            result.setReturnValue(false);
        }
    }

    @WrapOperation(method = "placeCoralBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/LevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
            require = 1)
    private boolean terrainDiffusion$limitReefSeaPickle(LevelAccessor reader, BlockPos pos,
                                                       BlockState plant, int flags,
                                                       Operation<Boolean> original) {
        return (!(reader instanceof WorldGenLevel level)
                || (SubmergedPlantPlacementGuard.allowsCoral(level, pos)
                && (!plant.is(Blocks.SEA_PICKLE)
                || SubmergedPlantPlacementGuard.allowsSeaPickle(level, pos))))
                && original.call(reader, pos, plant, flags);
    }
}
