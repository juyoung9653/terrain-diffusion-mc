package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalRiverAdapter;
import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsStructureShieldMixinPlugin;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.neoforged.fml.loading.FMLLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public final class StreamsNaturalLifecycleMixin {
    @Inject(method = "prepareLevels", at = @At("HEAD"))
    private void terrainDiffusion$checkNaturalAdapter(ChunkProgressListener listener, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        if (!WorldNaturalRiverState.enabled(server.overworld())) return;
        FMLLoader.getLoadingModList().getMods().stream()
                .filter(mod -> "streamsreflowing".equals(mod.getModId()))
                .forEach(mod -> {
                    if (!StreamsStructureShieldMixinPlugin.isAuditedVersion(mod.getVersion().toString())) {
                        throw new IllegalStateException("This world's TD natural river profile requires an audited Streams adapter; found "
                                + mod.getVersion() + ". Validate the adapter before generating new terrain.");
                    }
                });
    }

    @Inject(method = "stopServer", at = @At("TAIL"))
    private void terrainDiffusion$clearNaturalAdapter(CallbackInfo ci) {
        if (!TerrainDiffusionLifecycle.isPreviewServer((MinecraftServer) (Object) this)) {
            StreamsNaturalRiverAdapter.clear();
        }
    }
}
