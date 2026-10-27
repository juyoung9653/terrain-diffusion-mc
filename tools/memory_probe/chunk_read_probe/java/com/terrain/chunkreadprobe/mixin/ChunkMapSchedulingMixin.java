package com.terrain.chunkreadprobe.mixin;
import com.terrain.chunkreadprobe.ChunkReadCounter;
import org.spongepowered.asm.mixin.Mixin; import org.spongepowered.asm.mixin.injection.*; import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(targets = "net.minecraft.server.level.ChunkMap", remap = false)
abstract class ChunkMapSchedulingMixin {
 @Inject(method="updateChunkScheduling(JILnet/minecraft/server/level/ChunkHolder;I)Lnet/minecraft/server/level/ChunkHolder;", at=@At("HEAD"), require=1)
 private void td$schedule(long pos,int level,@Coerce Object holder,int oldLevel,CallbackInfoReturnable<?> cir){ChunkReadCounter.schedulingObserved(this,pos,level,false);}
}
