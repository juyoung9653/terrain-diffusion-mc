package com.github.xandergos.terraindiffusionmc.compat.terrainslabs;

import com.github.xandergos.terraindiffusionmc.world.ecology.FeatureEcologyMath;
import com.github.xandergos.terraindiffusionmc.world.ecology.FeatureEcologyMath.SurfaceMaterial;

/** Material-only admission for native generated slabs; null retains the native choice. */
public final class TerrainSlabSurfacePolicy {
    private TerrainSlabSurfacePolicy() { }

    public static String replacement(int version, String source, boolean generated, boolean dry,
                                     boolean supported, float temperature, float moisture, SurfaceMaterial surface) {
        if (version < 10 || !generated || !dry
                || !(source.equals("terrain_slabs:grass_slab") || source.equals("terrain_slabs:dirt_slab"))
                || !FeatureEcologyMath.dryMineralShore(temperature, moisture)) return null;
        if (surface == SurfaceMaterial.SAND) return supported ? "sand_slab" : "terrain_sandstone_slab";
        if (surface == SurfaceMaterial.GRAVEL) return supported ? "gravel_slab" : "terrain_stone_slab";
        return null;
    }
}
