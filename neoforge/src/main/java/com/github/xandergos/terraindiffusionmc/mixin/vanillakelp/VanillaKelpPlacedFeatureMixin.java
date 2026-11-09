package com.github.xandergos.terraindiffusionmc.mixin.vanillakelp;

import com.github.xandergos.terraindiffusionmc.compat.vanillakelp.VanillaKelpPlacementScope;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Retains vanilla kelp's configured feature and placement filters. */
@Mixin(PlacedFeature.class)
public abstract class VanillaKelpPlacedFeatureMixin {
    @WrapMethod(method = "placeWithContext")
    private boolean terrainDiffusion$vanillaKelpScope(
            PlacementContext context, RandomSource random, BlockPos origin, Operation<Boolean> original) {
        PlacedFeature feature = (PlacedFeature) (Object) this;
        var key = context.getLevel().registryAccess().registryOrThrow(Registries.PLACED_FEATURE)
                .getKey(feature);
        try (VanillaKelpPlacementScope.Scope ignored = VanillaKelpPlacementScope.enter(
                key, feature.placement(), context)) {
            return original.call(context, random, origin);
        }
    }

    @WrapOperation(
            method = "lambda$placeWithContext$4(Lnet/minecraft/world/level/levelgen/feature/ConfiguredFeature;"
                    + "Lnet/minecraft/world/level/levelgen/placement/PlacementContext;"
                    + "Lnet/minecraft/util/RandomSource;Lorg/apache/commons/lang3/mutable/MutableBoolean;"
                    + "Lnet/minecraft/core/BlockPos;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/"
                    + "ConfiguredFeature;place(Lnet/minecraft/world/level/WorldGenLevel;"
                    + "Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;"
                    + "Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean terrainDiffusion$vanillaKelpWorldCoordinateAcceptance(
            ConfiguredFeature<?, ?> configured, WorldGenLevel level, ChunkGenerator generator,
            RandomSource random, BlockPos pos, Operation<Boolean> original) {
        return VanillaKelpPlacementScope.accept(level, pos)
                && original.call(configured, level, generator, random, pos);
    }
}
