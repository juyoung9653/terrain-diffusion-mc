package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsScalePolicy;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Applies TD scale-aware settings while Streams creates a new per-world RiverEngine. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.StreamsWorldgen", remap = false)
public final class StreamsWorldgenScaleMixin {
    @ModifyArgs(
            method = "engine(Lnet/minecraft/world/level/WorldGenLevel;)Ldev/streamsreflowing/core/RiverEngine;",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/streamsreflowing/core/config/StreamsConfig;defaults(IDIII)Ldev/streamsreflowing/core/config/StreamsConfig;",
                    remap = false),
            require = 1,
            expect = 1,
            remap = false)
    private static void terrainDiffusion$scaleDefaults(Args arguments, WorldGenLevel level) {
        if (!StreamsScalePolicy.appliesTo(level)) {
            return;
        }
        int scale = StreamsScalePolicy.activeScale(level);
        if (scale <= StreamsScalePolicy.DEFAULT_SCALE) {
            return;
        }
        double widthScale = arguments.get(1);
        int plateScale = arguments.get(2);
        arguments.set(1, StreamsScalePolicy.widthScale(widthScale, scale));
        arguments.set(2, StreamsScalePolicy.plateScale(plateScale, scale));
        org.slf4j.LoggerFactory.getLogger("TerrainDiffusion/StreamsScale").info(
                "TD scale {}: Streams plate extent {} -> {} blocks, width factor {} -> {}",
                scale, plateScale, StreamsScalePolicy.plateScale(plateScale, scale),
                widthScale, StreamsScalePolicy.widthScale(widthScale, scale));
    }

    @ModifyArgs(
            method = "engine(Lnet/minecraft/world/level/WorldGenLevel;)Ldev/streamsreflowing/core/RiverEngine;",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/streamsreflowing/core/config/StreamsConfig;withAppearance(DDDD)Ldev/streamsreflowing/core/config/StreamsConfig;",
                    remap = false),
            require = 1,
            expect = 1,
            remap = false)
    private static void terrainDiffusion$scaleAppearance(Args arguments, WorldGenLevel level) {
        if (!StreamsScalePolicy.appliesTo(level)) {
            return;
        }
        int scale = StreamsScalePolicy.activeScale(level);
        if (scale <= StreamsScalePolicy.DEFAULT_SCALE) {
            return;
        }
        double maxWidth = arguments.get(2);
        arguments.set(2, StreamsScalePolicy.maxWidth(maxWidth, scale));
    }

}
