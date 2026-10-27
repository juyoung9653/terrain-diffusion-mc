package com.github.xandergos.terraindiffusionmc.mixin.hybridaquatic;

import com.github.xandergos.terraindiffusionmc.compat.hybridaquatic.HybridAquaticCompatibility;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Pseudo
@Mixin(targets = "dev.hybridlabs.aquatic.world.gen.densityfunction.TrenchCarver", remap = false)
public abstract class HybridAquaticTrenchCarverMixin {
    @ModifyVariable(method = "apply(Lnet/minecraft/server/MinecraftServer;)V",
            at = @At("STORE"), ordinal = 0, require = 1, allow = 1)
    private static NoiseRouter terrainDiffusion$continents(NoiseRouter router, MinecraftServer server) {
        return HybridAquaticCompatibility.adaptRouter(router, server);
    }
}
