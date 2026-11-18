package com.github.xandergos.terraindiffusionmc.compat.greeneries;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.world.ecology.GreeneriesSoilPolicy;
import com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology;
import com.github.xandergos.terraindiffusionmc.world.ecology.FeatureEcologyMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Keeps grass-like Greeneries worldgen off TD's deliberately sandy ecology surfaces. */
public final class GreeneriesPlacementGuard {
    private static final TagKey<Block> GREENERIES_GRASS = optionalBlockTag("greeneries", "grass");
    private static final TagKey<Block> GREENERIES_FERN = optionalBlockTag("greeneries", "fern");
    private static final ResourceLocation TERRAIN_SLABS_SAND = id("terrain_slabs", "sand_slab");
    private static final ResourceLocation TERRAIN_SLABS_RED_SAND = id("terrain_slabs", "red_sand_slab");

    private GreeneriesPlacementGuard() {
    }

    public static boolean allowsPlacement(WorldGenLevel level, BlockPos pos, BlockState plant) {
        if (!isGrassOrFern(plant)) return true;
        // Ordinary grass/dirt placements need no biome lookup; only sand can be rejected.
        if (!isSandSurface(level.getBlockState(pos.below()))) return true;
        return !isEcologyBiome(level, pos);
    }

    public static boolean acceptsNativeAttempt(WorldGenLevel level, BlockPos pos, BlockState plant) {
        return !GreeneriesNativePatchScope.active() || acceptsNaturalGrass(level, pos, plant);
    }

    /** Explicit entry point for audited native writers that bypass SimpleBlockFeature. */
    public static boolean acceptsNaturalGrass(WorldGenLevel level, BlockPos pos, BlockState plant) {
        if (!GreeneriesSoilPolicy.nativeWorldgenPolicyEnabled() || !isGrassOrFern(plant)
                || !isEcologyBiome(level, pos)) return true;
        float chance = GreeneriesSoilPolicy.acceptance(level.getBlockState(pos.below()));
        var generator = level.getLevel().getChunkSource().getGenerator();
        var climate = NaturalEcology.cachedSurfaceSample(generator, pos.getX(), pos.getZ());
        if (climate != null) chance *= FeatureEcologyMath.coldGroundcoverAcceptance(climate.climate().temperature());
        // This lookup is canonical rather than cache-only: native placement may
        // run after the surface feature and must not change with feature order.
        int waterLevel = NaturalEcology.nearestPlannedWetlandWaterLevel(generator, pos.getX(), pos.getZ(), 8);
        chance *= FeatureEcologyMath.plannedWetlandGroundcoverFactor(pos.getY() - 1, waterLevel);
        if (isAffectedWetlandOrRiparianBiome(level, pos)) {
            chance *= FeatureEcologyMath.nativeWetlandGroundcoverFactor(isFern(plant),
                    hasOverheadCanopy(level, pos), level.getSeed(), pos.getX(), pos.getZ());
        }
        return GreeneriesSoilPolicy.acceptsColumn(level.getSeed(), pos.getX(), pos.getZ(), chance);
    }

    /** The only three ecology biomes to which the native Grass patch modifier is restored. */
    private static boolean isAffectedWetlandOrRiparianBiome(WorldGenLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(key -> key.location()).map(location ->
                location.equals(id(TerrainDiffusionLifecycle.MOD_ID, "ecology/freshwater_swamp"))
                        || location.equals(id(TerrainDiffusionLifecycle.MOD_ID, "ecology/mangrove_swamp"))
                        || location.equals(id(TerrainDiffusionLifecycle.MOD_ID, "ecology/riparian_forest")))
                .orElse(false);
    }

    /**
     * The patch feature runs after this biome's EcoTrees feature.  A single
     * vertical probe detects real overhead foliage without heightmap ambiguity
     * or terrain-provider work; it reads at most seven blocks per admitted try.
     */
    private static boolean hasOverheadCanopy(WorldGenLevel level, BlockPos pos) {
        for (int dy = 2; dy <= 8; dy++) {
            BlockState state = level.getBlockState(pos.above(dy));
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) return true;
        }
        return false;
    }

    private static boolean isEcologyBiome(WorldGenLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey()
                .map(key -> key.location())
                .filter(location -> location.getNamespace().equals(TerrainDiffusionLifecycle.MOD_ID))
                .filter(location -> location.getPath().startsWith("ecology/"))
                .isPresent();
    }

    private static boolean isSandSurface(BlockState support) {
        return support.is(BlockTags.SAND) || support.is(Blocks.SAND) || support.is(Blocks.RED_SAND)
                || BuiltInRegistries.BLOCK.getKey(support.getBlock()).equals(TERRAIN_SLABS_SAND)
                || BuiltInRegistries.BLOCK.getKey(support.getBlock()).equals(TERRAIN_SLABS_RED_SAND);
    }

    private static boolean isGrassOrFern(BlockState plant) {
        return plant.is(Blocks.SHORT_GRASS) || plant.is(Blocks.TALL_GRASS)
                || plant.is(Blocks.FERN) || plant.is(Blocks.LARGE_FERN)
                || plant.is(GREENERIES_GRASS) || plant.is(GREENERIES_FERN);
    }

    private static boolean isFern(BlockState plant) {
        return plant.is(Blocks.FERN) || plant.is(Blocks.LARGE_FERN) || plant.is(GREENERIES_FERN);
    }

    private static TagKey<Block> optionalBlockTag(String namespace, String path) {
        return TagKey.create(Registries.BLOCK, id(namespace, path));
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}
