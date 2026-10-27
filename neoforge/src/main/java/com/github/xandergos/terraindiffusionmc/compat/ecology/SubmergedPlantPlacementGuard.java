package com.github.xandergos.terraindiffusionmc.compat.ecology;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.github.xandergos.terraindiffusionmc.world.ecology.SubmergedPlantPolicy;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;

/** Worldgen-only seagrass gate; reads only the candidate's already loaded column. */
public final class SubmergedPlantPlacementGuard {
    private static final TagKey<Biome> SWAMPS = biomeTag("is_swamp");
    private static final TagKey<Biome> BEACHES = biomeTag("is_beach");
    private static final TagKey<Biome> STONY_SHORES = biomeTag("is_stony_shores");
    private static final int MAX_DEPTH_READ = 8;
    private static final LongAdder NON_NATURAL_TERRAIN = new LongAdder();
    private static final LongAdder ALLOWED = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();
    private static final LongAdder DRY = new LongAdder();

    private SubmergedPlantPlacementGuard() { }

    public static boolean allowsPlacement(WorldGenLevel level, BlockPos pos) {
        TerrainDiffusionBiomeSource source = TerrainDiffusionBiomeSources.find(
                level.getLevel().getChunkSource().getGenerator().getBiomeSource());
        if (source == null || !source.naturalBiomes()) {
            NON_NATURAL_TERRAIN.increment();
            return true;
        }
        if (!level.hasChunkAt(pos) || pos.getY() <= level.getMinBuildHeight()) {
            REJECTED.increment();
            return false;
        }
        boolean submerged = level.getBlockState(pos).is(Blocks.WATER);
        if (!submerged) {
            DRY.increment();
            return false;
        }

        int depth = 0;
        BlockPos.MutableBlockPos scan = pos.mutable();
        while (depth < MAX_DEPTH_READ && scan.getY() < level.getMaxBuildHeight()
                && level.getBlockState(scan).is(Blocks.WATER)) {
            depth++;
            scan.move(0, 1, 0);
        }
        var floor = level.getBlockState(pos.below());
        boolean sediment = floor.is(BlockTags.SAND) || floor.is(BlockTags.DIRT)
                || floor.is(Blocks.GRAVEL) || floor.is(Blocks.CLAY) || floor.is(Blocks.MUD);
        var biome = level.getBiome(pos);
        boolean freshwater = biome.is(BiomeTags.IS_RIVER) || biome.is(SWAMPS)
                || biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP);
        boolean marine = biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_BEACH)
                || biome.is(BEACHES) || biome.is(STONY_SHORES) || biome.is(Biomes.STONY_SHORE);
        boolean allowed = SubmergedPlantPolicy.allows(true, submerged, marine, freshwater,
                sediment, depth, level.getSeed(), pos.getX(), pos.getZ());
        if (allowed) ALLOWED.increment(); else REJECTED.increment();
        return allowed;
    }

    public static Counts counts() {
        return new Counts(NON_NATURAL_TERRAIN.sum(), ALLOWED.sum(), REJECTED.sum(), DRY.sum());
    }

    public record Counts(long nonNaturalTerrain, long allowed, long rejected, long dry) { }

    private static TagKey<Biome> biomeTag(String path) {
        return TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("c", path));
    }
}
