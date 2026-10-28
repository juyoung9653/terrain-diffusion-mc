package com.github.xandergos.terraindiffusionmc.config;

/** Capture-only contract used by the production OnnxModel class. */
public final class TerrainDiffusionConfig {
    private TerrainDiffusionConfig() { }
    public static String inferenceDevice() { return "gpu"; }
    public static boolean offloadModels() { return false; }
}
