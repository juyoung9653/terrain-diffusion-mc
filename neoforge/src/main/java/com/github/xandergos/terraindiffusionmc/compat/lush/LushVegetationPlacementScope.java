package com.github.xandergos.terraindiffusionmc.compat.lush;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.github.xandergos.terraindiffusionmc.world.ecology.GreeneriesSoilPolicy;
import com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology;
import com.github.xandergos.terraindiffusionmc.world.ecology.FeatureEcologyMath;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.BooleanSupplier;

/** Scopes Lush's direct block writes to the TD ecology soil policy. */
public final class LushVegetationPlacementScope {
    private static final ThreadLocal<RandomSource> RANDOM = new ThreadLocal<>();

    private LushVegetationPlacementScope() { }

    public static boolean place(WorldGenLevel level, BlockPos origin, RandomSource random,
                                BooleanSupplier placement) {
        if (!isEcologyBiome(level, origin)) return placement.getAsBoolean();
        // The biome modifier is registry-scoped, so reject its feature if a TD ecology
        // biome is reused by a generator other than the natural TD biome source.
        if (!isNaturalTerrainDiffusion(level)) return false;
        RandomSource previous = RANDOM.get();
        RANDOM.set(random);
        try {
            return placement.getAsBoolean();
        } finally {
            if (previous == null) RANDOM.remove();
            else RANDOM.set(previous);
        }
    }

    public static boolean canGrowOn(BlockState support, boolean mushroom) {
        return RANDOM.get() != null && !mushroom && GreeneriesSoilPolicy.isGroundcoverSupport(support);
    }

    public static boolean allowsPlant(WorldGenLevel level, BlockPos pos) {
        RandomSource random = RANDOM.get();
        if (random == null) return true;
        // Lush chooses one biome at the placed-feature origin, but individual attempts
        // range three blocks away. Do not carry TD's fallback boost across that border.
        if (!isEcologyBiome(level, pos)) return false;
        float chance = GreeneriesSoilPolicy.acceptance(level.getBlockState(pos.below()), 0.3F);
        var climate = NaturalEcology.cachedSurfaceSample(level.getLevel().getChunkSource().getGenerator(), pos.getX(), pos.getZ());
        if (climate != null) chance *= FeatureEcologyMath.coldGroundcoverAcceptance(climate.climate().temperature());
        return chance >= 1.0F || (chance > 0.0F
                && GreeneriesSoilPolicy.accepts(chance, random.nextFloat()));
    }

    private static boolean isNaturalTerrainDiffusion(WorldGenLevel level) {
        TerrainDiffusionBiomeSource source = TerrainDiffusionBiomeSources.find(
                level.getLevel().getChunkSource().getGenerator().getBiomeSource());
        return source != null && source.naturalBiomes();
    }

    private static boolean isEcologyBiome(WorldGenLevel level, BlockPos origin) {
        return level.getBiome(origin).unwrapKey()
                .map(key -> key.location())
                .filter(location -> location.getNamespace().equals(TerrainDiffusionLifecycle.MOD_ID))
                .filter(location -> location.getPath().startsWith("ecology/"))
                .isPresent();
    }
}
