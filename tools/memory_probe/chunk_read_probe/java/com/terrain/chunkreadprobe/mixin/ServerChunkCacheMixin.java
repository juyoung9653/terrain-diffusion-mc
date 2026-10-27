package com.terrain.chunkreadprobe.mixin;

import com.terrain.chunkreadprobe.ChunkReadCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Original request boundary; records caller before tickets and C2ME async scheduling. */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache", remap = false)
abstract class ServerChunkCacheMixin {
    @Inject(method = "getChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), require = 1)
    private void td$request(int x, int z, @Coerce Object status, boolean create, CallbackInfoReturnable<?> cir) {
        ChunkReadCounter.chunkRequestObserved(this, x, z, status, create);
    }
}
