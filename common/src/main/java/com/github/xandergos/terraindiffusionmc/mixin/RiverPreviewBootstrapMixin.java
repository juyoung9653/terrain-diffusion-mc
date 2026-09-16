package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.river.WorldRiverPreviewState;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Initializes river-preview state after levels exist and before prepareLevels generates spawn chunks. */
@Mixin(MinecraftServer.class)
public class RiverPreviewBootstrapMixin {
    @Unique
    private boolean terrainDiffusion$riverPreviewNewWorld;

    @Inject(method = "createLevels", at = @At("HEAD"))
    private void terrainDiffusion$captureRiverPreviewNewWorld(ChunkProgressListener progressListener, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        terrainDiffusion$riverPreviewNewWorld = !server.getWorldData().overworldData().isInitialized();
    }

    @Inject(method = "createLevels", at = @At("RETURN"))
    private void terrainDiffusion$initializeRiverPreview(ChunkProgressListener progressListener, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        WorldRiverPreviewState.initialize(server.overworld(), terrainDiffusion$riverPreviewNewWorld);
        WorldNaturalRiverState.initialize(server.overworld(), terrainDiffusion$riverPreviewNewWorld);
    }

    @Inject(method = "stopServer", at = @At("TAIL"))
    private void terrainDiffusion$clearRiverPreview(CallbackInfo ci) {
        WorldRiverPreviewState.clear();
        WorldNaturalRiverState.clear();
    }
}
