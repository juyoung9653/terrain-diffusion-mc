package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.snow.NaturalSnowCover;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep vanilla placement/light/fluid guards, replacing only natural-world cold eligibility. */
@Mixin(Biome.class)
public abstract class NaturalSnowWeatherMixin {
    @WrapOperation(method = "shouldSnow", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;warmEnoughToRain(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean terrainDiffusion$snowClimate(Biome biome, BlockPos pos, Operation<Boolean> original,
                                                @Local(argsOnly = true) LevelReader reader) {
        Boolean eligible = NaturalSnowCover.weatherEligible(reader, pos, false);
        return eligible == null ? original.call(biome, pos) : !eligible;
    }

    @WrapOperation(method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/biome/Biome;warmEnoughToRain(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean terrainDiffusion$iceClimate(Biome biome, BlockPos pos, Operation<Boolean> original,
                                               @Local(argsOnly = true) LevelReader reader) {
        Boolean eligible = NaturalSnowCover.weatherEligible(reader, pos, true);
        return eligible == null ? original.call(biome, pos) : !eligible;
    }
}
