package com.github.xandergos.terraindiffusionmc.diagnostic.mixin;

import com.github.xandergos.terraindiffusionmc.diagnostic.IceWriteTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkIceTraceMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void tdProbe$traceIce(BlockPos pos, BlockState state, boolean moving,
                                  CallbackInfoReturnable<BlockState> callback) {
        IceWriteTrace.record((LevelChunk) (Object) this, pos, state);
    }
}
