package com.terrain.chunkreadprobe.mixin;

import com.terrain.chunkreadprobe.ChunkReadCounter;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Separately identifies no-tick player sources before their asynchronous square expansion. */
@Mixin(targets = "com.ishland.c2me.notickvd.common.NoTickSystem", remap = false)
abstract class NoTickSystemMixin {
    @Inject(method = "addPlayerSource(Lnet/minecraft/world/level/ChunkPos;)V", at = @At("HEAD"), require = 1)
    private void td$add(ChunkPos pos, CallbackInfo ci) { ChunkReadCounter.noTickSourceObserved(this, pos, true); }
    @Inject(method = "removePlayerSource(Lnet/minecraft/world/level/ChunkPos;)V", at = @At("HEAD"), require = 1)
    private void td$remove(ChunkPos pos, CallbackInfo ci) { ChunkReadCounter.noTickSourceObserved(this, pos, false); }
}
