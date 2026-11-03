package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.geology.RiverChannelData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Carries recorded worldgen columns through the vanilla proto-to-full promotion. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkRiverChannelDataMixin {
    @Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V", at = @At("RETURN"))
    private void terrainDiffusion$copyRiverChannels(ServerLevel level, ProtoChunk proto,
                                                    LevelChunk.PostLoadProcessor postLoad, CallbackInfo ci) {
        RiverChannelData.copy(proto, (LevelChunk) (Object) this);
    }
}
