package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsShortRiverTunnels;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Covers direct and helper native carver writes, but only inside the scoped V12 feature call. */
@Mixin(WorldGenRegion.class)
public final class ShortRiverTunnelWorldGenRegionMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$retainShortTunnelRoof(BlockPos pos, BlockState replacement, int flags, int recursionLeft,
                                                        CallbackInfoReturnable<Boolean> result) {
        WorldGenRegion region = (WorldGenRegion) (Object) this;
        if (StreamsShortRiverTunnels.retain(region, pos)) result.setReturnValue(false);
    }
}
