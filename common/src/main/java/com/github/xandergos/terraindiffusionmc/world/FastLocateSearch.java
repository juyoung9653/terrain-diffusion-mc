package com.github.xandergos.terraindiffusionmc.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Pure bounded planning. Hints affect order, never acceptance or absence claims. */
public final class FastLocateSearch {
    public static final int DEFAULT_RADIUS = 8192, MAX_RADIUS = 32768;
    public static final int TILE_LIMIT = 64, START_LIMIT = 32;
    public record Point(int x, int z) {
        public Point quart() { return new Point(Math.floorDiv(x, 4) * 4, Math.floorDiv(z, 4) * 4); }
        public Point tile(int size) { return new Point(Math.floorDiv(x, size) * size, Math.floorDiv(z, size) * size); }
        public long distanceSquared(Point other) {
            long dx = (long) x - other.x, dz = (long) z - other.z;
            return dx * dx + dz * dz;
        }
    }
    public record Candidate(Point point, int score, int sector) { }

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

    /** 25 bounded climate patches, near origin first, then all compass directions. */
    public static List<Point> patches(Point origin, int radius) {
        List<Point> result = new ArrayList<>();
        for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++)
            result.add(new Point(origin.x + x * radius / 2, origin.z + z * radius / 2));
        result.sort(Comparator.comparingLong(p -> p.distanceSquared(origin)));
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
        for (int sector = 0; sector < 25; sector++) {
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
        private volatile int tiles, starts, patches;
        public Budget(LongSupplier clock, long durationNanos) {
            this.clock = clock; this.deadline = clock.getAsLong() + durationNanos;
        }
        public void cancel() { cancelled.set(true); }
        public boolean cancelled() { return cancelled.get(); }
        public boolean live() { return !cancelled() && clock.getAsLong() < deadline; }
        public boolean takeTile() { if (!live() || tiles >= TILE_LIMIT) return false; tiles++; return true; }
        public boolean takeStart() { if (!live() || starts >= START_LIMIT) return false; starts++; return true; }
        public boolean takePatch() { if (!live() || patches >= 25) return false; patches++; return true; }
        public int tiles() { return tiles; }
        public int starts() { return starts; }
        public int patches() { return patches; }
    }
    private FastLocateSearch() { }
}
