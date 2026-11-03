package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.geology.RiverChannelData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Persists only successfully carved river columns alongside ordinary chunk NBT. */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerRiverChannelDataMixin {
    @Inject(method = "read", at = @At("RETURN"))
    private static void terrainDiffusion$readRiverChannels(ServerLevel level, PoiManager poiManager,
            RegionStorageInfo storageInfo, ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<ProtoChunk> cir) {
        RiverChannelData.read(cir.getReturnValue(), tag);
    }

    @Inject(method = "write", at = @At("RETURN"))
    private static void terrainDiffusion$writeRiverChannels(ServerLevel level, ChunkAccess chunk,
            CallbackInfoReturnable<CompoundTag> cir) {
        RiverChannelData.write(chunk, cir.getReturnValue());
    }
}
