package com.github.xandergos.terraindiffusionmc.compat.continuity;

/** Local water depth, independent of absolute world height or rendered fluid depression. */
public final class CattailDepthPolicy {
    public static final String CONTINUITY_VERSION = "3.0.0+0.0.1+1.21.1.neoforge";
    public static final String PROPERTY = "terrainDiffusion.cattailDepth";
    public static final String DEPTHS = "0.5 1";
    public static final String DISABLED_HEIGHT = "2147483647";

    private CattailDepthPolicy() { }

    public static boolean supportsRuntime(boolean client, String version) {
        return client && CONTINUITY_VERSION.equals(version);
    }

    public static boolean isCattailRule(String namespace, String path, String depths, String height) {
        return "minecraft".equals(namespace) && DEPTHS.equals(depths) && DISABLED_HEIGHT.equals(height)
                && path.matches("optifine/ctm/cattails1/[0-4]\\.properties");
    }

    public static boolean allows(boolean sourceWater, boolean fluidAbove, double rootY, double surfaceY) {
        if (!sourceWater || fluidAbove || !Double.isFinite(rootY) || !Double.isFinite(surfaceY)) return false;
        double depth = surfaceY - rootY;
        return Math.abs(depth - 0.5) < 1.0e-6 || Math.abs(depth - 1.0) < 1.0e-6;
    }
}
