package com.github.xandergos.terraindiffusionmc.mixin.hybridaquatic;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HeightmapPlacement.class)
public interface HybridAquaticKelpHeightmapAccessor {
    @Accessor("heightmap") Heightmap.Types terrainDiffusion$heightmap();
}
