package com.github.xandergos.terraindiffusionmc.compat.greeneries;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Keeps grass-like Greeneries worldgen off TD's deliberately sandy ecology surfaces. */
public final class GreeneriesPlacementGuard {
    private static final TagKey<Block> GREENERIES_GRASS = optionalBlockTag("greeneries", "grass");
    private static final TagKey<Block> GREENERIES_FERN = optionalBlockTag("greeneries", "fern");
    private static final ResourceLocation TERRAIN_SLABS_SAND = id("terrain_slabs", "sand_slab");

    private GreeneriesPlacementGuard() {
    }

    public static boolean allowsPlacement(WorldGenLevel level, BlockPos pos, BlockState plant) {
        if (!isGrassOrFern(plant)) return true;
        // Ordinary grass/dirt placements need no biome lookup; only sand can be rejected.
        if (!isSandSurface(level.getBlockState(pos.below()))) return true;
        return !isEcologyBiome(level, pos);
    }

    private static boolean isEcologyBiome(WorldGenLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey()
                .map(key -> key.location())
                .filter(location -> location.getNamespace().equals(TerrainDiffusionLifecycle.MOD_ID))
                .filter(location -> location.getPath().startsWith("ecology/"))
                .isPresent();
    }

    private static boolean isSandSurface(BlockState support) {
        return support.is(Blocks.SAND)
                || BuiltInRegistries.BLOCK.getKey(support.getBlock()).equals(TERRAIN_SLABS_SAND);
    }

    private static boolean isGrassOrFern(BlockState plant) {
        return plant.is(Blocks.SHORT_GRASS) || plant.is(Blocks.TALL_GRASS)
                || plant.is(Blocks.FERN) || plant.is(Blocks.LARGE_FERN)
                || plant.is(GREENERIES_GRASS) || plant.is(GREENERIES_FERN);
    }

    private static TagKey<Block> optionalBlockTag(String namespace, String path) {
        return TagKey.create(Registries.BLOCK, id(namespace, path));
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}
