package com.github.xandergos.terraindiffusionmc.world.river;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure-Java, immutable raster geometry for a planned river network. */
public final class RiverGeometry {
    private static final int SEA = 62;
    private static final int BIN = 64;

    private RiverGeometry() { }

    @FunctionalInterface
    public interface GroundSampler { int ground(int x, int z); }

    public record Column(boolean active, int groundY, int waterY, int originalGroundY, double halfWidth, int sealY) { }
    public record Segment(int source, int target, int x1, int z1, int x2, int z2,
                          int water1, int water2, double width1, double width2) { }

    public static final class Geometry {
        private final List<Segment> segments;
        private final int rejectedSegments;
        private final int maxCut;
        private final GroundSampler ground;
        private final Map<Long, List<Segment>> bins;

        private Geometry(List<Segment> segments, int rejectedSegments, int maxCut, GroundSampler ground) {
            this.segments = List.copyOf(segments);
            this.rejectedSegments = rejectedSegments;
            this.maxCut = maxCut;
            this.ground = ground;
            this.bins = index(this.segments);
        }

        public List<Segment> segments() { return segments; }
        public int rejectedSegments() { return rejectedSegments; }

        public Column column(int x, int z) {
            int original = ground.ground(x, z);
            List<Segment> nearby = bins.get(binKey(Math.floorDiv(x, BIN), Math.floorDiv(z, BIN)));
            if (nearby == null) return new Column(false, original, Integer.MIN_VALUE, original, 0.0, original - 3);
            int wetBed = original, bankGround = original, water = Integer.MIN_VALUE, seal = original - 3;
            double width = 0.0;
            boolean active = false, wet = false;
            for (Segment s : nearby) {
                Sample q = sample(s, x, z);
                if (q.distance > q.width + bank(q.width)) continue;
                active = true;
                width = Math.max(width, q.width);
                int depth = depth(q.width);
                if (q.distance <= q.width) {
                    int bed = q.water - bowl(depth, q.distance, q.width);
                    if (q.water == SEA && original <= SEA) bed = Math.min(original, bed);
                    wet = true;
                    wetBed = Math.min(wetBed, bed);
                    water = Math.max(water, q.water);
                    seal = Math.min(seal, bed - 3);
                } else {
                    double t = (q.distance - q.width) / bank(q.width);
                    double smooth = t * t * (3.0 - 2.0 * t);
                    int bankTarget = (int) Math.round((q.water + 1) * (1.0 - smooth) + original * smooth);
                    bankGround = Math.min(bankGround, Math.max(original - maxCut, bankTarget));
                    seal = Math.min(seal, q.water - depth - 3);
                }
            }
            if (!active) return new Column(false, original, Integer.MIN_VALUE, original, 0.0, original - 3);
            int groundY = wet ? wetBed : bankGround;
            return new Column(true, Math.max(original - maxCut, groundY), water, original, width, seal);
        }

        private static Map<Long, List<Segment>> index(List<Segment> segments) {
            Map<Long, List<Segment>> out = new HashMap<>();
            for (Segment s : segments) {
                double radius = Math.max(s.width1, s.width2) + bank(Math.max(s.width1, s.width2));
                int minX = (int) Math.floor(Math.min(s.x1, s.x2) - radius);
                int maxX = (int) Math.ceil(Math.max(s.x1, s.x2) + radius);
                int minZ = (int) Math.floor(Math.min(s.z1, s.z2) - radius);
                int maxZ = (int) Math.ceil(Math.max(s.z1, s.z2) + radius);
                for (int bz = Math.floorDiv(minZ, BIN); bz <= Math.floorDiv(maxZ, BIN); bz++)
                    for (int bx = Math.floorDiv(minX, BIN); bx <= Math.floorDiv(maxX, BIN); bx++)
                        out.computeIfAbsent(binKey(bx, bz), ignored -> new ArrayList<>()).add(s);
            }
            out.replaceAll((ignored, list) -> Collections.unmodifiableList(list));
            return Collections.unmodifiableMap(out);
        }
    }

    public static Geometry build(DrainagePlanner.Plan plan, int maxCut, GroundSampler ground) {
        if (plan == null || ground == null) throw new NullPointerException();
        if (maxCut < 0) throw new IllegalArgumentException("maxCut must be non-negative");
        int count = Math.multiplyExact(plan.width(), plan.height());
        Candidate[] candidates = new Candidate[count];
        for (int i = 0; i < count; i++) {
            int downstream = plan.downstream(i);
            if (!plan.channel(i) || downstream < 0) continue;
            candidates[i] = candidate(plan, i, downstream, plan.renderedAccumulation(i), plan.renderedAccumulation(downstream));
        }
        boolean[] rejected = new boolean[count];
        for (int i = 0; i < count; i++) if (candidates[i] != null
                && (unsafe(candidates[i], plan, maxCut, ground)
                || (!oceanRoot(plan, candidates[i].target) && candidates[candidates[i].target] == null))) rejected[i] = true;
        propagate(candidates, rejected, plan);
        while (true) {
            List<Segment> output = segments(plan, candidates, rejected);
            Geometry preview = new Geometry(output, 0, maxCut, ground);
            boolean changed = rejectLeakingSides(output, preview, candidates, rejected, ground);
            if (!changed) {
                int rejectedCount = 0;
                for (int i = 0; i < count; i++) if (candidates[i] != null && rejected[i]) rejectedCount++;
                return new Geometry(output, rejectedCount, maxCut, ground);
            }
            propagate(candidates, rejected, plan);
        }
    }

    private static List<Segment> segments(DrainagePlanner.Plan plan, Candidate[] candidates, boolean[] rejected) {
        int count = candidates.length;
        double[] local = new double[count], retained = new double[count];
        int[] indegree = new int[count];
        for (int i = 0; i < count; i++) local[i] = Math.max(0.0, plan.renderedAccumulation(i));
        for (Candidate c : candidates) if (c != null) local[c.target] -= Math.max(0.0, plan.renderedAccumulation(c.source));
        for (int i = 0; i < count; i++) retained[i] = Math.max(0.0, local[i]);
        for (int i = 0; i < count; i++) if (candidates[i] != null && !rejected[i]) indegree[candidates[i].target]++;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < count; i++) if (indegree[i] == 0) queue.add(i);
        while (!queue.isEmpty()) {
            int source = queue.remove(); Candidate c = candidates[source];
            if (c == null || rejected[source]) continue;
            retained[c.target] += retained[source];
            if (--indegree[c.target] == 0) queue.add(c.target);
        }
        List<Segment> output = new ArrayList<>();
        for (int i = 0; i < count; i++) if (candidates[i] != null && !rejected[i]) {
            Candidate c = candidates[i];
            double targetArea = plan.channel(c.target) ? retained[c.target] : retained[i];
            output.add(candidate(plan, i, c.target, retained[i], targetArea).segment);
        }
        return output;
    }

    private static void propagate(Candidate[] candidates, boolean[] rejected, DrainagePlanner.Plan plan) {
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < candidates.length; i++) {
                Candidate c = candidates[i];
                if (c != null && !rejected[i] && !oceanRoot(plan, c.target)
                        && (candidates[c.target] == null || rejected[c.target])) { rejected[i] = true; changed = true; }
            }
        } while (changed);
    }

    private static boolean rejectLeakingSides(List<Segment> segments, Geometry geometry, Candidate[] candidates,
                                              boolean[] rejected, GroundSampler ground) {
        boolean changed = false;
        for (Segment s : segments) {
            double radius = Math.max(s.width1, s.width2);
            int minX = (int) Math.floor(Math.min(s.x1, s.x2) - radius), maxX = (int) Math.ceil(Math.max(s.x1, s.x2) + radius);
            int minZ = (int) Math.floor(Math.min(s.z1, s.z2) - radius), maxZ = (int) Math.ceil(Math.max(s.z1, s.z2) + radius);
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                Sample q = sample(s, x, z);
                if (q.distance > q.width || q.water <= SEA) continue;
                for (int[] d : new int[][] {{-1,0},{1,0},{0,-1},{0,1}}) {
                    Column side = geometry.column(x + d[0], z + d[1]);
                    if (side.waterY != Integer.MIN_VALUE || side.groundY >= q.water) continue;
                    rejected[s.source] = true;
                    changed = true;
                    break;
                }
            }
        }
        return changed;
    }

    private static Candidate candidate(DrainagePlanner.Plan p, int source, int target, double sourceArea, double targetArea) {
        int x1 = x(p, source), z1 = z(p, source), x2 = x(p, target), z2 = z(p, target);
        int water1 = water(p, source), water2 = oceanRoot(p, target) ? SEA : water(p, target);
        return new Candidate(source, target, new Segment(source, target, x1, z1, x2, z2, water1, water2,
                width(sourceArea), width(targetArea)));
    }

    private static boolean unsafe(Candidate c, DrainagePlanner.Plan p, int maxCut, GroundSampler ground) {
        Segment s = c.segment;
        double radius = Math.max(s.width1, s.width2);
        int minX = (int) Math.floor(Math.min(s.x1, s.x2) - radius), maxX = (int) Math.ceil(Math.max(s.x1, s.x2) + radius);
        int minZ = (int) Math.floor(Math.min(s.z1, s.z2) - radius), maxZ = (int) Math.ceil(Math.max(s.z1, s.z2) + radius);
        int edgeX = p.originX() + (p.width() - 1) * p.spacing(), edgeZ = p.originZ() + (p.height() - 1) * p.spacing();
        double fullRadius = radius + bank(radius);
        minX = (int) Math.floor(Math.min(s.x1, s.x2) - fullRadius); maxX = (int) Math.ceil(Math.max(s.x1, s.x2) + fullRadius);
        minZ = (int) Math.floor(Math.min(s.z1, s.z2) - fullRadius); maxZ = (int) Math.ceil(Math.max(s.z1, s.z2) + fullRadius);
        if (minX < p.originX() || maxX > edgeX || minZ < p.originZ() || maxZ > edgeZ) return true;
        for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            Sample q = sample(s, x, z);
            if (q.distance > q.width) continue;
            int raw = ground.ground(x, z), bed = q.water - bowl(depth(q.width), q.distance, q.width);
            boolean naturalOcean = q.water == SEA && raw <= SEA;
            if (naturalOcean) bed = Math.min(raw, bed);
            if ((!naturalOcean && raw < q.water) || raw - bed > maxCut) return true;
        }
        return false;
    }

    private static Sample sample(Segment s, int x, int z) {
        double dx = s.x2 - s.x1, dz = s.z2 - s.z1, len2 = dx * dx + dz * dz;
        double t = len2 == 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, ((x - s.x1) * dx + (z - s.z1) * dz) / len2));
        double px = s.x1 + t * dx, pz = s.z1 + t * dz;
        return new Sample(Math.hypot(x - px, z - pz), s.width1 + t * (s.width2 - s.width1),
                (int) Math.round(s.water1 + t * (s.water2 - s.water1)));
    }
    private static int x(DrainagePlanner.Plan p, int node) { return p.originX() + (node % p.width()) * p.spacing(); }
    private static int z(DrainagePlanner.Plan p, int node) { return p.originZ() + (node / p.width()) * p.spacing(); }
    private static int water(DrainagePlanner.Plan p, int node) { return Math.max(SEA, (int) Math.floor(p.drainageLevel(node)) - 1); }
    private static boolean oceanRoot(DrainagePlanner.Plan p, int node) {
        return p.downstream(node) < 0 && p.terminal(node) == DrainagePlanner.Terminal.OCEAN;
    }
    private static double width(double area) { return Math.max(2.0, Math.min(24.0, 1.5 + 1.2 * Math.sqrt(Math.max(0.0, area) / 65536.0))); }
    private static int depth(double width) { return Math.max(2, Math.min(4, (int) Math.round(1.0 + width * .10))); }
    private static int bowl(int depth, double distance, double width) { return Math.max(1, (int) Math.round(depth * (1.0 - (distance / width) * (distance / width)))); }
    private static double bank(double width) { return 4.0 + width * 1.5; }
    private static long binKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private record Candidate(int source, int target, Segment segment) { }
    private record Sample(double distance, double width, int water) { }
}
