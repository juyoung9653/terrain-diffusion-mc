package com.github.xandergos.terraindiffusionmc.compat.terrainslabs;

import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankSurface;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;

/** Intercepts only the audited Terrain Slabs feature's final native placement. */
public final class TerrainSlabSurface {
    private TerrainSlabSurface() { }

    public static BlockState material(LevelAccessor world, BlockPos pos, BlockState proposed) {
        if (!(world instanceof WorldGenLevel level) || !(proposed.getBlock() instanceof SlabBlock)) return proposed;
        int version = WorldNaturalRiverState.version(level.getLevel());
        if (version < 10 || !generated(proposed) || !proposed.getFluidState().isEmpty()
                || !level.getFluidState(pos).isEmpty()) return proposed;
        String source = BuiltInRegistries.BLOCK.getKey(proposed.getBlock()).toString();
        if (!source.equals("terrain_slabs:grass_slab") && !source.equals("terrain_slabs:dirt_slab")) return proposed;
        // The native feature scans caves as well as the surface. Do not carry the
        // surface climate underground, and never edit snow-bearing or manual slabs.
        if (proposed.hasProperty(BlockStateProperties.SNOWY) && proposed.getValue(BlockStateProperties.SNOWY)) return proposed;
        BlockState existing = level.getBlockState(pos);
        if (existing.getBlock() instanceof SlabBlock && !generated(existing)) return proposed;
        if (pos.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) - 1)
            return proposed;
        var surface = NaturalEcology.surfaceSample(level.getLevel().getChunkSource().getGenerator(), pos.getX(), pos.getZ());
        if (surface == null) return proposed;
        var climate = surface.climate();
        var material = NaturalRiverBankSurface.material(climate, surface.terrainBiomeId(), surface.elevation(),
                Float.POSITIVE_INFINITY, level.getSeed(), pos.getX(), pos.getZ(), WorldScaleManager.getCurrentScale());
        BlockPos below = pos.below();
        BlockState support = level.getBlockState(below);
        boolean supported = support.getFluidState().isEmpty() && support.isFaceSturdy(level, below, Direction.UP);
        String path = TerrainSlabSurfacePolicy.replacement(version, source, true, true, supported,
                climate.temperature(), climate.moisture(), material);
        if (path == null) return proposed;
        BlockState target = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("terrain_slabs", path))
                .defaultBlockState();
        if (!(target.getBlock() instanceof SlabBlock) || !supported && target.getBlock() instanceof Fallable) return proposed;
        for (Property<?> property : proposed.getProperties()) {
            if (target.hasProperty(property)) target = copyProperty(proposed, target, property);
            // Snowy is a grass-only rendering property; its false value has no
            // mineral equivalent. Every geometry/fluid/generated property must match.
            else if (property != BlockStateProperties.SNOWY) return proposed;
        }
        return generated(target) ? target : proposed;
    }

    private static boolean generated(BlockState state) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty("generated");
        return property != null && Boolean.TRUE.equals(state.getValues().get(property));
    }

    private static <T extends Comparable<T>> BlockState copyProperty(BlockState source, BlockState target, Property<T> property) {
        return target.setValue(property, source.getValue(property));
    }
}
