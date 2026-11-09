package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsShortRiverTunnels;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Binds all native RiverCarverFeature writes to one exact, exception-safe tunnel scope. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.RiverCarverFeature", remap = false)
public final class StreamsShortRiverTunnel2162Mixin {
    @WrapMethod(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z")
    private boolean terrainDiffusion$shortMountainTunnel(
            FeaturePlaceContext<NoneFeatureConfiguration> context, Operation<Boolean> original) {
        return StreamsShortRiverTunnels.place(context.level(), () -> original.call(context));
    }
}
