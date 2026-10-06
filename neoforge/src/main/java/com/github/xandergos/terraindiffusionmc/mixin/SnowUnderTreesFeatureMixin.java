package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Snow Under Trees runs after freeze_top_layer, which can hide leaves beneath snow. */
@Pseudo
@Mixin(targets = "bl4ckscor3.mod.snowundertrees.SnowUnderTreesFeature", remap = false)
public final class SnowUnderTreesFeatureMixin {
    private static final int MAX_CANOPY_DEPTH = 64;

    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Lnet/minecraft/world/level/WorldGenLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 1, remap = false)
    private BlockState terrainDiffusion$recognizeSnowCappedLeaves(WorldGenLevel level, BlockPos pos,
                                                                   Operation<BlockState> original) {
        BlockState top = original.call(level, pos);
        if (!isNaturalTerrain(level) || !top.is(Blocks.SNOW)) return top;
        BlockState leaves = level.getBlockState(pos.below());
        return leaves.is(BlockTags.LEAVES) ? leaves : top;
    }

    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At(value = "INVOKE", ordinal = 1,
                    target = "Lnet/minecraft/world/level/WorldGenLevel;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"),
            require = 1, remap = false)
    private int terrainDiffusion$findFloorBelowSnowCap(WorldGenLevel level, Heightmap.Types type,
                                                       int x, int z, Operation<Integer> original) {
        int height = original.call(level, type, x, z);
        if (!isNaturalTerrain(level)) return height;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x,
                level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
        if (!level.getBlockState(cursor).is(Blocks.SNOW)
                || !level.getBlockState(cursor.below()).is(BlockTags.LEAVES)) return height;

        int minimum = Math.max(level.getMinBuildHeight(), cursor.getY() - MAX_CANOPY_DEPTH);
        for (int y = cursor.getY() - 1; y >= minimum; y--) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            if (type.isOpaque().test(state)) return y + 1;
        }
        return height;
    }

    private static boolean isNaturalTerrain(WorldGenLevel level) {
        return level.getLevel().getChunkSource().getGenerator().getBiomeSource()
                instanceof TerrainDiffusionBiomeSource source && source.naturalBiomes();
    }
}
