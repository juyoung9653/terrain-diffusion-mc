package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;

/**
 * Applies per-engine Streams settings for Terrain Diffusion worlds whose physical land scale is
 * larger than the default.  The native TOML is never changed.
 */
public final class StreamsScalePolicy {
    public static final int DEFAULT_SCALE = StreamsScaleMath.DEFAULT_SCALE;

    private StreamsScalePolicy() {
    }

    public static boolean appliesTo(WorldGenLevel level) {
        if (level == null) {
            return false;
        }
        ServerLevel serverLevel = level.getLevel();
        return serverLevel != null
                && TerrainDiffusionBiomeSources.isTerrainDiffusion(
                        serverLevel.getChunkSource().getGenerator().getBiomeSource());
    }

    public static int activeScale(WorldGenLevel level) {
        return appliesTo(level) ? WorldScaleManager.getCurrentScale() : DEFAULT_SCALE;
    }

    /** Keeps scales one and two byte-for-byte equivalent to the native configuration. */
    public static int plateScale(int nativePlateScale, int scale) {
        if (scale <= DEFAULT_SCALE) {
            return nativePlateScale;
        }
        return StreamsScaleMath.plateScale(nativePlateScale, scale);
    }

    /** Raises large-scale channel width modestly without changing headwater width or drainage. */
    public static double widthScale(double nativeWidthScale, int scale) {
        if (scale <= DEFAULT_SCALE) {
            return nativeWidthScale;
        }
        return StreamsScaleMath.widthScale(nativeWidthScale, scale);
    }

    /** Caps the half-width at Streams' documented native maximum. */
    public static double maxWidth(double nativeMaxWidth, int scale) {
        if (scale <= DEFAULT_SCALE) {
            return nativeMaxWidth;
        }
        return StreamsScaleMath.maxWidth(nativeMaxWidth, scale);
    }

}
