package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.snow.NaturalSnowCover;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** SRM's weather loop bypasses Biome.shouldSnow; use the same cached column decision. */
@Pseudo
@Mixin(targets = "snownee.snow.util.CommonProxy", remap = false)
public abstract class SnowRealMagicClimateMixin {
    @Inject(method = "coldEnoughToSnow", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private static void terrainDiffusion$weatherClimate(LevelReader reader, BlockPos pos, Holder<Biome> biome,
                                                         CallbackInfoReturnable<Boolean> callback) {
        Boolean eligible = NaturalSnowCover.weatherEligible(reader, pos, false);
        if (eligible != null) callback.setReturnValue(eligible);
    }
}
