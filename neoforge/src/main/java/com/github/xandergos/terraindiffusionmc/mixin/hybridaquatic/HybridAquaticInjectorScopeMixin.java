package com.github.xandergos.terraindiffusionmc.mixin.hybridaquatic;

import com.github.xandergos.terraindiffusionmc.compat.hybridaquatic.HybridAquaticCompatibility;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource", remap = false)
public abstract class HybridAquaticInjectorScopeMixin {
    @WrapMethod(method = "getNoiseBiome")
    private Holder<Biome> terrainDiffusion$scope(int x, int y, int z, Climate.Sampler sampler,
                                                Operation<Holder<Biome>> original) {
        var previous = HybridAquaticCompatibility.enter((BiomeSource) (Object) this, x, z);
        try {
            return original.call(x, y, z, sampler);
        } finally {
            HybridAquaticCompatibility.restore(previous);
        }
    }
}
