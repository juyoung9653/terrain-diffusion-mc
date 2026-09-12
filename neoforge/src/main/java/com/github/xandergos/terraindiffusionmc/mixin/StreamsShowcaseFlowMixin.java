package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes Streams' server-side fallback report no flow in an ecology showcase. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.flow.StreamFlowGrid", remap = false)
public final class StreamsShowcaseFlowMixin {
    @Inject(
            method = "columnFallback(Lnet/minecraft/world/level/BlockGetter;II)B",
            at = @At("HEAD"), cancellable = true, require = 1, expect = 1, remap = false)
    private static void terrainDiffusion$skipShowcaseFlowFallback(
            BlockGetter level, int blockX, int blockZ, CallbackInfoReturnable<Byte> callback) {
        if (level instanceof ServerLevel serverLevel && isEcologyShowcase(serverLevel)) {
            // Native non-server and unavailable-flow branches use -1 for no flow.
            callback.setReturnValue((byte) -1);
        }
    }

    private static boolean isEcologyShowcase(ServerLevel level) {
        return level.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource source
                && !source.ecologyShowcase().isEmpty();
    }
}
