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
import net.minecraft.tags.BlockTags;

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
        WetlandEcology.Weights habitat = WetlandEcology.weights(sample);
        boolean coastalCanopy = FeatureEcologyMath.coastalTropicalCanopy(
                sample.temperature(), sample.moisture(), sample.slope(), sample.marineInfluence());
        // Do not trade an exposed sand-tip acacia for another perched tree. The
        // connected-land condition applies only to the humid coastal canopy.
        if (coastalCanopy && !hasCoastalLandFootprint(level, origin)) return false;
        boolean saturatedWetland = FeatureEcologyMath.wetlandSedimentCore(
                habitat.freshwaterSwamp(), habitat.mangrove(),
                sample.wetness(), sample.slope());
        TreeChoice choice = choose(sample, context.random(), saturatedWetland, coastalCanopy);
        if (choice == null) return false;
        BlockPos wetlandRoot = choice.wetland() ? shallowWetlandRoot(level, origin) : null;
        if (choice.mangrove() && wetlandRoot == null && !mangroveSite(level, origin, sample)) {
            // A saturated mangrove site without real mud/water support is not a
            // dry woodland site.  Leave it empty rather than planting a normal
            // tree in the wet core.
            if (saturatedWetland) return false;
            choice = new TreeChoice(chooseDry(sample, context.random(), coastalCanopy), false, false);
        }
        // Every dry route, including a rejected mangrove, keeps the configured
        // feature's original air placement. Only a verified shallow basin moves
        // the root down into its lowest water cell.
        BlockPos treeOrigin = choice.wetland() && wetlandRoot != null ? wetlandRoot : origin;
        // Check after every fallback has resolved: a rejected mangrove is now
        // a dry choice too. Actual mud overrides a differing climate sample.
        if (!choice.wetland() && !FeatureEcologyMath.allowsDryTree(saturatedWetland,
                level.getBlockState(treeOrigin.below()).is(Blocks.MUD))) return false;
        if (!choice.wetland() && habitat.freshwaterSwamp() + habitat.mangrove() + habitat.riparian() > .1f
                && !hasDryBankFootprint(level, treeOrigin.below())) return false;
        // A swamp oak can use wet mud, but it must still have a bank under its
        // root footprint.  Do not grow it from a one-column corner between
        // deeper water cells; mangrove arches deliberately keep their own
        // water-rooting behaviour.
        if (choice.wetland() && !choice.mangrove()
                && !hasGroundedSwampOakFootprint(level, treeOrigin)) return false;
        FluidState fluid = level.getFluidState(treeOrigin);
        BlockState support = choice.mangrove()
                ? Blocks.MANGROVE_PROPAGULE.defaultBlockState()
                : Blocks.OAK_SAPLING.defaultBlockState();
        // A vanilla tree feature performs its own clearance checks. Its wet root
        // placement is allowed only on a one/two-block source-water bed we have
        // already checked for natural wetland soil; dry trees retain sapling
        // survival exactly as before.
        if (choice.wetland() && !fluid.is(Fluids.WATER) && !support.canSurvive(level, treeOrigin)) return false;
        if (!choice.wetland() && (!fluid.isEmpty() || !support.canSurvive(level, treeOrigin))) return false;
        ConfiguredFeature<?, ?> tree = directTree(registry, choice.key(), terralithVariant(choice.key()));
        return tree != null && tree.place(level, generator, context.random(), treeOrigin);
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

    private record TreeChoice(ResourceKey<ConfiguredFeature<?, ?>> key, boolean mangrove, boolean wetland) { }

    private static TreeChoice choose(EcologyClassifier.Sample sample, RandomSource random,
                                     boolean saturatedWetland, boolean coastalCanopy) {
        WetlandEcology.Weights wetland = WetlandEcology.weights(sample);
        float mangrove = FeatureEcologyMath.clamp01(.88F * wetland.mangrove());
        float swampOak = FeatureEcologyMath.clamp01(.68F * wetland.freshwaterSwamp()
                + .28F * wetland.riparian());
        float roll = random.nextFloat();
        if (roll < mangrove) return new TreeChoice(MANGROVE, true, true);
        if (roll < Math.min(1.0F, mangrove + (1.0F - mangrove) * swampOak))
            return new TreeChoice(SWAMP_OAK, false, true);
        if (saturatedWetland) return null;
        return new TreeChoice(chooseDry(sample, random, coastalCanopy), false, false);
    }

    private static ResourceKey<ConfiguredFeature<?, ?>> chooseDry(EcologyClassifier.Sample sample, RandomSource random,
                                                                    boolean coastalCanopy) {
        FeatureEcologyMath.SpeciesWeights weights = FeatureEcologyMath.speciesWeights(sample.temperature(), sample.moisture());
        float roll = random.nextFloat() * weights.total();
        if ((roll -= weights.spruce()) < 0.0F) return SPRUCE;
        if ((roll -= weights.pine()) < 0.0F) return PINE;
        if ((roll -= weights.oak()) < 0.0F) return OAK;
        if ((roll -= weights.birch()) < 0.0F) return BIRCH;
        return roll < weights.acacia() ? (coastalCanopy ? JUNGLE : ACACIA) : JUNGLE;
    }

    private static ResourceKey<ConfiguredFeature<?, ?>> terralithVariant(ResourceKey<ConfiguredFeature<?, ?>> vanilla) {
        if (vanilla == OAK) return TERRALITH_OAK;
        if (vanilla == BIRCH) return TERRALITH_BIRCH;
        if (vanilla == SPRUCE) return TERRALITH_SPRUCE;
        if (vanilla == PINE) return TERRALITH_PINE;
        return vanilla;
    }

    /** Lowland mangrove interiors can root in exposed wet mud between inundations.
     * Retain native propagule support, air clearance and the water-edge route for other soils.
     */
    private static boolean mangroveSite(WorldGenLevel level, BlockPos origin, EcologyClassifier.Sample sample) {
        if (!level.getBlockState(origin).isAir() || !level.getFluidState(origin).isEmpty()
                || !Blocks.MANGROVE_PROPAGULE.defaultBlockState().canSurvive(level, origin))
            return false;
        BlockPos bank = origin.below();
        if (sample.marineInfluence() >= .45f && level.getBlockState(bank).is(Blocks.MUD)) return true;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (shallowStillWater(level, bank.relative(direction))) return true;
        }
        return false;
    }

    /** Returns the lowest source-water cell in a shallow natural wetland column.
     * Tree features receive the same root position vanilla uses for water-grown
     * trees, rather than an air position above the water surface.
     */
    private static BlockPos shallowWetlandRoot(WorldGenLevel level, BlockPos origin) {
        BlockPos water = level.getFluidState(origin).is(Fluids.WATER) ? origin : origin.below();
        if (!level.getFluidState(water).is(Fluids.WATER) || !level.getFluidState(water).isSource()) return null;
        int depth = 0;
        BlockPos cursor = water;
        while (level.getFluidState(cursor).is(Fluids.WATER) && level.getFluidState(cursor).isSource()) {
            if (++depth > 2) return null;
            cursor = cursor.below();
        }
        BlockState bed = level.getBlockState(cursor);
        return isWetlandSoil(bed) ? water.below(depth - 1) : null;
    }

    private static boolean isWetlandSoil(BlockState state) {
        return state.is(Blocks.MUD) || state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL);
    }

    // Land on two adjoining sides connects the trunk to a bank. A single
    // narrow spit, isolated cap, or water-covered soil beneath a clear surface
    // is not a dry-tree site. Mangrove root arches use their own placement.
    private static boolean hasDryBankFootprint(WorldGenLevel level, BlockPos base) {
        boolean eastWest = false, northSouth = false;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos inner = dryGroundNear(level, base.relative(dir));
            if (inner == null || dryGroundNear(level, inner.relative(dir)) == null) continue;
            if (dir.getAxis() == Direction.Axis.X) eastWest = true;
            else northSouth = true;
            if (eastWest && northSouth) return true;
        }
        return false;
    }

    /** A coastal canopy needs an actual bank, not one grass column on a beach. */
    private static boolean hasCoastalLandFootprint(WorldGenLevel level, BlockPos origin) {
        BlockPos base = origin.below();
        if (!dryLandSoil(level, base)) return false;
        boolean eastWest = false, northSouth = false;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!dryLandSoil(level, base.relative(direction))) continue;
            if (direction.getAxis() == Direction.Axis.X) eastWest = true;
            else northSouth = true;
        }
        return eastWest && northSouth;
    }

    private static boolean dryLandSoil(WorldGenLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.DIRT)
                && level.getFluidState(pos.above()).isEmpty();
    }

    private static BlockPos dryGroundNear(WorldGenLevel level, BlockPos column) {
        for (int dy = 1; dy >= -1; dy--) {
            BlockPos ground = column.offset(0, dy, 0);
            BlockState soil = level.getBlockState(ground);
            if (!soil.is(Blocks.MUD) && isWetlandSoil(soil)
                    && level.getFluidState(ground.above()).isEmpty()
                    && !level.getBlockState(ground.above()).blocksMotion()) return ground;
        }
        return null;
    }

    /**
     * Every cardinal cell must retain natural ground at the intended tree base
     * or one step below it.  This admits a gentle bank while rejecting a root
     * perched above a two-block water drop.  The check is only for swamp-oak
     * wetland placement, not mangrove root arches or ordinary forest trees.
     */
    private static boolean hasGroundedSwampOakFootprint(WorldGenLevel level, BlockPos treeOrigin) {
        BlockPos base = treeOrigin.below();
        return naturalGroundAtOrOneBelow(level, base.relative(Direction.NORTH))
                && naturalGroundAtOrOneBelow(level, base.relative(Direction.SOUTH))
                && naturalGroundAtOrOneBelow(level, base.relative(Direction.EAST))
                && naturalGroundAtOrOneBelow(level, base.relative(Direction.WEST));
    }

    private static boolean naturalGroundAtOrOneBelow(WorldGenLevel level, BlockPos pos) {
        return isWetlandSoil(level.getBlockState(pos))
                || isWetlandSoil(level.getBlockState(pos.below()));
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
