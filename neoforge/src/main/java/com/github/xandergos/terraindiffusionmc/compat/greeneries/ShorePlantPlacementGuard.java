package com.github.xandergos.terraindiffusionmc.compat.greeneries;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.github.xandergos.terraindiffusionmc.world.ecology.ShorePlantPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Applies only to optional Greeneries worldgen reeds; no dependency on the mod's classes. */
public final class ShorePlantPlacementGuard {
    private static final TagKey<Block> REEDS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("greeneries", "reeds"));
    private static final TagKey<Biome> SWAMPS = biomeTag("is_swamp");
    private static final TagKey<Biome> BEACHES = biomeTag("is_beach");
    private static final TagKey<Biome> STONY_SHORES = biomeTag("is_stony_shores");

    private ShorePlantPlacementGuard() { }

    public static boolean allowsPlacement(WorldGenLevel level, BlockPos pos, BlockState plant) {
        var id = BuiltInRegistries.BLOCK.getKey(plant.getBlock());
        if (!plant.is(REEDS) && !(id.getNamespace().equals("greeneries")
                && (id.getPath().equals("reed") || id.getPath().equals("cattail")))) return true;
        TerrainDiffusionBiomeSource source = TerrainDiffusionBiomeSources.find(
                level.getLevel().getChunkSource().getGenerator().getBiomeSource());
        if (source == null || !source.naturalBiomes()) return true;
        var biome = level.getBiome(pos);
        boolean freshwater = biome.is(BiomeTags.IS_RIVER) || biome.is(SWAMPS)
                || biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP);
        boolean marine = biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_BEACH)
                || biome.is(BEACHES) || biome.is(STONY_SHORES) || biome.is(Biomes.STONY_SHORE);
        boolean sandy = level.getBlockState(pos.below()).is(BlockTags.SAND);
        return ShorePlantPolicy.allows(marine, freshwater, sandy, level.getSeed(), pos.getX(), pos.getZ());
    }

    private static TagKey<Biome> biomeTag(String path) {
        return TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("c", path));
    }
}
