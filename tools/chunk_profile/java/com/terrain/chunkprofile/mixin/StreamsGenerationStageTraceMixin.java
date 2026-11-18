package com.terrain.chunkprofile.mixin;

import com.terrain.chunkprofile.GenerationStageTrace;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Separates native Streams writes from TD's post-return bank repair. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.RiverCarverFeature", remap = false, priority = 2000)
public final class StreamsGenerationStageTraceMixin {
    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At("HEAD"), require = 1, remap = false)
    private void traceNativeBegin(FeaturePlaceContext<NoneFeatureConfiguration> context,
                                  CallbackInfoReturnable<Boolean> cir) {
        GenerationStageTrace.capture("streams-place-begin", context.level(),
                context.origin().getX() & ~15, context.origin().getZ() & ~15);
    }

    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At("RETURN"), require = 1, remap = false)
    private void traceNativeComplete(FeaturePlaceContext<NoneFeatureConfiguration> context,
                                     CallbackInfoReturnable<Boolean> cir) {
        GenerationStageTrace.capture("streams-native-complete", context.level(),
                context.origin().getX() & ~15, context.origin().getZ() & ~15);
    }
}
