package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsScalePolicy;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Applies TD scale-aware settings to the Streams Reflowing 2.16.x config factory. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.StreamsWorldgen", remap = false)
public final class StreamsWorldgenScale2162Mixin {
    @ModifyArgs(
            method = "engine(Lnet/minecraft/world/level/WorldGenLevel;)Ldev/streamsreflowing/core/RiverEngine;",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/streamsreflowing/core/config/StreamsConfig;proprietary1(IDIIIIIZ)Ldev/streamsreflowing/core/config/StreamsConfig;",
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
        double adjustedWidthScale = StreamsScalePolicy.widthScale(widthScale, scale);
        int adjustedPlateScale = StreamsScalePolicy.plateScale(plateScale, scale);
        arguments.set(1, adjustedWidthScale);
        arguments.set(2, adjustedPlateScale);
        org.slf4j.LoggerFactory.getLogger("TerrainDiffusion/StreamsScale").info(
                "TD scale {}: Streams plate extent {} -> {} blocks, width factor {} -> {}",
                scale, plateScale, adjustedPlateScale, widthScale, adjustedWidthScale);
    }

    @ModifyArgs(
            method = "engine(Lnet/minecraft/world/level/WorldGenLevel;)Ldev/streamsreflowing/core/RiverEngine;",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/streamsreflowing/core/config/StreamsConfig;proprietary1(DDDD)Ldev/streamsreflowing/core/config/StreamsConfig;",
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
