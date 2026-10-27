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
 * Streams 2.15.2 selects a BankRing before painting the top and native depth fill.
 * Ring index zero remains the bed; the final boolean argument (slot 22) is the
 * native point-bar decision, also passed into BankRing.Metrics.
 */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.RiverCarverFeature", remap = false)
public abstract class StreamsBankMaterial2152Mixin {
    /** 2.15.2 place offset 13011: exposed soil recap, outside the BANKTOP helper. */
    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 12),
            require = 1, remap = false)
    private boolean terrainDiffusion$climateRecap(WorldGenLevel level, BlockPos pos, BlockState proposed,
                                                 int flags, Operation<Boolean> original) {
        BlockState material = WorldNaturalRiverState.version(level.getLevel()) >= 10
                ? StreamsBankMaterialPolicy.climateBankTop(level, pos.getX(), pos.getY(), pos.getZ(), proposed)
                : proposed;
        return original.call(level, pos, material, flags);
    }

    /** Immediate recap decoration, offsets 13093/13109/13133; consume native RNG unchanged. */
    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = {
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 13),
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 14),
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 15)
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
            method = "a(Lnet/minecraft/world/level/WorldGenLevel;Ldev/streamsreflowing/core/RiverEngine;IILnet/minecraft/core/BlockPos$MutableBlockPos;IIIIZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;IZLjava/util/List;Ljava/util/List;Ljava/util/Map;Ldev/streamsreflowing/worldgen/QuartBiomeMemo;IILjava/lang/String;ZZLdev/streamsreflowing/core/compose/ColumnPlan;)V",
            at = @At(value = "INVOKE", target = "Ldev/streamsreflowing/worldgen/bank/BankRing;pick(JIIIDDLdev/streamsreflowing/worldgen/bank/BankRing$Metrics;I)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 1,
            remap = false)
    private static BlockState terrainDiffusion$preserveSelectedNaturalShoreMaterial(
            @Coerce Object ring, long seed, int x, int y, int z, double width, double distance,
            @Coerce Object metrics, int ringIndex, Operation<BlockState> original,
            @Local(argsOnly = true) WorldGenLevel level,
            @Local(argsOnly = true, index = 22) boolean pointBar) {
        // Offset 723 stores the selected state in local 48. Returning null reaches
        // the native guard at 1179 and skips both top painting and the depth loop.
        BlockState proposed = original.call(ring, seed, x, y, z, width, distance, metrics, ringIndex);
        if (WorldNaturalRiverState.version(level.getLevel()) >= 7) {
            return StreamsBankMaterialPolicy.preserveV7NaturalShoreSubstrate(
                    level, new BlockPos.MutableBlockPos(x, y, z), ringIndex, pointBar, proposed);
        }
        return proposed == null ? null : StreamsBankMaterialPolicy.preserveNaturalShoreSubstrate(
                level, x, z, ringIndex, pointBar, proposed);
    }

    /**
     * 2.15.2 bank helper bytecode offset 1213 is the sole BANKTOP helper invocation.
     * Its later setBlock loop (1329) fills depth from local 56 or the untouched
     * ring material in local 48, never from this top-only replacement.
     */
    @WrapOperation(
            method = "a(Lnet/minecraft/world/level/WorldGenLevel;Ldev/streamsreflowing/core/RiverEngine;IILnet/minecraft/core/BlockPos$MutableBlockPos;IIIIZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;IZLjava/util/List;Ljava/util/List;Ljava/util/Map;Ldev/streamsreflowing/worldgen/QuartBiomeMemo;IILjava/lang/String;ZZLdev/streamsreflowing/core/compose/ColumnPlan;)V",
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
