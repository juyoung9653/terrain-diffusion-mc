package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankMargin;
import java.util.HashMap;
import java.util.Map;

public final class NaturalLakeShoreTest {
    public static void main(String[] args) throws Exception {
        FakeEngine engine = new FakeEngine();
        Map<Long, Integer> forward = sample(engine, false), reverse = sample(engine, true);
        require(forward.equals(reverse), "shore height depends on chunk generation order");
        require(forward.size() == 9216, "missing boundary columns");
        // Saved screenshot trigger: wetting ends at2991; dry soil starts at2992.
        StreamsBankLakeMask saved = new StreamsBankLakeMask(new SavedEdge(), 2992, 1248);
        saved.prepareWetting(new SavedEdge());
        var edge = saved.shore(2992, 1248);
        require(edge != null && edge.water() == 120 && edge.distance() == 1, "saved wetting edge lost");
        int target = NaturalRiverBankMargin.lakeShoreTarget(122, edge.water(), edge.distance(), 2992, 1248);
        require(target >= 120 && target < 122, "saved dry wall not softened while containing lake");
        for (double distance : new double[]{0, -1, 4, Double.NaN, Double.POSITIVE_INFINITY})
            require(NaturalRiverBankMargin.lakeShoreTarget(122, 120, distance, 0, 0) == 122,
                    "invalid/distant shore changed");
        require(NaturalRiverBankMargin.lakeShoreTarget(119, 120, 1, 0, 0) == 119, "submerged bed changed");
        require(NaturalRiverBankMargin.lakeShoreTarget(130, 120, 1, 0, 0) == 130, "high cliff changed");
        require(NaturalRiverBankMargin.lakeShoreTarget(Integer.MAX_VALUE, Integer.MIN_VALUE, 1, 0, 0)
                == Integer.MAX_VALUE, "height overflow changed terrain");
        for (int water = -60; water < 390; water++) for (int rise = 1; rise <= 4; rise++)
            for (double distance : new double[]{1, Math.sqrt(2), 2, Math.sqrt(5), Math.sqrt(8), 3}) {
                int height = NaturalRiverBankMargin.lakeShoreTarget(water + rise, water, distance, -16, 0);
                require(height >= water && height <= water + rise && water + rise - height <= 2,
                        "shore violates containment or two-block cut bound");
            }
        System.out.println("NaturalLakeShoreTest: passed saved lake-only wall, 9216 boundary/order columns,"
                + " highest local water, two-block cap, submerged/distant/high-cliff rejection");
    }

    private static Map<Long, Integer> sample(FakeEngine engine, boolean reverse) throws Exception {
        Map<Long, Integer> heights = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            int ordinal = reverse ? 35 - i : i;
            int minX = (ordinal / 6 - 3) * 16, minZ = (ordinal % 6 - 3) * 16;
            StreamsBankLakeMask mask = new StreamsBankLakeMask(engine, minX, minZ);
            mask.prepareWetting(engine);
            for (int x = minX; x < minX + 16; x++) for (int z = minZ; z < minZ + 16; z++) {
                var shore = mask.shore(x, z);
                int safe = Integer.MIN_VALUE;
                double nearest = Double.POSITIVE_INFINITY;
                if (engine.lakeWetting(x, z, 2) == null)
                    for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                        Lake lake = engine.lakeWetting(x + dx, z + dz, 2);
                        if (dx * dx + dz * dz <= 9 && lake != null) {
                            safe = Math.max(safe, lake.level());
                            nearest = Math.min(nearest, Math.hypot(dx, dz));
                        }
                    }
                require((shore == null) == (safe == Integer.MIN_VALUE), "chunk seam changed shore admission");
                if (shore != null) require(shore.water() == safe && shore.distance() == nearest,
                        "shore lost highest nearby water or nearest distance");
                int top = 127;
                int height = shore == null ? top : NaturalRiverBankMargin.lakeShoreTarget(top,
                        shore.water(), shore.distance(), x, z);
                if (shore != null) require(height >= shore.water(), "higher adjacent lake can escape");
                heights.put(((long) x << 32) ^ (z & 0xffffffffL), height);
            }
        }
        return heights;
    }

    public record Lake(int level) { }
    public static class Config { public int gridSpacing() { return 16; } }
    public static class FakeEngine {
        public Config config() { return new Config(); }
        public boolean lakeNearChunk(int x, int z, int radius) { return true; }
        public Lake lakeAt(int x, int z) { return (x + 17) * (x + 17) + z * z < 121 ? new Lake(124) : null; }
        public Lake lakeWetting(int x, int z, int radius) {
            return (x + 17) * (x + 17) + z * z < 169 ? new Lake(x < -17 ? 124 : 126) : null;
        }
    }
    public static class SavedEdge extends FakeEngine {
        @Override public Lake lakeAt(int x, int z) { return x <= 2989 ? new Lake(120) : null; }
        @Override public Lake lakeWetting(int x, int z, int radius) { return x <= 2991 ? new Lake(120) : null; }
    }
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
