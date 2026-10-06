package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.incendium.IncendiumOriginCompatibility;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class IncendiumOriginLifecycleMixin {
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void terrainDiffusion$completeIncendiumOrigin(BooleanSupplier hasTime, CallbackInfo ci) {
        IncendiumOriginCompatibility.tick((MinecraftServer) (Object) this);
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void terrainDiffusion$releaseIncendiumOrigin(CallbackInfo ci) {
        IncendiumOriginCompatibility.stop((MinecraftServer) (Object) this);
    }
}
