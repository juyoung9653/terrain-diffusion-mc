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

    public static boolean protects(boolean terrainDiffusionWorld, int seaLevel, int originalSurfaceY, int carveY) {
        if (!terrainDiffusionWorld
                || originalSurfaceY < seaLevel
                || originalSurfaceY > seaLevel + LOWLAND_DEPTH) {
            return false;
        }
        return carveY >= originalSurfaceY - (LOWLAND_DEPTH - 1) && carveY <= originalSurfaceY;
    }
}
