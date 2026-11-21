package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsWetlandHandoff2173;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Associates Streams' cached native engine with this world's modelled wetland field. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.StreamsWorldgen", remap = false)
public abstract class StreamsWetlandHandoffEngine2173Mixin {
    @Inject(method = "engine(Lnet/minecraft/world/level/WorldGenLevel;)"
            + "Ldev/streamsreflowing/core/RiverEngine;", at = @At("RETURN"), cancellable = true,
            require = 1, remap = false)
    private static void terrainDiffusion$bindWetlandField(WorldGenLevel level,
            CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(StreamsWetlandHandoff2173.registerEngine(cir.getReturnValue(),
                level.getLevel().getChunkSource().getGenerator()));
    }
}
