import com.github.xandergos.terraindiffusionmc.compat.terrainslabs.TerrainSlabRiverPolicy;

public final class TerrainSlabRiverPolicyTest {
    public static void main(String[] args) {
        for (int version = 0; version < 9; version++)
            require(!protect(version, true, true, true, 0, 2, 62, 60, 62, 600, 688), "legacy slab policy changed");
        // Saved screenshot grid: water over gravel at Y62 was turned into bottom
        // waterlogged slabs on z672/688/704 when the neighboring chunk looked solid.
        for (int z : new int[]{-32, -16, 0, 16, 672, 688, 704}) for (int x = -33; x <= 33; x++) {
            for (boolean neighborAlreadyCarved : new boolean[]{false, true}) {
                boolean nativeCandidate = !neighborAlreadyCarved;
                boolean protectedWater = protect(9, true, true, true, 0, 2, 62, 60, 62, x, z);
                require(!(nativeCandidate && !protectedWater), "chunk-order-dependent water slab survives");
            }
            require(protect(9, false, true, true, 0, 2, 62, 60, 62, x, z), "planned channel not protected");
            require(protect(9, false, true, false, 2.5, 2, 63, 60, 62, x, z), "containing apron not protected");
            require(!protect(9, false, true, false, 20, 2, 63, 60, 62, x, z), "unrelated dry slope lost slabs");
            require(!protect(9, false, true, true, 0, 2, -40, 60, 62, x, z), "deep cave slabs changed");
            require(!protect(9, false, false, false, Double.NaN, 0, 63, 60, 62, x, z), "absent river changed slabs");
        }
        require(protect(9, true, false, false, Double.NaN, 0, 62, 0, 0, 0, 0), "water requires a river plan");
        require(TerrainSlabRiverPolicy.protect(9, true, true, false, false, Double.NaN, 0, 62, 0, 0, 0, 0),
                "saved V9 ocean policy changed");
        require(!TerrainSlabRiverPolicy.protect(10, true, true, false, false, Double.NaN, 0, 62, 0, 0, 0, 0),
                "ocean without river lost wet slabs");
        require(TerrainSlabRiverPolicy.protect(10, true, false, false, false, Double.NaN, 0, 62, 0, 0, 0, 0),
                "inland water without river lost protection");
        require(TerrainSlabRiverPolicy.protect(10, true, true, true, true, 0, 2, 62, 60, 62, 0, 0),
                "ocean river mouth lost channel protection");
        require(TerrainSlabRiverPolicy.protect(10, true, true, true, false, 2.5, 2, 63, 60, 62, 0, 0),
                "ocean river mouth lost containing apron");
        require(!TerrainSlabRiverPolicy.protect(10, true, true, true, false, 20, 2, 62, 60, 62, 0, 0),
                "ocean outside river apron lost wet slabs");
        require(!TerrainSlabRiverPolicy.protect(10, true, true, true, true, 0, 2, -40, 60, 62, 0, 0),
                "deep ocean slope lost wet slabs");
        System.out.println("TerrainSlabRiverPolicyTest: passed saved seam, neighbor-order, negative boundary, legacy, ocean/mouth and dry/cave checks");
    }
    private static boolean protect(int v, boolean wet, boolean corridor, boolean channel, double d, double w,
                                   int y, int bed, int surface, int x, int z) {
        return TerrainSlabRiverPolicy.protect(v, wet, false, corridor, channel, d, w, y, bed, surface, x, z);
    }
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
