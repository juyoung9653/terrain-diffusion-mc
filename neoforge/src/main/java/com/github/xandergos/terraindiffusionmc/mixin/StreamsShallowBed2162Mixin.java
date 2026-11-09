package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalRiverAdapter;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRecordAccess;
import com.github.xandergos.terraindiffusionmc.world.river.ShallowRiverBedPolicy;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * The 2.16.2 depth helper runs before native carving, material selection and
 * structure checks.  Only its small extra two-block cut of a planned one-block
 * natural-river channel is limited.
 */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.worldgen.NoDecompilePermissionEver_001", remap = false)
public abstract class StreamsShallowBed2162Mixin {
    private static final String DEPTH_HELPER = "proprietary1("
            + "Ldev/streamsreflowing/core/compose/ColumnPlan;"
            + "Ldev/streamsreflowing/core/config/StreamsConfig;DDDJIIIID)I";

    @ModifyReturnValue(method = DEPTH_HELPER, at = @At("RETURN"), require = 1, remap = false)
    private static int terrainDiffusion$preservePlannedShallowBed(int original,
            @Coerce Object plan, @Coerce Object config,
            double bankBlend, double distance, double width, long seed,
            int x, int z, int carverWaterY, int gradeY, double noise) {
        if (original != 2) return original;
        boolean active = StreamsNaturalRiverAdapter.active(config);
        if (!active) return original;
        int version = StreamsNaturalRiverAdapter.version(config);
        if (version < ShallowRiverBedPolicy.FIRST_FIXED_PROFILE_VERSION) return original;
        return ShallowRiverBedPolicy.normalizeDepth(original, active,
                version,
                Boolean.TRUE.equals(StreamsRecordAccess.read(plan, "inChannel")),
                Boolean.TRUE.equals(StreamsRecordAccess.read(plan, "source")),
                Boolean.TRUE.equals(StreamsRecordAccess.read(plan, "terminal")),
                (int) StreamsRecordAccess.number(plan, "waterSurfaceY"),
                (int) StreamsRecordAccess.number(plan, "bedY"), carverWaterY);
    }
}
