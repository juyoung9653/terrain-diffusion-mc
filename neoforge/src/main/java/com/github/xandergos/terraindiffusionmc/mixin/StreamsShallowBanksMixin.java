package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsShallowBanks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Audited Streams 2.13.1/2.13.5/2.13.8: ordinal six is the dry, non-log bank AIR
 * write in RiverCarverFeature.place (bytecode 6982, 7198 and 7843 respectively).
 * The adjacent ordinals are log removal, water placement, and lower
 * cleanup and deliberately remain native.
 */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.RiverCarverFeature", remap = false)
public final class StreamsShallowBanksMixin {
    @WrapOperation(
            method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 6),
            require = 1, remap = false)
    private boolean terrainDiffusion$retainContainingSoil(WorldGenLevel level, BlockPos pos, BlockState replacement,
                                                           int flags, Operation<Boolean> original) {
        return StreamsShallowBanks.preserve(level, pos, replacement) ? false
                : original.call(level, pos, replacement, flags);
    }
}
