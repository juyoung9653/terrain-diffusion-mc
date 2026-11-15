package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.WetlandEcology;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.Fluids;

/**
 * Wetland-only decoration kept separate from the normal groundcover feature: installed
 * Greeneries policies can remove the latter without disabling reeds and lily pads.
 */
public final class EcoWetlandGroundcoverFeature extends Feature<NoneFeatureConfiguration> {
    private static final long REED_SALT = 0x5745545F52454544L;
    private static final long LILY_SALT = 0x5745545F4C494C59L;

    public EcoWetlandGroundcoverFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        int startX = Math.floorDiv(context.origin().getX(), 16) * 16;
        int startZ = Math.floorDiv(context.origin().getZ(), 16) * 16;
        boolean changed = false;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int x = startX + localX;
                int z = startZ + localZ;
                BlockPos bank = new BlockPos(x,
                        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                NaturalEcology.SurfaceSample surface = NaturalEcology.surfaceSample(context.chunkGenerator(), x, z);
                if (surface == null || !isEligibleBiome(level, bank, surface)) continue;
                EcologyClassifier.Sample sample = surface.climate();
                WetlandEcology.Weights wetland = WetlandEcology.weights(sample);
                changed |= placeLilyPad(level, x, z, sample, wetland);
                changed |= placeReed(level, bank, sample, wetland);
            }
        }
        return changed;
    }

    private static boolean placeReed(WorldGenLevel level, BlockPos pos,
                                     EcologyClassifier.Sample sample,
                                     WetlandEcology.Weights wetland) {
        float chance = FeatureEcologyMath.wetlandBankPlantChance(wetland.reedMarsh(),
                wetland.freshwaterSwamp(), wetland.mangrove(), wetland.riparian(),
                sample.temperature(), sample.slope());
        if (!FeatureEcologyMath.coordinateChance(level.getSeed(), pos.getX(), pos.getZ(), REED_SALT, chance))
            return false;
        BlockState reed = Blocks.SUGAR_CANE.defaultBlockState();
        BlockPos below = pos.below();
        if (!freeDryAir(level, pos) || !level.getFluidState(below).isEmpty()
                || !reed.canSurvive(level, pos)) return false;
        return level.setBlock(pos, reed, 2);
    }

    private static boolean placeLilyPad(WorldGenLevel level, int x, int z,
                                        EcologyClassifier.Sample sample,
                                        WetlandEcology.Weights wetland) {
        float chance = .42F * FeatureEcologyMath.wetlandBankPlantChance(wetland.reedMarsh(),
                wetland.freshwaterSwamp(), wetland.mangrove(), wetland.riparian(),
                sample.temperature(), sample.slope());
        if (!FeatureEcologyMath.coordinateChance(level.getSeed(), x, z, LILY_SALT, chance)) return false;
        int waterY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        BlockPos water = new BlockPos(x, waterY, z);
        BlockPos lilyPos = water.above();
        if (level.isOutsideBuildHeight(lilyPos) || !freeDryAir(level, lilyPos)) return false;
        var fluid = level.getFluidState(water);
        if (!fluid.is(Fluids.WATER) || !fluid.isSource() || !shallowStillWater(level, water)) return false;
        BlockState lily = Blocks.LILY_PAD.defaultBlockState();
        return lily.canSurvive(level, lilyPos) && level.setBlock(lilyPos, lily, 2);
    }

    private static boolean shallowStillWater(WorldGenLevel level, BlockPos water) {
        BlockPos below = water.below();
        var lower = level.getFluidState(below);
        if (!lower.is(Fluids.WATER)) return true;
        return lower.isSource() && !level.getFluidState(below.below()).is(Fluids.WATER);
    }

    private static boolean isEligibleBiome(WorldGenLevel level, BlockPos pos,
                                           NaturalEcology.SurfaceSample surface) {
        boolean ecology = level.getBiome(pos).unwrapKey().map(key -> key.location())
                .filter(id -> id.getNamespace().equals(TerrainDiffusionLifecycle.MOD_ID)
                        && id.getPath().startsWith("ecology/"))
                .isPresent();
        if (ecology) return true;
        // Streams can replace a retained TD land column's quart biome with river.
        // Admit only that known overlay, never ocean or another mod's wet biome.
        return level.getBiome(pos).is(BiomeTags.IS_RIVER)
                && surface.terrainBiomeId() == 0 && surface.climate().biomeId() != 0;
    }

    private static boolean freeDryAir(WorldGenLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getBlockState(pos).isAir()
                && level.getFluidState(pos).isEmpty();
    }
}
