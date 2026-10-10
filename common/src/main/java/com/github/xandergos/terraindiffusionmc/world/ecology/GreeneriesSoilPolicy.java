package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Acceptance of native Greeneries grass/fern worldgen attempts on ecological soil. */
public final class GreeneriesSoilPolicy {
    public static final TagKey<Block> GRASS = tag("greeneries_grass_soils");
    public static final TagKey<Block> SOIL = tag("greeneries_ordinary_soils");
    public static final TagKey<Block> BARREN = tag("greeneries_barren_soils");

    private GreeneriesSoilPolicy() { }

    public static float acceptance(BlockState support) {
        return acceptance(support, 0.5F);
    }

    public static float acceptance(BlockState support, float ordinarySoilChance) {
        // Sand and water always win, even if a datapack also puts them in a soil tag.
        if (!support.getFluidState().isEmpty() || support.is(BlockTags.SAND)
                || support.is(Blocks.SAND) || support.is(Blocks.RED_SAND)) return 0.0F;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(support.getBlock());
        if (id.getNamespace().equals("terrain_slabs")
                && (id.getPath().equals("sand_slab") || id.getPath().equals("red_sand_slab"))) return 0.0F;
        // Grassy IW variants must take precedence over ordinary/barren parent categories.
        if (support.is(GRASS)) return 1.0F;
        if (support.is(BARREN)) return 0.1F;
        if (support.is(SOIL)) return ordinarySoilChance;
        return 0.0F;
    }

    public static boolean accepts(float acceptance, float roll) {
        return roll < acceptance;
    }

    public static boolean isGroundcoverSupport(BlockState support) {
        return support.is(GRASS) || support.is(SOIL) || support.is(BARREN);
    }

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath(TerrainDiffusionLifecycle.MOD_ID, path));
    }
}
