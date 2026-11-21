package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsWetlandHandoff2173;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Protects an already modelled wet column from RiverCarverFeature's unplanned native-lake AIR clear. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.RiverCarverFeature", remap = false)
public abstract class StreamsWetlandHandoffLake2173Mixin {
    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;"
                    + "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
                    ordinal = 0), require = 1, expect = 1, remap = false)
    private boolean terrainDiffusion$preserveModelledWetLakeBed(WorldGenLevel level, BlockPos pos,
            BlockState proposed, int flags, Operation<Boolean> original) {
        if (proposed.is(Blocks.AIR) && StreamsWetlandHandoff2173.preserveWetlandLakeColumn(
                level.getLevel().getChunkSource().getGenerator(), pos.getX(), pos.getY(), pos.getZ())) return false;
        return original.call(level, pos, proposed, flags);
    }
}
