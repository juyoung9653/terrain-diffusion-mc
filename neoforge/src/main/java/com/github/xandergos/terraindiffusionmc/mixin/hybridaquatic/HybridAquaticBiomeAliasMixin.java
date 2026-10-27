package com.github.xandergos.terraindiffusionmc.mixin.hybridaquatic;

import com.github.xandergos.terraindiffusionmc.compat.hybridaquatic.HybridAquaticCompatibility;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Pseudo
@Mixin(targets = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.ReplacePartially", remap = false)
public abstract class HybridAquaticBiomeAliasMixin {
    @Shadow public abstract Holder<Biome> replacement();

    @ModifyVariable(method = "matches", at = @At("HEAD"), argsOnly = true, index = 4, require = 1, allow = 1)
    private Holder<Biome> terrainDiffusion$alias(Holder<Biome> biome) {
        boolean hybrid = replacement().unwrapKey()
                .map(key -> key.location().getNamespace().equals("hybrid_aquatic")).orElse(false);
        return hybrid ? HybridAquaticCompatibility.alias(biome) : biome;
    }
}
