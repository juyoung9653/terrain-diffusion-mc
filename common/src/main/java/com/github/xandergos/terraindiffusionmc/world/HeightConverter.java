package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;

public class HeightConverter {
    private static final int SEA_LEVEL = 63;
    private static final short MAX_PIPELINE_METERS = 10_000;

    private static float getResolutionForScale(int configuredScale) {
        return WorldPipelineModelConfig.nativeResolution() / WorldScaleManager.clampScale(configuredScale);
    }

    public static int convertToMinecraftHeight(short meters) {
        return convertToMinecraftHeight(meters, WorldScaleManager.getCurrentScale());
    }

    public static int convertToMinecraftHeight(short meters, int configuredScale) {
        int baseY;
        float resolution = getResolutionForScale(configuredScale);

        if (meters >= 0) {
            baseY = (int) (meters / resolution);
        } else {
            baseY = (int) (-Math.sqrt(Math.abs(meters) + 10) + Math.sqrt(10.0)) - 1;
        }

        return baseY + SEA_LEVEL;
    }

    /** Explicit saved profile; the legacy overloads never change existing worlds. */
    public static int convertToMinecraftHeight(short meters, int configuredScale, int terrainProfile) {
        if (terrainProfile < 1 || terrainProfile > 2)
            throw new IllegalArgumentException("Unsupported terrain profile " + terrainProfile);
        if (terrainProfile == 1 || meters >= 0) return convertToMinecraftHeight(meters, configuredScale);
        return OceanDepthProfile.oceanBoundary(meters, WorldScaleManager.clampScale(configuredScale));
    }

    /**
     * Returns the highest generated block Y expected from pipeline output for a given scale.
     */
    public static int getMaxGeneratedYForScale(int configuredScale) {
        return convertToMinecraftHeight(MAX_PIPELINE_METERS, configuredScale);
    }
}
