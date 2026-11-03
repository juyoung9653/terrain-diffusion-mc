package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsInferenceBinding;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalRiverAdapter;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsScalePolicy;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Binds natural TD river profiles to the Streams Reflowing 2.16.x engine. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.StreamsWorldgen", remap = false)
public final class StreamsNaturalRiver2162Mixin {
    @ModifyArgs(
            method = "engine(Lnet/minecraft/world/level/WorldGenLevel;)Ldev/streamsreflowing/core/RiverEngine;",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/streamsreflowing/core/RiverEngine;<init>(JLdev/streamsreflowing/core/config/StreamsConfig;Ldev/streamsreflowing/core/terrain/TerrainSampler;Ldev/streamsreflowing/core/terrain/SourceGivenToAuthorizedOnly_002;)V",
                    remap = false),
            require = 1,
            expect = 1,
            remap = false)
    private static void terrainDiffusion$naturalProfile(Args args, WorldGenLevel level) {
        args.set(1, StreamsNaturalRiverAdapter.configure(level, args.get(1)));
        if (!StreamsScalePolicy.appliesTo(level)) {
            return;
        }
        LocalTerrainProvider provider = LocalTerrainProvider.getInstance();
        args.set(2, StreamsInferenceBinding.bind(args.get(2),
                "dev.streamsreflowing.core.terrain.TerrainSampler", provider));
        args.set(3, StreamsInferenceBinding.bind(args.get(3),
                "dev.streamsreflowing.core.terrain.SourceGivenToAuthorizedOnly_002", provider));
    }

    @ModifyArgs(
            method = "engine(Lnet/minecraft/world/level/WorldGenLevel;)Ldev/streamsreflowing/core/RiverEngine;",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/streamsreflowing/worldgen/ProprietaryNiceJohn_002;<init>(Ljava/nio/file/Path;JLdev/streamsreflowing/core/config/StreamsConfig;)V",
                    remap = false),
            require = 1,
            expect = 1,
            remap = false)
    private static void terrainDiffusion$versionedStore(Args args) {
        args.set(0, StreamsNaturalRiverAdapter.storePath(args.get(0), args.get(2)));
    }
}
