package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.greeneries.GreeneriesPlacementGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Guards only Streams 2.17.3's direct grass and fern writers. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.SeeAiNoticeConstant_005", remap = false)
public final class StreamsLandGrass2173Mixin {
    @Inject(method = "proprietary1(Lnet/minecraft/world/level/WorldGenLevel;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, expect = 1, remap = false)
    private void terrainDiffusion$guardShortLandGrass(WorldGenLevel level, BlockPos pos,
            BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!GreeneriesPlacementGuard.acceptsNaturalGrass(level, pos, state)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "proprietary1(Lnet/minecraft/world/level/WorldGenLevel;"
            + "Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, expect = 1, remap = false)
    private void terrainDiffusion$guardTallLandGrass(WorldGenLevel level, BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        if (!GreeneriesPlacementGuard.acceptsNaturalGrass(level, pos,
                Blocks.TALL_GRASS.defaultBlockState())) {
            cir.setReturnValue(false);
        }
    }
}
