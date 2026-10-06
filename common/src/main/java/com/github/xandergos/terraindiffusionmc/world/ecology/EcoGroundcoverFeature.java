package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BambooLeaves;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** One climate-driven groundcover pass per chunk, after trees have been placed. */
public final class EcoGroundcoverFeature extends Feature<NoneFeatureConfiguration> {
    private static final long GROUNDCOVER_SALT = 0x47524F554E44434FL;
    private static final Block[] FLOWERS = {
            Blocks.DANDELION, Blocks.POPPY, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER
    };

    public EcoGroundcoverFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        int startX = Math.floorDiv(context.origin().getX(), 16) * 16;
        int startZ = Math.floorDiv(context.origin().getZ(), 16) * 16;
        int scale = WorldScaleManager.getCurrentScale();
        boolean changed = false;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int x = startX + localX;
                int z = startZ + localZ;
                // Leaves must not lift the placement onto the canopy. Logs and occupied
                // ground remain obstructions; never dig down through or clear them.
                BlockPos pos = new BlockPos(x,
                        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (!freeDryAir(level, pos) || !isEcologyBiome(level, pos)) continue;
                EcologyClassifier.Sample sample = NaturalEcology.sample(context.chunkGenerator(), x, z);
                if (sample == null) continue;
                RandomSource random = RandomSource.create(
                        FeatureEcologyMath.decorationSeed(level.getSeed(), x, z, GROUNDCOVER_SALT));
                if (random.nextFloat() >= FeatureEcologyMath.groundcoverChance(
                        sample.temperature(), sample.moisture(), sample.treeCover(), sample.slope(),
                        level.getSeed(), x, z, scale)) continue;
                FeatureEcologyMath.GroundcoverKind kind = FeatureEcologyMath.groundcoverWeights(
                        sample.temperature(), sample.moisture(), sample.treeCover(), sample.slope())
                        .choose(random.nextFloat());
                changed |= placeGroundcover(level, pos, kind, sample, random);
            }
        }
        return changed;
    }

    private static boolean isEcologyBiome(WorldGenLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(key -> key.location())
                .filter(id -> id.getNamespace().equals(TerrainDiffusionLifecycle.MOD_ID)
                        && id.getPath().startsWith("ecology/"))
                .isPresent();
    }

    private static boolean placeGroundcover(WorldGenLevel level, BlockPos pos,
                                            FeatureEcologyMath.GroundcoverKind kind,
                                            EcologyClassifier.Sample sample, RandomSource random) {
        if (kind == FeatureEcologyMath.GroundcoverKind.NONE) return false;
        BlockPos below = pos.below();
        BlockState soil = level.getBlockState(below);
        if (!level.getFluidState(below).isEmpty()) return false;
        boolean sand = soil.is(Blocks.SAND) || soil.is(Blocks.RED_SAND) || soil.is(BlockTags.SAND);
        boolean dirt = soil.is(BlockTags.DIRT) || soil.is(Blocks.MOSS_BLOCK)
                || GreeneriesSoilPolicy.isGroundcoverSupport(soil);
        // Explicit guards are intentional: survival rules can be relaxed by other mods.
        if (kind == FeatureEcologyMath.GroundcoverKind.CACTUS) {
            if (!sand || !cactusSpace(level, pos)) return false;
        } else if (kind == FeatureEcologyMath.GroundcoverKind.DEAD_BUSH) {
            if (!sand && !dirt) return false;
        } else if (sand || !dirt) {
            return false;
        }

        BlockState state = switch (kind) {
            case GRASS -> Blocks.SHORT_GRASS.defaultBlockState();
            case TALL_GRASS -> Blocks.TALL_GRASS.defaultBlockState();
            case FERN -> Blocks.FERN.defaultBlockState();
            case TALL_FERN -> Blocks.LARGE_FERN.defaultBlockState();
            case BERRY_BUSH -> Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 2);
            case AZALEA -> Blocks.AZALEA.defaultBlockState();
            case FLOWER -> FLOWERS[random.nextInt(FLOWERS.length)].defaultBlockState();
            case DEAD_BUSH -> Blocks.DEAD_BUSH.defaultBlockState();
            case CACTUS -> Blocks.CACTUS.defaultBlockState();
            case BAMBOO -> Blocks.BAMBOO.defaultBlockState();
            case MOSS -> Blocks.MOSS_CARPET.defaultBlockState();
            case MUSHROOM -> (random.nextBoolean() ? Blocks.BROWN_MUSHROOM : Blocks.RED_MUSHROOM).defaultBlockState();
            case NONE -> throw new IllegalStateException("NONE was already excluded");
        };
        if (kind == FeatureEcologyMath.GroundcoverKind.GRASS
                || kind == FeatureEcologyMath.GroundcoverKind.FERN) {
            String optionalPath = kind == FeatureEcologyMath.GroundcoverKind.GRASS
                    ? OptionalGroundcoverPolicy.grass(sample.temperature(), sample.moisture(), sample.treeCover(),
                            random.nextFloat(), random.nextFloat())
                    : OptionalGroundcoverPolicy.fern(sample.temperature(), sample.moisture(), sample.treeCover(),
                            random.nextFloat(), random.nextFloat());
            if (optionalPath != null) {
                var optional = BuiltInRegistries.BLOCK.getOptional(
                        ResourceLocation.fromNamespaceAndPath("greeneries", optionalPath));
                if (optional.isPresent()) {
                    BlockState candidate = optional.get().defaultBlockState();
                    if (candidate.canSurvive(level, pos)) state = candidate;
                }
            }
        }
        if (!state.canSurvive(level, pos)) return false;
        if (state.getBlock() instanceof DoublePlantBlock) return placeDoublePlant(level, pos, state);
        if (kind == FeatureEcologyMath.GroundcoverKind.BAMBOO) return placeBamboo(level, pos, state, random);
        return level.setBlock(pos, state, 2);
    }

    private static boolean freeDryAir(WorldGenLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getBlockState(pos).isAir()
                && level.getFluidState(pos).isEmpty();
    }

    private static boolean cactusSpace(WorldGenLevel level, BlockPos pos) {
        if (!freeDryAir(level, pos.above())) return false;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = pos.relative(direction);
            if (level.getBlockState(adjacent).isSolid() || !level.getFluidState(adjacent).isEmpty()) return false;
        }
        return true;
    }

    private static boolean placeDoublePlant(WorldGenLevel level, BlockPos pos, BlockState state) {
        BlockPos above = pos.above();
        if (!freeDryAir(level, above)) return false;
        BlockState original = level.getBlockState(pos);
        BlockState lower = state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
        if (!lower.canSurvive(level, pos) || !level.setBlock(pos, lower, 2)) return false;
        if (upper.canSurvive(level, above) && level.setBlock(above, upper, 2)) return true;
        // Do not leave an orphan lower half when the upper write is rejected.
        level.setBlock(pos, original, 2);
        return false;
    }

    private static boolean placeBamboo(WorldGenLevel level, BlockPos pos, BlockState state, RandomSource random) {
        int requestedHeight = 2 + random.nextInt(3);
        int height = 0;
        while (height < requestedHeight && freeDryAir(level, pos.above(height))) height++;
        boolean changed = false;
        for (int offset = 0; offset < height; offset++) {
            BlockPos part = pos.above(offset);
            BlockState stalk = state.setValue(BambooStalkBlock.LEAVES,
                    offset == height - 1 ? BambooLeaves.SMALL : BambooLeaves.NONE);
            if (!stalk.canSurvive(level, part) || !level.setBlock(part, stalk, 2)) break;
            changed = true;
        }
        return changed;
    }
}
