package com.github.xandergos.terraindiffusionmc.mixin.hybridaquatic;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = TerrainDiffusionBiomeSource.class, remap = false)
public interface TerrainDiffusionBiomeSourceAccessor {
    @Accessor("biomeLookup") HolderGetter<Biome> terrainDiffusion$biomeLookup();
}
