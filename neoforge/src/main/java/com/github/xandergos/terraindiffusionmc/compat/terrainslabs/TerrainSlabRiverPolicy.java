package com.github.xandergos.terraindiffusionmc.compat.terrainslabs;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankMargin;

/** Pure V9 admission rule for Terrain Slabs' generated half-blocks. */
public final class TerrainSlabRiverPolicy {
    private TerrainSlabRiverPolicy() { }

    public static boolean protect(int version, boolean water, boolean corridor, boolean channel,
                                  double distance, double width, int y, int bed, int surface, int x, int z) {
        if (version < 9) return false;
        if (water) return true;
        if (!corridor || !Double.isFinite(distance) || !Double.isFinite(width) || width <= 0) return false;
        if ((long) y < (long) bed - 1 || (long) y > (long) surface + 3) return false;
        // A dry neighbour can still be awaiting its river carve. Exclude the same
        // plan-defined apron on both sides of a chunk edge regardless of that state.
        return channel || distance - width <= NaturalRiverBankMargin.width(width, x, z) + 1;
    }
}
