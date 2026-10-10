import com.github.xandergos.terraindiffusionmc.compat.terrainslabs.TerrainSlabSurfacePolicy;
import com.github.xandergos.terraindiffusionmc.world.ecology.FeatureEcologyMath.SurfaceMaterial;

public final class TerrainSlabSurfacePolicyTest {
    public static void main(String[] args) {
        for (String soil : new String[]{"terrain_slabs:grass_slab", "terrain_slabs:dirt_slab"}) {
            require("sand_slab".equals(select(10, soil, true, true, true, 28, .1F, SurfaceMaterial.SAND)), "desert cap");
            require("gravel_slab".equals(select(10, soil, true, true, true, 18, .07F, SurfaceMaterial.GRAVEL)), "arid gravel shore");
            require("terrain_sandstone_slab".equals(select(10, soil, true, true, false, 28, .1F, SurfaceMaterial.SAND)),
                    "unsupported sand cap would fall");
            require("terrain_stone_slab".equals(select(10, soil, true, true, false, 18, .07F, SurfaceMaterial.GRAVEL)),
                    "unsupported gravel cap would fall");
            for (int version = 0; version < 10; version++)
                require(select(version, soil, true, true, true, 28, .1F, SurfaceMaterial.SAND) == null, "legacy changed");
            require(select(10, soil, false, true, true, 28, .1F, SurfaceMaterial.SAND) == null, "manual slab changed");
            require(select(10, soil, true, false, true, 28, .1F, SurfaceMaterial.SAND) == null, "wet slab changed");
            for (float[] climate : new float[][]{{-6, .1F}, {2, 1.25F}, {13, .5F}, {28, 1.5F}})
                for (SurfaceMaterial material : SurfaceMaterial.values())
                    require(select(10, soil, true, true, true, climate[0], climate[1], material) == null,
                            "cold, temperate or humid climate changed");
            for (SurfaceMaterial material : new SurfaceMaterial[]{null, SurfaceMaterial.GRASS, SurfaceMaterial.COARSE_DIRT,
                    SurfaceMaterial.PODZOL, SurfaceMaterial.STONE})
                require(select(10, soil, true, true, true, 28, .1F, material) == null, "non-sediment surface changed");
        }
        for (String source : new String[]{"terrain_slabs:podzol_slab", "terrain_slabs:gravel_slab", "minecraft:stone_slab",
                "other:grass_slab", "terrain_slabs:grass_block"})
            require(select(10, source, true, true, true, 28, .1F, SurfaceMaterial.SAND) == null, "unrelated block changed");
        System.out.println("TerrainSlabSurfacePolicyTest: passed mineral caps, nonfalling unsupported caps, legacy/manual/wet/cold/humid/unrelated exclusions");
    }

    private static String select(int version, String source, boolean generated, boolean dry, boolean supported,
                                 float temperature, float moisture, SurfaceMaterial material) {
        return TerrainSlabSurfacePolicy.replacement(version, source, generated, dry, supported, temperature, moisture, material);
    }

    private static void require(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
}
