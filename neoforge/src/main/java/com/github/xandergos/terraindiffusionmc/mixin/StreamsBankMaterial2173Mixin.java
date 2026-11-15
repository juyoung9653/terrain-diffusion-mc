package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsBankMaterialPolicy;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsBankTop2173Policy;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Exact Streams 2.17.3 exposed-cap repair; native bank geometry otherwise remains native. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.RiverCarverFeature", remap = false)
public final class StreamsBankMaterial2173Mixin {
    private static final String TOP_HELPER = "Ldev/streamsreflowing/worldgen/RiverCarverFeature;"
            + "proprietary1(Lnet/minecraft/world/level/WorldGenLevel;"
            + "Lnet/minecraft/core/BlockPos$MutableBlockPos;III"
            + "Lnet/minecraft/world/level/block/state/BlockState;I)V";

    /** The audited helper is the sole exposed-cap writer and has twelve 2.17.3 call sites. */
    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = TOP_HELPER),
            require = 12, expect = 12, remap = false)
    private static void terrainDiffusion$climateOrRejectRaisedCap(WorldGenLevel level,
            BlockPos.MutableBlockPos mutable, int x, int y, int z, BlockState proposed, int minY,
            Operation<Void> original) {
        if (StreamsBankTop2173Policy.suppressUnnecessaryRaisedCap(level, x, y, z, proposed)) return;
        BlockState material = WorldNaturalRiverState.version(level.getLevel()) >= 10
                ? StreamsBankMaterialPolicy.climateBankTop(level, x, y, z, proposed) : proposed;
        original.call(level, mutable, x, y, z, material, minY);
    }

    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;"
                    + "setBlock(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 12),
            require = 1, expect = 1, remap = false)
    private boolean terrainDiffusion$climateOrRejectRecap(WorldGenLevel level, BlockPos pos,
            BlockState proposed, int flags, Operation<Boolean> original) {
        if (StreamsBankTop2173Policy.suppressUnnecessaryRaisedCap(level, pos.getX(), pos.getY(),
                pos.getZ(), proposed)) return false;
        BlockState material = WorldNaturalRiverState.version(level.getLevel()) >= 10
                ? StreamsBankMaterialPolicy.climateBankTop(level, pos.getX(), pos.getY(), pos.getZ(), proposed)
                : proposed;
        return original.call(level, pos, material, flags);
    }

    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = {
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;"
                            + "setBlock(Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 13),
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;"
                            + "setBlock(Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 14),
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;"
                            + "setBlock(Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 15)
            }, require = 3, expect = 3, remap = false)
    private boolean terrainDiffusion$keepSupportedVegetation(WorldGenLevel level, BlockPos pos,
            BlockState proposed, int flags, Operation<Boolean> original) {
        if (WorldNaturalRiverState.version(level.getLevel()) < 10) {
            return original.call(level, pos, proposed, flags);
        }
        boolean upper = proposed.is(Blocks.TALL_GRASS)
                && proposed.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
        BlockState support = level.getBlockState(pos.below());
        if (upper ? !support.is(Blocks.TALL_GRASS) : !support.is(Blocks.GRASS_BLOCK)) return false;
        return original.call(level, pos, proposed, flags);
    }
}
