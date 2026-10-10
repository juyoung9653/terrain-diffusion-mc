package com.github.xandergos.terraindiffusionmc.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Pure bounded planning. Hints affect order, never acceptance or absence claims. */
public final class FastLocateSearch {
    public static final int DEFAULT_RADIUS = 32768, MAX_RADIUS = 131072;
    public static final int TILE_LIMIT = 256, START_LIMIT = 128, PATCH_LIMIT = 81;
    public static final int REGION_LIMIT = 8192, REGIONS_PER_PATCH = 128, REGION_BATCH = 32;
    public static final int DEFAULT_SECONDS = 300, MAX_SECONDS = 1800, PATCH_BATCH = 9;
    public record Point(int x, int z) {
        public Point quart() { return new Point(Math.floorDiv(x, 4) * 4, Math.floorDiv(z, 4) * 4); }
        public Point tile(int size) { return new Point(Math.floorDiv(x, size) * size, Math.floorDiv(z, size) * size); }
        public long distanceSquared(Point other) {
            long dx = (long) x - other.x, dz = (long) z - other.z;
            return dx * dx + dz * dz;
        }
    }
    public record Candidate(Point point, int score, int sector) { }

    /** Reserve an equal share for remaining batches so nearby misses cannot consume all far coverage. */
    public static int batchAllowance(int remainingWork, int remainingPatches) {
        int batches = Math.max(1, (remainingPatches + PATCH_BATCH - 1) / PATCH_BATCH);
        return Math.max(0, (remainingWork + batches - 1) / batches);
    }

    /** Reserve planning work across remaining patches, then groups within the current patch. */
    public static int regionAllowance(int remainingWork, int remainingParts) {
        if (remainingParts <= 0) throw new IllegalArgumentException("Planning parts must be positive");
        return Math.max(0, (remainingWork + remainingParts - 1) / remainingParts);
    }

    @FunctionalInterface
    public interface Verifier<T> { T verify(Candidate candidate) throws Exception; }

    /** A hint never produces a result. Only an exact verifier may return one. */
    public static <T> T findVerified(List<Candidate> candidates, Budget budget, Verifier<T> verifier) throws Exception {
        for (Candidate candidate : candidates) {
            if (!budget.takeTile()) break;
            T result = verifier.verify(candidate);
            if (!budget.live()) return null;
            if (result != null) return result;
        }
        return null;
    }

    /** Dense nearby patches plus logarithmic rings: increasing radius retains local coverage. */
    public static List<Point> patches(Point origin, int radius) {
        var unique = new java.util.LinkedHashSet<Point>();
        int inner = Math.min(radius, 2048);
        for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++)
            unique.add(new Point(origin.x + x * inner / 2, origin.z + z * inner / 2));
        for (int distance = Math.min(radius, inner * 2); distance > inner;) {
            for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++)
                if (x != 0 || z != 0) unique.add(new Point(origin.x + x * distance, origin.z + z * distance));
            if (distance == radius) break;
            distance = Math.min(radius, distance * 2);
        }
        // Fill angular gaps in the widest rings without dropping any existing nearby samples.
        for (int distance = radius; distance > inner && unique.size() < PATCH_LIMIT; distance /= 2) {
            for (int z : new int[]{-1, 1}) for (int x : new int[]{-1, 1}) {
                if (unique.size() < PATCH_LIMIT)
                    unique.add(new Point(origin.x + x * distance, origin.z + z * distance / 2));
                if (unique.size() < PATCH_LIMIT)
                    unique.add(new Point(origin.x + x * distance / 2, origin.z + z * distance));
            }
        }
        List<Point> result = new ArrayList<>(unique);
        result.sort(Comparator.comparingLong(p -> p.distanceSquared(origin)));
        return List.copyOf(result);
    }

    /** Native placement-region coordinates intersecting a patch, nearest first and bounded.
     * Each region needs just one native potential-chunk calculation, shared by all tag variants.
     */
    public static List<Point> placementRegions(Point patch, int extent, Point origin, int radius, int spacing) {
        if (spacing <= 0) throw new IllegalArgumentException("Placement spacing must be positive");
        int blocks = Math.multiplyExact(spacing, 16);
        int minX = Math.floorDiv(Math.max(patch.x - extent, origin.x - radius), blocks);
        int maxX = Math.floorDiv(Math.min(patch.x + extent, origin.x + radius), blocks);
        int minZ = Math.floorDiv(Math.max(patch.z - extent, origin.z - radius), blocks);
        int maxZ = Math.floorDiv(Math.min(patch.z + extent, origin.z + radius), blocks);
        int cx = Math.floorDiv(patch.x, blocks), cz = Math.floorDiv(patch.z, blocks);
        int reach = Math.max(Math.max(cx - minX, maxX - cx), Math.max(cz - minZ, maxZ - cz));
        List<Point> result = new ArrayList<>();
        for (int ring = 0; ring <= reach; ring++) {
            for (int z = -ring; z <= ring; z++) for (int x = -ring; x <= ring; x++) {
                if (Math.max(Math.abs(x), Math.abs(z)) != ring) continue;
                int rx = cx + x, rz = cz + z;
                if (rx < minX || rx > maxX || rz < minZ || rz > maxZ) continue;
                result.add(new Point(rx, rz));
                if (result.size() == REGIONS_PER_PATCH) return List.copyOf(result);
            }
        }
        return List.copyOf(result);
    }

    public static boolean inside(Point point, Point origin, int radius) {
        return Math.abs((long) point.x - origin.x) <= radius
                && Math.abs((long) point.z - origin.z) <= radius;
    }

    /** Every fourth slot explores another sector, even when all hints are misleading. */
    public static List<Candidate> ranked(List<Candidate> input, Point origin, int tileSize) {
        var unique = new LinkedHashMap<Point, Candidate>();
        for (Candidate candidate : input) unique.merge(candidate.point.tile(tileSize), candidate,
                (a, b) -> b.score > a.score ? b : a);
        List<Candidate> ranked = new ArrayList<>(unique.values());
        ranked.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparingLong(c -> c.point.distanceSquared(origin))
                .thenComparingInt(c -> c.point.x).thenComparingInt(c -> c.point.z));
        List<Candidate> diverse = new ArrayList<>();
        for (int sector : ranked.stream().mapToInt(Candidate::sector).distinct().sorted().toArray()) {
            final int s = sector;
            ranked.stream().filter(c -> c.sector == s).findFirst().ifPresent(diverse::add);
        }
        List<Candidate> result = new ArrayList<>();
        while (!ranked.isEmpty()) {
            Candidate next = result.size() % 4 == 3 && !diverse.isEmpty()
                    ? diverse.removeFirst() : ranked.getFirst();
            if (ranked.remove(next)) result.add(next);
            diverse.remove(next);
        }
        return List.copyOf(result);
    }

    /** Cancellation marks stale immediately; ownership is released only by the worker's finally. */
    public static final class Budget {
        private final LongSupplier clock;
        private final long deadline;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile int tiles, starts, patches, regions;
        public Budget(LongSupplier clock, long durationNanos) {
            this.clock = clock; this.deadline = clock.getAsLong() + durationNanos;
        }
        public void cancel() { cancelled.set(true); }
        public boolean cancelled() { return cancelled.get(); }
        public boolean live() { return !cancelled() && clock.getAsLong() < deadline; }
        public boolean takeTile() { if (!live() || tiles >= TILE_LIMIT) return false; tiles++; return true; }
        public boolean takeStart() { if (!live() || starts >= START_LIMIT) return false; starts++; return true; }
        public boolean takePatch() { if (!live() || patches >= PATCH_LIMIT) return false; patches++; return true; }
        public boolean takeRegion() { if (!live() || regions >= REGION_LIMIT) return false; regions++; return true; }
        public int tiles() { return tiles; }
        public int starts() { return starts; }
        public int patches() { return patches; }
        public int regions() { return regions; }
    }
    private FastLocateSearch() { }
}
