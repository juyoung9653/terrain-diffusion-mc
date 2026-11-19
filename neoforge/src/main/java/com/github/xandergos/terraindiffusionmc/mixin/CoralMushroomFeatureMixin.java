package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.ecology.SubmergedPlantPlacementGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.CoralMushroomFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Retain some mushroom colonies without letting their open boxes dominate a reef. */
@Mixin(CoralMushroomFeature.class)
public final class CoralMushroomFeatureMixin {
    @Inject(method = "placeFeature", at = @At("HEAD"), cancellable = true, require = 1)
    private void terrainDiffusion$thinWholeMushroom(LevelAccessor reader, RandomSource random,
                                                     BlockPos pos, BlockState coral,
                                                     CallbackInfoReturnable<Boolean> result) {
        if (reader instanceof WorldGenLevel level
                && !SubmergedPlantPlacementGuard.allowsCoralMushroom(level, pos)) {
            result.setReturnValue(false);
        }
    }
}
