package com.github.xandergos.terraindiffusionmc.compat.streams;

import java.util.HashMap;
import java.util.Map;

/** Runs the production reflective lake-mask adapter without a Minecraft world. */
public final class StreamsBankLakeMaskTest {
    public static void main(String[] args) throws Exception {
        FakeEngine engine = new FakeEngine();
        Map<Long, Boolean> forward = sample(engine, false), reverse = sample(engine, true);
        require(forward.equals(reverse), "lake exclusion changes with chunk generation order");
        require(forward.containsValue(true) && forward.containsValue(false), "fixture misses exclusion edge");
        // The negative-coordinate lake crosses x=-16 and z=0; every query uses the
        // same world-coordinate circle, including all four chunk corners.
        for (int x = -48; x < 48; x++) for (int z = -48; z < 48; z++) {
            boolean expected = expected(engine, x, z, 8);
            require(forward.get(key(x, z)) == expected, "chunk halo disagrees at " + x + "," + z);
            require(StreamsBankLakeMask.nearColumn(engine, x, z, 2) == expected(engine, x, z, 2),
                    "on-demand shallow hook disagrees at " + x + "," + z);
        }
        FakeEngine absent = new FakeEngine() {
            @Override public Object lakeAt(int x, int z) { return null; }
        };
        require(!new StreamsBankLakeMask(absent, -16, -16).excludes(-1, -1, 8), "absent lake blocks bank");
        System.out.println("StreamsBankLakeMaskTest: passed 9216 columns, negative boundaries, reverse order, absent lake");
    }

    private static Map<Long, Boolean> sample(FakeEngine engine, boolean reverse) throws Exception {
        Map<Long, Boolean> result = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            int ordinal = reverse ? 35 - i : i;
            int minX = (ordinal / 6 - 3) * 16, minZ = (ordinal % 6 - 3) * 16;
            StreamsBankLakeMask mask = new StreamsBankLakeMask(engine, minX, minZ);
            for (int x = minX; x < minX + 16; x++) for (int z = minZ; z < minZ + 16; z++)
                result.put(key(x, z), mask.excludes(x, z, 8));
        }
        return result;
    }

    private static boolean expected(FakeEngine engine, int x, int z, int radius) {
        for (int lx = x - radius; lx <= x + radius; lx++) for (int lz = z - radius; lz <= z + radius; lz++)
            if ((lx - x) * (lx - x) + (lz - z) * (lz - z) <= radius * radius
                    && engine.lakeAt(lx, lz) != null) return true;
        return false;
    }

    public static class FakeEngine {
        public FakeConfig config() { return new FakeConfig(); }
        public boolean lakeNearChunk(int x, int z, int radius) {
            return x - radius <= -6 && x + 16 + radius >= -28
                    && z - radius <= 12 && z + 16 + radius >= -10;
        }
        public Object lakeAt(int x, int z) {
            return (x + 17) * (x + 17) + (z - 1) * (z - 1) < 121 ? this : null;
        }
    }

    public static class FakeConfig { public int gridSpacing() { return 32; } }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
