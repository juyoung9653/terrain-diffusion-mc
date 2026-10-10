package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.snow.NaturalSnowCover;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Simple Snowy Fix 2.2.1 decorates tree leaves before the final snow pass. */
@Pseudo
@Mixin(targets = "dev.kostromdan.mods.simple_snowy_fix.SimpleSnowyFixMod", remap = false)
public final class SimpleSnowyFixSnowMixin {
    @WrapOperation(method = "placeSnowOnLeaves(Lnet/minecraft/world/level/LevelAccessor;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Biome;shouldSnow("
                    + "Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"),
            require = 1, remap = false)
    private static boolean terrainDiffusion$columnSnow(Biome biome, LevelReader reader, BlockPos pos,
                                                       Operation<Boolean> original) {
        if (!(reader instanceof WorldGenLevel level)) return original.call(biome, reader, pos);
        var generator = level.getLevel().getChunkSource().getGenerator();
        if (!NaturalSnowCover.isNatural(generator)) return original.call(biome, reader, pos);
        return NaturalSnowCover.eligible(generator, pos.getX(), pos.getZ())
                && NaturalSnowCover.canPlace(level, pos);
    }
}
