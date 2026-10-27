package com.github.xandergos.terraindiffusionmc.compat.terrainslabs;

import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;

/** Requires an actual terrain step before the native feature starts a slab contour. */
public final class TerrainSlabContour {
    private TerrainSlabContour() { }

    public static boolean allowsBottom(WorldGenLevel level, BlockPos pos, boolean neighbourCanBeSlab) {
        if (WorldNaturalRiverState.version(level.getLevel()) < 10) return true;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos wallPos = pos.relative(direction);
            BlockState wall = level.getBlockState(wallPos);
            // Native 3.1.2 accepts any collision here, including turtle eggs and
            // small decorations on otherwise flat sand. Those are not a rise.
            boolean terrainStep = wall.isCollisionShapeFullBlock(level, wallPos) && mapped(wall);
            // Preserve the mod's explicit corner/extended passes, whose seeds
            // have already passed the full terrain-step check.
            boolean continuation = neighbourCanBeSlab && generatedSlab(wall);
            if (!terrainStep && !continuation) continue;
            BlockPos oppositeSupport = pos.relative(direction.getOpposite()).below();
            if (!level.getBlockState(oppositeSupport).isCollisionShapeFullBlock(level, oppositeSupport))
                continue;
            BlockPos aboveWall = wallPos.above();
            BlockState above = level.getBlockState(aboveWall);
            boolean lowStep = above.getCollisionShape(level, aboveWall).isEmpty();
            boolean tallOceanStep = terrainStep && above.isCollisionShapeFullBlock(level, aboveWall)
                    && mapped(above) && oceanFloor(level, pos);
            // The native candidate has already proved a full mapped wall and
            // opposite support. At the top source-water cell of an ocean shore,
            // requiring a second tangent wall leaves short connecting slopes as
            // full cubes. Do not extend this to lakes, flowing/deep water, dry
            // bumps, or tall submerged cliffs.
            boolean coastalWaterline = lowStep && oceanWaterline(level, pos);
            if ((lowStep || tallOceanStep)
                    && (continuation || connectedContour(level, pos, direction) || coastalWaterline)) return true;
        }
        return false;
    }

    /** A one-cell bump or pit is not a contour; require a matching step alongside it. */
    private static boolean connectedContour(WorldGenLevel level, BlockPos pos, Direction wallDirection) {
        for (Direction tangent : Direction.Plane.HORIZONTAL) {
            if (tangent.getAxis() == wallDirection.getAxis()) continue;
            BlockPos next = pos.relative(tangent);
            BlockState nextState = level.getBlockState(next);
            if (!nextState.getCollisionShape(level, next).isEmpty() && !generatedSlab(nextState)) continue;
            if (!level.getBlockState(next.above()).getCollisionShape(level, next.above()).isEmpty()) continue;
            if (!level.getBlockState(next.below()).isCollisionShapeFullBlock(level, next.below())) continue;
            BlockPos support = next.relative(wallDirection.getOpposite()).below();
            if (!level.getBlockState(support).isCollisionShapeFullBlock(level, support)) continue;
            BlockPos wall = next.relative(wallDirection);
            BlockState state = level.getBlockState(wall);
            if (!state.isCollisionShapeFullBlock(level, wall) || !mapped(state)) continue;
            BlockState above = level.getBlockState(wall.above());
            if (above.getCollisionShape(level, wall.above()).isEmpty()
                    || above.isCollisionShapeFullBlock(level, wall.above()) && mapped(above)
                    && oceanFloor(level, next)) return true;
        }
        return false;
    }

    /** Extends only the native surrounding test; its support/open-space tests still run. */
    public static boolean allowsOceanRiser(WorldGenLevel level, BlockPos pos) {
        if (!oceanFloor(level, pos)) return false;
        // Retain the native surrounding test's unconditional lava veto.
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(direction)).is(Blocks.LAVA)) return false;
        }
        return allowsBottom(level, pos, false);
    }

    private static boolean oceanFloor(WorldGenLevel level, BlockPos pos) {
        return WorldNaturalRiverState.version(level.getLevel()) >= 10
                && level.getFluidState(pos).is(FluidTags.WATER)
                && level.getFluidState(pos).isSource()
                && level.getFluidState(pos.above()).is(FluidTags.WATER)
                && level.getFluidState(pos.above()).isSource()
                && level.getBiome(pos).is(BiomeTags.IS_OCEAN)
                // A submerged cave below an ocean is not an exposed seabed contour.
                && pos.getY() >= level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, pos.getX(), pos.getZ());
    }

    private static boolean oceanWaterline(WorldGenLevel level, BlockPos pos) {
        return WorldNaturalRiverState.version(level.getLevel()) >= 10
                && level.getFluidState(pos).is(FluidTags.WATER)
                && level.getFluidState(pos).isSource()
                && level.getBiome(pos).is(BiomeTags.IS_OCEAN)
                && pos.getY() == level.getHeight(Heightmap.Types.WORLD_SURFACE_WG,
                        pos.getX(), pos.getZ()) - 1;
    }

    private static boolean generatedSlab(BlockState state) {
        if (!(state.getBlock() instanceof SlabBlock)) return false;
        Property<?> property = state.getBlock().getStateDefinition().getProperty("generated");
        return property != null && Boolean.TRUE.equals(state.getValues().get(property));
    }

    private static boolean mapped(BlockState state) {
        try {
            return Access.SLAB_FOR_BLOCK.invoke(null, state.getBlock()) != null;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Terrain Slabs contour adapter", failure);
        }
    }

    private static final class Access {
        static final Method SLAB_FOR_BLOCK;
        static {
            try {
                SLAB_FOR_BLOCK = Class.forName("net.countered.terrainslabs.block.ModSlabsMap")
                        .getMethod("getSlabForBlock", Block.class);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
