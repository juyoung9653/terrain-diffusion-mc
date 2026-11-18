package com.terrain.chunkprofile.mixin;

import com.terrain.chunkprofile.GenerationStageTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Emits actual post-write transitions for opted-in columns only. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkGenerationWriteTraceMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void traceLevelWrite(BlockPos pos, BlockState proposed, boolean moved,
                                 CallbackInfoReturnable<BlockState> cir) {
        GenerationStageTrace.captureWrite((LevelChunk) (Object) this, pos, cir.getReturnValue(), proposed, "level");
    }
}
