package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsBankMaterialPolicy;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Streams 2.13.8's bank helper prepends RiverEngine and two integer arguments.
 * Its x and scan-top inputs therefore occupy argument local slots 5 and 7.
 */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.RiverCarverFeature", remap = false)
public abstract class StreamsBankMaterial2138Mixin {
    /** 2.13.8 place offset 11890: exposed soil recap, outside the BANKTOP helper. */
    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 11),
            require = 1, remap = false)
    private boolean terrainDiffusion$climateRecap(WorldGenLevel level, BlockPos pos, BlockState proposed,
                                                 int flags, Operation<Boolean> original) {
        BlockState material = WorldNaturalRiverState.version(level.getLevel()) >= 10
                ? StreamsBankMaterialPolicy.climateBankTop(level, pos.getX(), pos.getY(), pos.getZ(), proposed)
                : proposed;
        return original.call(level, pos, material, flags);
    }

    /** Immediate recap decoration, offsets 11972/11988/12012; consume native RNG unchanged. */
    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = {
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 12),
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 13),
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 14)
            }, require = 3, remap = false)
    private boolean terrainDiffusion$recapVegetation(WorldGenLevel level, BlockPos pos, BlockState proposed,
                                                     int flags, Operation<Boolean> original) {
        if (WorldNaturalRiverState.version(level.getLevel()) >= 10) {
            boolean upper = proposed.is(Blocks.TALL_GRASS)
                    && proposed.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
            // A rejected lower half must never leave an orphan upper half. Mineral
            // or exposed soil caps also must not acquire the native generic grass.
            BlockState support = level.getBlockState(pos.below());
            if (upper ? !support.is(Blocks.TALL_GRASS) : !support.is(Blocks.GRASS_BLOCK)) return false;
        }
        return original.call(level, pos, proposed, flags);
    }

    @WrapOperation(
            method = "a(Lnet/minecraft/world/level/WorldGenLevel;Ldev/streamsreflowing/core/RiverEngine;IILnet/minecraft/core/BlockPos$MutableBlockPos;IIIIZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;IZLjava/util/List;Ljava/util/List;Ljava/util/Map;Ljava/util/Map;IILjava/lang/String;ZZ)V",
            at = @At(value = "INVOKE", target = "Ldev/streamsreflowing/worldgen/bank/BankStyle;zoneBlock(IDZ)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 1,
            remap = false)
    private static BlockState terrainDiffusion$preserveSelectedNaturalShoreMaterial(
            @Coerce Object style, int zone, double noise, boolean pointBar, Operation<BlockState> original,
            @Local(argsOnly = true) WorldGenLevel level,
            @Local(argsOnly = true) BlockPos.MutableBlockPos surface,
            @Local(argsOnly = true, index = 5) int blockX,
            @Local(argsOnly = true, index = 7) int scanTop) {
        BlockState proposed = original.call(style, zone, noise, pointBar);
        if (WorldNaturalRiverState.version(level.getLevel()) >= 7) {
            return StreamsBankMaterialPolicy.preserveV7NaturalShoreSubstrate(level, surface, zone, pointBar, proposed);
        }
        return StreamsBankMaterialPolicy.preserveNaturalShoreSubstrate(level, blockX, scanTop, zone, pointBar, proposed);
    }

    /**
     * 2.13.8 bank helper bytecode offset 1216 is the sole BANKTOP helper invocation.
     * Its later setBlock loop fills depth from the untouched zoneBlock local instead.
     */
    @WrapOperation(
            method = "a(Lnet/minecraft/world/level/WorldGenLevel;Ldev/streamsreflowing/core/RiverEngine;IILnet/minecraft/core/BlockPos$MutableBlockPos;IIIIZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;IZLjava/util/List;Ljava/util/List;Ljava/util/Map;Ljava/util/Map;IILjava/lang/String;ZZ)V",
            at = @At(value = "INVOKE", target = "Ldev/streamsreflowing/worldgen/RiverCarverFeature;a(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/core/BlockPos$MutableBlockPos;IIILnet/minecraft/world/level/block/state/BlockState;I)V"),
            require = 1,
            remap = false)
    private static void terrainDiffusion$climateBankTop(WorldGenLevel level, BlockPos.MutableBlockPos mutable,
                                                       int x, int y, int z, BlockState proposed, int minY,
                                                       Operation<Void> original) {
        BlockState material = WorldNaturalRiverState.version(level.getLevel()) >= 7
                ? StreamsBankMaterialPolicy.climateBankTop(level, x, y, z, proposed) : proposed;
        original.call(level, mutable, x, y, z, material, minY);
    }

}
