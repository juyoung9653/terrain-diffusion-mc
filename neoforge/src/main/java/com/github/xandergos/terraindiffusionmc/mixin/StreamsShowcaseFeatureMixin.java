package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;

/** Keeps Streams' feature hook from constructing an engine in a one-biome showcase. */
@Mixin(ConfiguredFeature.class)
public final class StreamsShowcaseFeatureMixin {
    @WrapMethod(
            method = "place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
            require = 1, expect = 1)
    private boolean terrainDiffusion$placeWithoutStreamsHook(
            WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
            Operation<Boolean> original) {
        ConfiguredFeature<?, ?> self = (ConfiguredFeature<?, ?>) (Object) this;
        if (!isEcologyShowcase(generator)) {
            return original.call(level, generator, random, origin);
        }
        if (self.feature().getClass().getName().startsWith("dev.streamsreflowing.")) {
            return false;
        }
        // Wrap the entire method: HEAD callback priority cannot reliably precede
        // another mod's injected engine lookup. Feature.place is not recursive here.
        return placeVanilla(self, level, generator, random, origin);
    }

    private static boolean isEcologyShowcase(ChunkGenerator generator) {
        return generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource source
                && !source.ecologyShowcase().isEmpty();
    }

    private static <C extends FeatureConfiguration, F extends Feature<C>> boolean placeVanilla(
            ConfiguredFeature<C, F> configured, WorldGenLevel level, ChunkGenerator generator,
            RandomSource random, BlockPos origin) {
        return configured.feature().place(configured.config(), level, generator, random, origin);
    }
}
