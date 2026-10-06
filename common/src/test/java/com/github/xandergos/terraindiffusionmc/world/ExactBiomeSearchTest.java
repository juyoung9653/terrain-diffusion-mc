package com.github.xandergos.terraindiffusionmc.world;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Differential tests against per-candidate tile loading, without Minecraft or native inference. */
public final class ExactBiomeSearchTest {
    private record Tile(int x, int z) { }
    private record Hit(int x, int y, int z, int biome) { }
    private static final int[] HEIGHTS = {65, 129, 1, 193, -63, 257};

    public static void main(String[] args) {
        differential();
        callCounts();
        failureAndEmptyColumns();
        System.out.println("PASS ExactBiomeSearchTest: 2400 differential searches; negative/tile/quart boundaries; "
                + "Y-dependent caves, first-hit Y, null fallback, retry and request isolation");
    }

    private static int biome(int x, int y, int z, boolean invariant) {
        return Math.floorMod((x >> 2) * 31 + (z >> 2) * 17 + (invariant ? 0 : (y >> 2) * 7), 13);
    }

    private static void differential() {
        Random random = new Random(5029451);
        for (int size : new int[]{16, 64, 256}) for (boolean invariant : new boolean[]{false, true}) {
            for (int trial = 0; trial < 400; trial++) {
                int originX = random.nextInt(2048) - 1024;
                int originZ = random.nextInt(2048) - 1024;
                int step = 1 + random.nextInt(35);
                Set<Integer> accepted = trial % 5 == 0 ? Set.of(99) : Set.of(random.nextInt(13));
                int[] heights = trial % 17 == 0 ? new int[0] : HEIGHTS;
                ExactBiomeSearch<Tile, Integer> search = new ExactBiomeSearch<>(size,
                        (x, z, s) -> new Tile(x, z),
                        (x, y, z, sx, sz, tile) -> {
                            require(tile.x == sx && tile.z == sz, "tile key");
                            require(x >= sx && x < sx + size && z >= sz && z < sz + size, "tile bounds");
                            require((x & 3) == 0 && (y & 3) == 0 && (z & 3) == 0, "quart rounding");
                            return biome(x, y, z, invariant);
                        }, invariant);
                // A fixed square spiral provides ties, turns and revisits. Production uses BlockPos.spiralAround.
                int dx = 0, dz = 0, vx = 1, vz = 0, leg = 1, onLeg = 0, turns = 0;
                Hit expected = null, actual = null;
                for (int candidate = 0; candidate < 289; candidate++) {
                    int x = originX + dx * step, z = originZ + dz * step;
                    if (expected == null) for (int y : heights) {
                        int b = biome((x >> 2) << 2, (y >> 2) << 2, (z >> 2) << 2, invariant);
                        if (accepted.contains(b)) { expected = new Hit(x, y, z, b); break; }
                    }
                    if (actual == null) {
                        var match = search.findColumn(x, z, heights, accepted::contains);
                        if (match != null) actual = new Hit(x, match.blockY(), z, match.biome());
                    }
                    if (expected != null || actual != null) break;
                    dx += vx; dz += vz;
                    if (++onLeg == leg) {
                        onLeg = 0; int oldX = vx; vx = -vz; vz = oldX;
                        if (++turns % 2 == 0) leg++;
                    }
                }
                require(java.util.Objects.equals(expected, actual), "differential result: " + expected + " / " + actual);
            }
        }
    }

    private static void callCounts() {
        Map<Tile, Tile> sharedCache = new HashMap<>();
        int[] loads = {0}, coldMisses = {0}, reads = {0};
        for (boolean invariant : new boolean[]{false, true}) {
            sharedCache.clear();
            for (int run = 0; run < 2; run++) {
                loads[0] = coldMisses[0] = reads[0] = 0;
                ExactBiomeSearch<Tile, Integer> search = new ExactBiomeSearch<>(256,
                        (x, z, size) -> {
                            loads[0]++;
                            return sharedCache.computeIfAbsent(new Tile(x, z), key -> { coldMisses[0]++; return key; });
                        }, (x, y, z, sx, sz, tile) -> { reads[0]++; return 1; }, invariant);
                for (int x = -512; x < 512; x += 32)
                    require(search.findColumn(x, -1, HEIGHTS, b -> b == 99) == null, "absent result");
                require(loads[0] == 4 && coldMisses[0] == (run == 0 ? 4 : 0), "cold/warm load counts");
                require(reads[0] == (invariant ? 32 : 192), "vertical reads");
                System.out.printf("  %s %s: baseline provider calls=192, cached calls=%d, cold misses=%d, biome reads=%d%n",
                        invariant ? "Y-invariant" : "Y-dependent", run == 0 ? "cold" : "warm", loads[0], coldMisses[0], reads[0]);
            }
        }
        // Only the current tile is retained; revisiting an older tile calls the provider again.
        loads[0] = 0;
        ExactBiomeSearch<Tile, Integer> bounded = new ExactBiomeSearch<>(256,
                (x, z, size) -> { loads[0]++; return new Tile(x, z); },
                (x, y, z, sx, sz, tile) -> 1, false);
        for (int x : new int[]{0, 256, 0}) bounded.findColumn(x, 0, HEIGHTS, b -> false);
        require(loads[0] == 3, "bounded current tile");
    }

    private static void failureAndEmptyColumns() {
        int[] loads = {0};
        ExactBiomeSearch<Tile, Integer> search = new ExactBiomeSearch<>(256,
                (x, z, size) -> {
                    if (++loads[0] == 1) throw new IllegalStateException("expected load failure");
                    return null;
                }, (x, y, z, sx, sz, tile) -> tile == null ? 1 : 2, false);
        require(search.findColumn(0, 0, new int[0], b -> true) == null && loads[0] == 0, "empty heights");
        try {
            search.findColumn(-1, -257, HEIGHTS, b -> true);
            throw new AssertionError("failure swallowed");
        } catch (IllegalStateException expected) { }
        var match = search.findColumn(-1, -257, HEIGHTS, b -> true);
        require(match != null && match.blockY() == 65 && match.biome() == 1, "fallback and original Y");
        require(search.findColumn(-1, -257, HEIGHTS, b -> false) == null && loads[0] == 2, "null tile retained");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
