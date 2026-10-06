package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.ecology.SubmergedPlantPlacementGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Streams 2.13.8 writes seagrass itself, bypassing vanilla SeagrassFeature. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.StreamVegetationFeature", remap = false)
public final class StreamsSeagrassFeatureMixin {
    private static final String PLACE_COLUMN = "a(Lnet/minecraft/world/level/WorldGenLevel;"
            + "Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos$MutableBlockPos;IIII)Z";

    @WrapOperation(method = PLACE_COLUMN, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/DoublePlantBlock;placeAt(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;I)V"),
            require = 1, remap = false)
    private void terrainDiffusion$limitTallSeagrass(LevelAccessor reader, BlockState plant,
                                                     BlockPos pos, int flags, Operation<Void> original) {
        if (!(reader instanceof WorldGenLevel level)
                || SubmergedPlantPlacementGuard.allowsPlacement(level, pos)) {
            original.call(reader, plant, pos, flags);
        }
    }

    @WrapOperation(method = PLACE_COLUMN, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
            require = 1, remap = false)
    private boolean terrainDiffusion$limitShortSeagrass(WorldGenLevel level, BlockPos pos,
                                                         BlockState plant, int flags, Operation<Boolean> original) {
        return SubmergedPlantPlacementGuard.allowsPlacement(level, pos)
                && original.call(level, pos, plant, flags);
    }
}
