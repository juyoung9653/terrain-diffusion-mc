package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.WetlandEcology;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.core.Direction;

/** Places a continuous ecological mix of direct tree configured features. */
public final class EcoTreesFeature extends Feature<NoneFeatureConfiguration> {
    private static final ResourceKey<ConfiguredFeature<?, ?>> OAK = key("minecraft", "oak");
    private static final ResourceKey<ConfiguredFeature<?, ?>> BIRCH = key("minecraft", "birch");
    private static final ResourceKey<ConfiguredFeature<?, ?>> SPRUCE = key("minecraft", "spruce");
    private static final ResourceKey<ConfiguredFeature<?, ?>> PINE = key("minecraft", "pine");
    private static final ResourceKey<ConfiguredFeature<?, ?>> ACACIA = key("minecraft", "acacia");
    private static final ResourceKey<ConfiguredFeature<?, ?>> JUNGLE = key("minecraft", "jungle_tree");
    private static final ResourceKey<ConfiguredFeature<?, ?>> SWAMP_OAK = key("minecraft", "swamp_oak");
    private static final ResourceKey<ConfiguredFeature<?, ?>> MANGROVE = key("minecraft", "mangrove");
    private static final ResourceKey<ConfiguredFeature<?, ?>> TERRALITH_OAK = key("terralith", "forest/vanilla/oak");
    private static final ResourceKey<ConfiguredFeature<?, ?>> TERRALITH_BIRCH = key("terralith", "birch/trees_forest");
    private static final ResourceKey<ConfiguredFeature<?, ?>> TERRALITH_SPRUCE = key("terralith", "taiga/mid/spruce_mid");
    private static final ResourceKey<ConfiguredFeature<?, ?>> TERRALITH_PINE = key("terralith", "taiga/mid/pine_mid");

    public EcoTreesFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        BlockPos origin = context.origin();
        ChunkGenerator generator = context.chunkGenerator();
        EcologyClassifier.Sample sample = NaturalEcology.sample(generator, origin.getX(), origin.getZ());
        if (sample == null || context.random().nextFloat() >= FeatureEcologyMath.treePlacementChance(
                sample.treeCover(), context.level().getSeed(), origin.getX(), origin.getZ(),
                WorldScaleManager.getCurrentScale())) return false;

        WorldGenLevel level = context.level();
        Registry<ConfiguredFeature<?, ?>> registry = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        TreeChoice choice = choose(sample, context.random());
        if (choice.mangrove() && !mangroveSite(level, origin)) {
            // A predicted wet habitat without a real, shallow water edge remains ordinary woodland.
            choice = new TreeChoice(chooseDry(sample, context.random()), false);
        }
        FluidState fluid = level.getFluidState(origin);
        BlockState support = choice.mangrove()
                ? Blocks.MANGROVE_PROPAGULE.defaultBlockState()
                : Blocks.OAK_SAPLING.defaultBlockState();
        if (!fluid.isEmpty() || !support.canSurvive(level, origin)) return false;
        ConfiguredFeature<?, ?> tree = directTree(registry, choice.key(), terralithVariant(choice.key()));
        return tree != null && tree.place(level, generator, context.random(), origin);
    }

    private static ConfiguredFeature<?, ?> directTree(Registry<ConfiguredFeature<?, ?>> registry,
                                                        ResourceKey<ConfiguredFeature<?, ?>> vanilla,
                                                        ResourceKey<ConfiguredFeature<?, ?>> terralith) {
        ConfiguredFeature<?, ?> custom = directTree(registry, terralith);
        return custom != null ? custom : directTree(registry, vanilla);
    }

    private static ConfiguredFeature<?, ?> directTree(Registry<ConfiguredFeature<?, ?>> registry,
                                                        ResourceKey<ConfiguredFeature<?, ?>> key) {
        return registry.getHolder(key)
                .map(Holder::value)
                .filter(feature -> feature.feature() instanceof TreeFeature)
                .orElse(null);
    }

    private record TreeChoice(ResourceKey<ConfiguredFeature<?, ?>> key, boolean mangrove) { }

    private static TreeChoice choose(EcologyClassifier.Sample sample, RandomSource random) {
        WetlandEcology.Weights wetland = WetlandEcology.weights(sample);
        float mangrove = FeatureEcologyMath.clamp01(.88F * wetland.mangrove());
        float swampOak = FeatureEcologyMath.clamp01(.68F * wetland.freshwaterSwamp()
                + .28F * wetland.riparian());
        float roll = random.nextFloat();
        if (roll < mangrove) return new TreeChoice(MANGROVE, true);
        if (roll < Math.min(1.0F, mangrove + (1.0F - mangrove) * swampOak))
            return new TreeChoice(SWAMP_OAK, false);
        return new TreeChoice(chooseDry(sample, random), false);
    }

    private static ResourceKey<ConfiguredFeature<?, ?>> chooseDry(EcologyClassifier.Sample sample, RandomSource random) {
        FeatureEcologyMath.SpeciesWeights weights = FeatureEcologyMath.speciesWeights(sample.temperature(), sample.moisture());
        float roll = random.nextFloat() * weights.total();
        if ((roll -= weights.spruce()) < 0.0F) return SPRUCE;
        if ((roll -= weights.pine()) < 0.0F) return PINE;
        if ((roll -= weights.oak()) < 0.0F) return OAK;
        if ((roll -= weights.birch()) < 0.0F) return BIRCH;
        return roll < weights.acacia() ? ACACIA : JUNGLE;
    }

    private static ResourceKey<ConfiguredFeature<?, ?>> terralithVariant(ResourceKey<ConfiguredFeature<?, ?>> vanilla) {
        if (vanilla == OAK) return TERRALITH_OAK;
        if (vanilla == BIRCH) return TERRALITH_BIRCH;
        if (vanilla == SPRUCE) return TERRALITH_SPRUCE;
        if (vanilla == PINE) return TERRALITH_PINE;
        return vanilla;
    }

    /** Mangroves need an air planting cell, valid propagule support, and a nearby shallow still-water edge. */
    private static boolean mangroveSite(WorldGenLevel level, BlockPos origin) {
        if (!level.getBlockState(origin).isAir() || !level.getFluidState(origin).isEmpty()
                || !Blocks.MANGROVE_PROPAGULE.defaultBlockState().canSurvive(level, origin))
            return false;
        BlockPos bank = origin.below();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (shallowStillWater(level, bank.relative(direction))) return true;
        }
        return false;
    }

    private static boolean shallowStillWater(WorldGenLevel level, BlockPos water) {
        FluidState surface = level.getFluidState(water);
        if (!surface.is(Fluids.WATER) || !surface.isSource()) return false;
        BlockPos below = water.below();
        FluidState lower = level.getFluidState(below);
        if (!lower.is(Fluids.WATER)) return true;
        return lower.isSource() && !level.getFluidState(below.below()).is(Fluids.WATER);
    }

    private static ResourceKey<ConfiguredFeature<?, ?>> key(String namespace, String path) {
        return ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }
}
