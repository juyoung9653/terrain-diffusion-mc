package com.terrain.instanceclientprobe.mixin;

import com.terrain.instanceclientprobe.InstanceClientProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ProtoChunk.class)
public abstract class ProtoChunkWriteTraceMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void tdInstanceProbe$trace(BlockPos pos, BlockState state, boolean moving,
                                       CallbackInfoReturnable<BlockState> callback) {
        InstanceClientProbe.recordWrite((ProtoChunk) (Object) this, "proto", pos, state);
    }
}
