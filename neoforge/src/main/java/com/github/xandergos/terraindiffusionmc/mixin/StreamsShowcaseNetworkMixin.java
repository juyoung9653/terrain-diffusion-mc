package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Showcase chunks have no Streams flow grid to generate or send to a player. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.neoforge.net.StreamFlowNet", remap = false)
public final class StreamsShowcaseNetworkMixin {
    @Inject(
            method = "onChunkSent(Lnet/neoforged/neoforge/event/level/ChunkWatchEvent$Sent;)V",
            at = @At("HEAD"), cancellable = true, require = 1, expect = 1, remap = false)
    private static void terrainDiffusion$skipShowcaseFlowPacket(ChunkWatchEvent.Sent event,
                                                              CallbackInfo callback) {
        if (event.getLevel().getChunkSource().getGenerator().getBiomeSource()
                instanceof TerrainDiffusionBiomeSource source && !source.ecologyShowcase().isEmpty()) {
            callback.cancel();
        }
    }
}
