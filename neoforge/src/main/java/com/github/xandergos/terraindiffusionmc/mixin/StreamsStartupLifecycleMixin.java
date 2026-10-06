package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsStartupPolicy;
import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Also validates marked worlds on an unsupported Streams version; unmarked worlds are safe without Streams. */
@Mixin(MinecraftServer.class)
public final class StreamsStartupLifecycleMixin {
    @Unique private boolean terrainDiffusion$streamsNewWorld;

    @Inject(method = "createLevels", at = @At("HEAD"))
    private void terrainDiffusion$captureStreamsNewWorld(ChunkProgressListener listener, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        terrainDiffusion$streamsNewWorld = !server.getWorldData().overworldData().isInitialized();
    }

    @Inject(method = "createLevels", at = @At("RETURN"))
    private void terrainDiffusion$initializeStreamsSampler(ChunkProgressListener listener, CallbackInfo ci) {
        StreamsStartupPolicy.initialize(((MinecraftServer) (Object) this).overworld(), terrainDiffusion$streamsNewWorld);
    }

    @Inject(method = "stopServer", at = @At("TAIL"))
    private void terrainDiffusion$clearStreamsSampler(CallbackInfo ci) {
        if (!TerrainDiffusionLifecycle.isPreviewServer((MinecraftServer) (Object) this)) {
            StreamsStartupPolicy.clear();
        }
    }
}
