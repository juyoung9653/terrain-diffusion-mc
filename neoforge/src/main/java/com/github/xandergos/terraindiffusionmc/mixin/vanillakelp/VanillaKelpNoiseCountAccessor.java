package com.github.xandergos.terraindiffusionmc.mixin.vanillakelp;

import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NoiseBasedCountPlacement.class)
public interface VanillaKelpNoiseCountAccessor {
    @Accessor("noiseToCountRatio") int terrainDiffusion$noiseToCountRatio();
    @Accessor("noiseFactor") double terrainDiffusion$noiseFactor();
    @Accessor("noiseOffset") double terrainDiffusion$noiseOffset();
}
