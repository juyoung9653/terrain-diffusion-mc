package com.github.xandergos.terraindiffusionmc.mixin.hybridaquatic;

import com.github.xandergos.terraindiffusionmc.compat.hybridaquatic.HybridAquaticKelpPlacementScope;
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

/** Changes only the HA kelp count and thinning while keeping its native filters. */
@Mixin(PlacedFeature.class)
public abstract class HybridAquaticKelpPlacedFeatureMixin {

    @WrapMethod(method = "placeWithContext")
    private boolean terrainDiffusion$kelpScope(
            PlacementContext context, RandomSource random, BlockPos origin, Operation<Boolean> original) {
        var key = context.getLevel().registryAccess()
                .registryOrThrow(Registries.PLACED_FEATURE)
                .getKey((PlacedFeature) (Object) this);
        try (HybridAquaticKelpPlacementScope.Scope ignored = HybridAquaticKelpPlacementScope.enter(key, ((PlacedFeature) (Object) this).placement(), context)) {
            return original.call(context, random, origin);
        }
    }

    @WrapOperation(
            method = "lambda$placeWithContext$4(Lnet/minecraft/world/level/levelgen/feature/ConfiguredFeature;Lnet/minecraft/world/level/levelgen/placement/PlacementContext;Lnet/minecraft/util/RandomSource;Lorg/apache/commons/lang3/mutable/MutableBoolean;Lnet/minecraft/core/BlockPos;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/ConfiguredFeature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean terrainDiffusion$kelpWorldCoordinateAcceptance(
            ConfiguredFeature<?, ?> configured, WorldGenLevel level, ChunkGenerator generator,
            RandomSource random, BlockPos pos, Operation<Boolean> original) {
        return HybridAquaticKelpPlacementScope.accept(level, pos)
                && original.call(configured, level, generator, random, pos);
    }
}
