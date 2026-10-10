package com.github.xandergos.terraindiffusionmc.diagnostic.mixin;

import com.github.xandergos.terraindiffusionmc.diagnostic.IceWriteTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ProtoChunk.class)
public abstract class ProtoChunkIceTraceMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void tdProbe$traceIce(BlockPos pos, BlockState state, boolean moving,
                                  CallbackInfoReturnable<BlockState> callback) {
        IceWriteTrace.record((ProtoChunk) (Object) this, pos, state);
    }
}
