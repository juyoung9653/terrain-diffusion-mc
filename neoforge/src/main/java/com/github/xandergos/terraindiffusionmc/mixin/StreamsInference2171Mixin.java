package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsInferenceBinding;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalRiverAdapter;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsScalePolicy;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverProfile;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Supplies profile-14 inputs to the original engine and binds its asynchronous samplers. */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.StreamsWorldgen", remap = false)
public abstract class StreamsInference2171Mixin {
    @ModifyArgs(method = "engine(Lnet/minecraft/world/level/WorldGenLevel;)"
            + "Ldev/streamsreflowing/core/RiverEngine;",
            at = @At(value = "INVOKE", target = "Ldev/streamsreflowing/core/RiverEngine;<init>(J"
                    + "Ldev/streamsreflowing/core/config/StreamsConfig;"
                    + "Ldev/streamsreflowing/core/terrain/TerrainSampler;"
                    + "Ldev/streamsreflowing/core/terrain/SourceGivenToAuthorizedOnly_002;)V"),
            require = 1, expect = 1, remap = false)
    private static void terrainDiffusion$bindSamplers(Args args, WorldGenLevel level) {
        if (!StreamsScalePolicy.appliesTo(level)) return;
        args.set(1, StreamsNaturalRiverAdapter.configure(level, args.get(1)));
        LocalTerrainProvider provider = LocalTerrainProvider.getInstance();
        args.set(2, StreamsInferenceBinding.bind(args.get(2),
                "dev.streamsreflowing.core.terrain.TerrainSampler", provider));
        args.set(3, StreamsInferenceBinding.bind(args.get(3),
                "dev.streamsreflowing.core.terrain.SourceGivenToAuthorizedOnly_002", provider));
    }

    // Pin the effective domain before native engine/lattice creation, not just in its config.
    @ModifyArgs(method = "engine(Lnet/minecraft/world/level/WorldGenLevel;)"
            + "Ldev/streamsreflowing/core/RiverEngine;",
            at = @At(value = "INVOKE", target = "Ldev/streamsreflowing/worldgen/ProprietaryNiceJohn_006;"
                    + "proprietary1(Ljava/nio/file/Path;JI)I"), require = 1, expect = 1, remap = false)
    private static void terrainDiffusion$pinWatershed(Args args, WorldGenLevel level) {
        if (!StreamsScalePolicy.appliesTo(level) || !WorldNaturalRiverState.enabled(level.getLevel())) return;
        int profile = WorldNaturalRiverState.version(level.getLevel());
        args.set(0, ((java.nio.file.Path) args.get(0)).resolve("td-natural-v" + profile));
        args.set(2, NaturalRiverProfile.watershedPlateV3(args.get(2), StreamsScalePolicy.activeScale(level)));
    }

    @ModifyArgs(method = "engine(Lnet/minecraft/world/level/WorldGenLevel;)"
            + "Ldev/streamsreflowing/core/RiverEngine;",
            at = @At(value = "INVOKE", target = "Ldev/streamsreflowing/worldgen/SeeAiNoticeConstant_002;"
                    + "<init>(Ljava/nio/file/Path;JLdev/streamsreflowing/core/config/StreamsConfig;)V"),
            require = 1, expect = 1, remap = false)
    private static void terrainDiffusion$versionedStore(Args args) {
        args.set(0, StreamsNaturalRiverAdapter.storePath(args.get(0), args.get(2)));
    }
}
