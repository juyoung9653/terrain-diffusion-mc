package com.github.xandergos.terraindiffusionmc.world;

/**
 * Preserves a shallow roof over Terrain Diffusion land near sea level.
 *
 * <p>The supplied surface height is the highest original solid block in a
 * column. It must be captured before carvers modify the chunk.</p>
 */
public final class LowlandCarvingGuard {
    private static final int LOWLAND_DEPTH = 8;

    private LowlandCarvingGuard() {
    }

    /** The last dry tidal shelf is one block below the exclusive sea level. */
    public static boolean dryTidalRoof(int seaLevel, int surface, int originalWaterLevel, int carveY) {
        return surface == seaLevel - 1 && originalWaterLevel == 0
                && carveY <= surface && carveY > surface - LOWLAND_DEPTH;
    }

    /** Keep the natural floor and shallow water of a pre-carver wetland watertight. */
    public static boolean protectsWaterBed(int originalSurfaceY, int waterLevel, int carveY) {
        int depth = waterLevel - 1 - originalSurfaceY;
        return waterLevel > 0 && depth >= 1 && depth <= 3
                && carveY >= originalSurfaceY - (LOWLAND_DEPTH - 1) && carveY < waterLevel;
    }

    public static boolean protects(boolean terrainDiffusionWorld, int seaLevel, int originalSurfaceY, int carveY) {
        if (!terrainDiffusionWorld
                || originalSurfaceY < seaLevel
                || originalSurfaceY > seaLevel + LOWLAND_DEPTH) {
            return false;
        }
        return carveY >= originalSurfaceY - (LOWLAND_DEPTH - 1) && carveY <= originalSurfaceY;
    }
}
