package com.terrain.chunkreadprobe.mixin;
import com.terrain.chunkreadprobe.ChunkReadCounter;
import org.spongepowered.asm.mixin.Mixin; import org.spongepowered.asm.mixin.injection.*; import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(targets="com.ishland.c2me.rewrites.chunksystem.common.TheChunkSystem", remap=false)
abstract class TheChunkSystemMixin {
 @Inject(method="vanillaIf$setLevel(JI)Lnet/minecraft/server/level/ChunkHolder;",at=@At("HEAD"),require=1)
 private void td$level(long pos,int level,CallbackInfoReturnable<?> cir){ChunkReadCounter.schedulingObserved(this,pos,level,true);}
}
