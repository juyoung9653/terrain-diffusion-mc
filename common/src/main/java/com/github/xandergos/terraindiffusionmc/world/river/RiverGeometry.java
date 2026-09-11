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
    /** A sampled centreline point.  Natural paths include their exact Segment endpoints. */
    public record PathPoint(double x, double z, double water, double width) { }

    public static final class Geometry {
        private final List<Segment> segments;
        private final int rejectedSegments;
        private final int maxCut;
        private final GroundSampler ground;
        private final Map<Long, List<Segment>> bins;
        private final Map<Segment, List<PathPoint>> paths;

        private Geometry(List<Segment> segments, int rejectedSegments, int maxCut, GroundSampler ground) {
            this(segments, rejectedSegments, maxCut, ground, Map.of());
        }

        private Geometry(List<Segment> segments, int rejectedSegments, int maxCut, GroundSampler ground,
                         Map<Segment, List<PathPoint>> paths) {
            this.segments = List.copyOf(segments);
            this.rejectedSegments = rejectedSegments;
            this.maxCut = maxCut;
            this.ground = ground;
            this.paths = Map.copyOf(paths);
            this.bins = index(this.segments, this.paths);
        }

        public List<Segment> segments() { return segments; }
        public int rejectedSegments() { return rejectedSegments; }
        /** Returns the actual raster centreline; legacy geometry has exactly two endpoint samples. */
        public List<PathPoint> path(Segment segment) {
            List<PathPoint> path = paths.get(segment);
            return path != null ? path : List.of(new PathPoint(segment.x1, segment.z1, segment.water1, segment.width1),
                    new PathPoint(segment.x2, segment.z2, segment.water2, segment.width2));
        }

        public Column column(int x, int z) {
            int original = ground.ground(x, z);
            List<Segment> nearby = bins.get(binKey(Math.floorDiv(x, BIN), Math.floorDiv(z, BIN)));
            if (nearby == null) return new Column(false, original, Integer.MIN_VALUE, original, 0.0, original - 3);
            int wetBed = original, bankGround = original, water = Integer.MIN_VALUE, seal = original - 3;
            double width = 0.0;
            boolean active = false, wet = false;
            for (Segment s : nearby) {
                // Keep the v1 hot path byte-for-byte in its straight-segment arithmetic.
                Sample q = paths.isEmpty() ? sample(s, x, z) : sample(path(s), x, z);
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

        private static Map<Long, List<Segment>> index(List<Segment> segments, Map<Segment, List<PathPoint>> paths) {
            Map<Long, List<Segment>> out = new HashMap<>();
            for (Segment s : segments) {
                List<PathPoint> path = paths.get(s);
                if (path == null) {
                    double radius = Math.max(s.width1, s.width2) + bank(Math.max(s.width1, s.width2));
                    int minX = (int) Math.floor(Math.min(s.x1, s.x2) - radius);
                    int maxX = (int) Math.ceil(Math.max(s.x1, s.x2) + radius);
                    int minZ = (int) Math.floor(Math.min(s.z1, s.z2) - radius);
                    int maxZ = (int) Math.ceil(Math.max(s.z1, s.z2) + radius);
                    for (int bz = Math.floorDiv(minZ, BIN); bz <= Math.floorDiv(maxZ, BIN); bz++)
                        for (int bx = Math.floorDiv(minX, BIN); bx <= Math.floorDiv(maxX, BIN); bx++)
                            out.computeIfAbsent(binKey(bx, bz), ignored -> new ArrayList<>()).add(s);
                    continue;
                }
                double radius = Math.max(s.width1, s.width2) + bank(Math.max(s.width1, s.width2));
                double minPathX = s.x1, maxPathX = s.x1, minPathZ = s.z1, maxPathZ = s.z1;
                if (path != null) for (PathPoint p : path) {
                    minPathX = Math.min(minPathX, p.x); maxPathX = Math.max(maxPathX, p.x);
                    minPathZ = Math.min(minPathZ, p.z); maxPathZ = Math.max(maxPathZ, p.z);
                    radius = Math.max(radius, p.width + 1.0 + bank(p.width + 1.0));
                }
                int minX = (int) Math.floor(minPathX - radius);
                int maxX = (int) Math.ceil(maxPathX + radius);
                int minZ = (int) Math.floor(minPathZ - radius);
                int maxZ = (int) Math.ceil(maxPathZ + radius);
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

    /**
     * Version-two appearance geometry.  It deliberately starts from the proven
     * legacy acceptance set, so routing, terminal checks and accumulated widths
     * remain identical while only the raster centreline is made less gridded.
     */
    public static Geometry buildNatural(DrainagePlanner.Plan plan, int maxCut, GroundSampler ground) {
        Geometry legacy = build(plan, maxCut, ground);
        if (legacy.segments.isEmpty()) return legacy;
        int count = Math.multiplyExact(plan.width(), plan.height());
        int[] in = new int[count];
        List<List<Integer>> neighbours = new ArrayList<>(count);
        for (int i = 0; i < count; i++) neighbours.add(new ArrayList<>());
        for (Segment s : legacy.segments) {
            in[s.target]++;
            neighbours.get(s.source).add(s.target);
            neighbours.get(s.target).add(s.source);
        }
        double[] dx = new double[count], dz = new double[count];
        double[] baseX = new double[count], baseZ = new double[count];
        boolean[] fixed = new boolean[count];
        for (int i = 0; i < count; i++) {
            baseX[i] = x(plan, i); baseZ[i] = z(plan, i);
            fixed[i] = in[i] != 1 || !plan.channel(i) || plan.downstream(i) < 0;
            if (!fixed[i]) {
                // A world-coordinate field, shared by adjacent reaches rather than a per-segment wiggle.
                double wx = x(plan, i), wz = z(plan, i), cap = plan.spacing() * .22;
                dx[i] = cap * Math.sin(wx * .017 + wz * .011 + 0.73);
                dz[i] = cap * Math.sin(wx * .013 - wz * .019 - 1.17);
            }
        }
        for (int pass = 0; pass < 3; pass++) {
            double[] nx = dx.clone(), nz = dz.clone();
            for (int i = 0; i < count; i++) if (!fixed[i] && !neighbours.get(i).isEmpty()) {
                // Smooth actual node positions: a D8 corner is pulled toward its
                // adjacent midpoint, rather than merely smoothing a noise field.
                double ax = baseX[i] + dx[i], az = baseZ[i] + dz[i]; int n = 1;
                for (int other : neighbours.get(i)) { ax += baseX[other] + dx[other]; az += baseZ[other] + dz[other]; n++; }
                nx[i] = ax / n - baseX[i]; nz[i] = az / n - baseZ[i];
            }
            dx = nx; dz = nz;
            clampDisplacements(dx, dz, plan.spacing() * .04);
        }
        List<Segment> output = new ArrayList<>(legacy.segments.size());
        for (Segment s : legacy.segments) output.add(new Segment(s.source, s.target,
                x(plan, s.source) + (int) Math.round(dx[s.source]), z(plan, s.source) + (int) Math.round(dz[s.source]),
                x(plan, s.target) + (int) Math.round(dx[s.target]), z(plan, s.target) + (int) Math.round(dz[s.target]),
                s.water1, s.water2, s.width1, s.width2));
        Map<Integer, Segment> outgoing = new HashMap<>();
        Map<Integer, Segment> incoming = new HashMap<>();
        for (Segment s : output) {
            outgoing.put(s.source, s);
            Segment prior = incoming.get(s.target);
            if (prior == null || s.width2 > prior.width2) incoming.put(s.target, s);
        }
        double[] widthFactor = new double[count];
        for (int i = 0; i < count; i++) widthFactor[i] = coherentWidthFactor(baseX[i], baseZ[i]);
        Tangent[] tangents = new Tangent[count];
        for (int i = 0; i < count; i++) tangents[i] = nodeTangent(i, incoming.get(i), outgoing.get(i));
        Map<Segment, List<PathPoint>> paths = new HashMap<>();
        for (Segment s : output) paths.put(s, naturalPath(s, incoming.get(s.source), outgoing.get(s.target), plan.spacing(),
                widthFactor[s.source], widthFactor[s.target], tangents[s.source], tangents[s.target]));
        Geometry natural = new Geometry(output, legacy.rejectedSegments, maxCut, ground, paths);
        // Retain safe curved reaches.  Only a reach that fails the v1 containment
        // rules becomes a straight connection between the same relaxed endpoints.
        for (Segment s : output) if (!naturalSegmentSafe(natural.path(s), plan, maxCut, ground))
            paths.put(s, straightPath(s, widthFactor[s.source], widthFactor[s.target]));
        natural = new Geometry(output, legacy.rejectedSegments, maxCut, ground, paths);
        for (Segment s : output) if (!naturalSegmentSafe(natural.path(s), plan, maxCut, ground)) return legacy;
        for (int pass = 0; pass < output.size(); pass++) {
            Segment leaking = firstLeakingSegment(natural);
            if (leaking == null) return natural;
            paths.put(leaking, straightPath(leaking, widthFactor[leaking.source], widthFactor[leaking.target]));
            natural = new Geometry(output, legacy.rejectedSegments, maxCut, ground, paths);
        }
        return firstLeakingSegment(natural) == null ? natural : legacy;
    }

    private static List<PathPoint> straightPath(Segment s, double factor1, double factor2) {
        return List.of(new PathPoint(s.x1, s.z1, s.water1, widthAt(s.width1, factor1)),
                new PathPoint(s.x2, s.z2, s.water2, widthAt(s.width2, factor2)));
    }

    private static boolean naturalSegmentSafe(List<PathPoint> path, DrainagePlanner.Plan plan, int maxCut, GroundSampler ground) {
        int edgeX = plan.originX() + (plan.width() - 1) * plan.spacing();
        int edgeZ = plan.originZ() + (plan.height() - 1) * plan.spacing();
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY, radius = 0.0;
        for (PathPoint p : path) {
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
            radius = Math.max(radius, p.width + 1.0 + bank(p.width + 1.0));
        }
        if (minX - radius < plan.originX() || maxX + radius > edgeX || minZ - radius < plan.originZ() || maxZ + radius > edgeZ) return false;
        for (int z = (int) Math.floor(minZ - radius); z <= (int) Math.ceil(maxZ + radius); z++)
            for (int x = (int) Math.floor(minX - radius); x <= (int) Math.ceil(maxX + radius); x++) {
                Sample q = sample(path, x, z);
                if (q.distance > q.width) continue;
                int raw = ground.ground(x, z), bed = q.water - bowl(depth(q.width), q.distance, q.width);
                boolean naturalOcean = q.water == SEA && raw <= SEA;
                if (naturalOcean) bed = Math.min(raw, bed);
                if ((!naturalOcean && raw < q.water) || raw - bed > maxCut) return false;
            }
        return true;
    }

    private static Segment firstLeakingSegment(Geometry geometry) {
        for (Segment s : geometry.segments) {
            List<PathPoint> path = geometry.path(s);
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY, radius = 0.0;
            for (PathPoint p : path) { minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x); minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z); radius = Math.max(radius, p.width + 1.0); }
            for (int z = (int) Math.floor(minZ - radius); z <= (int) Math.ceil(maxZ + radius); z++) for (int x = (int) Math.floor(minX - radius); x <= (int) Math.ceil(maxX + radius); x++) {
                Sample q = sample(path, x, z);
                if (q.distance > q.width || q.water <= SEA) continue;
                for (int[] d : new int[][] {{-1,0},{1,0},{0,-1},{0,1}}) {
                    Column side = geometry.column(x + d[0], z + d[1]);
                    if (side.waterY == Integer.MIN_VALUE && side.groundY < q.water) return s;
                }
            }
        }
        return null;
    }

    private static void clampDisplacements(double[] dx, double[] dz, double cap) {
        for (int i = 0; i < dx.length; i++) {
            double length = Math.hypot(dx[i], dz[i]);
            if (length > cap) { dx[i] *= cap / length; dz[i] *= cap / length; }
        }
    }

    private static double coherentWidthFactor(double x, double z) {
        // Two broad world-space waves make neighbouring nodes agree while still
        // giving a long reach visible pools and constrictions.
        double field = .65 * Math.sin(x * .008 + z * .005 + .9)
                + .35 * Math.sin(x * .003 - z * .010 - .4);
        return Math.max(.68, Math.min(1.24, 1.0 + .28 * field));
    }

    private static List<PathPoint> naturalPath(Segment s, Segment before, Segment after, int spacing,
                                               double widthFactor1, double widthFactor2,
                                               Tangent startTangent, Tangent endTangent) {
        double vx = s.x2 - s.x1, vz = s.z2 - s.z1, length = Math.hypot(vx, vz);
        if (length < 1.0) return List.of(new PathPoint(s.x1, s.z1, s.water1, s.width1), new PathPoint(s.x2, s.z2, s.water2, s.width2));
        double tx0 = startTangent.x, tz0 = startTangent.z, tx1 = endTangent.x, tz1 = endTangent.z;
        double startLen = Math.hypot(tx0, tz0), endLen = Math.hypot(tx1, tz1);
        if (startLen < 1e-6 || endLen < 1e-6) { tx0 = tx1 = vx; tz0 = tz1 = vz; startLen = endLen = length; }
        double handle = Math.min(length * .32, spacing * .38);
        double c1x = s.x1 + tx0 / startLen * handle, c1z = s.z1 + tz0 / startLen * handle;
        double c2x = s.x2 - tx1 / endLen * handle, c2z = s.z2 - tz1 / endLen * handle;
        int steps = Math.max(2, (int) Math.ceil(length / 3.0));
        List<PathPoint> out = new ArrayList<>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps, u = 1.0 - t;
            double px = u*u*u*s.x1 + 3*u*u*t*c1x + 3*u*t*t*c2x + t*t*t*s.x2;
            double pz = u*u*u*s.z1 + 3*u*u*t*c1z + 3*u*t*t*c2z + t*t*t*s.z2;
            double baseWidth = s.width1 + t * (s.width2 - s.width1);
            double factor = widthFactor1 + t * (widthFactor2 - widthFactor1);
            // Endpoints use one shared node factor; no per-link pinch resets.
            out.add(new PathPoint(px, pz, s.water1 + t * (s.water2 - s.water1),
                    widthAt(baseWidth, factor)));
        }
        return List.copyOf(out);
    }

    private static double widthAt(double baseWidth, double factor) { return Math.max(2.0, Math.min(24.0, baseWidth * factor)); }

    private static Tangent nodeTangent(int node, Segment incoming, Segment outgoing) {
        double x, z;
        if (incoming != null && outgoing != null) { x = outgoing.x2 - incoming.x1; z = outgoing.z2 - incoming.z1; }
        else if (outgoing != null) { x = outgoing.x2 - outgoing.x1; z = outgoing.z2 - outgoing.z1; }
        else if (incoming != null) { x = incoming.x2 - incoming.x1; z = incoming.z2 - incoming.z1; }
        else return new Tangent(1.0, 0.0);
        double length = Math.hypot(x, z);
        if (length < 1e-6) return new Tangent(1.0, 0.0);
        // A node-owned, slowly changing rotation supplies meander while C1
        // tangents remain identical on both sides of a shared node.
        double px = incoming != null ? incoming.x2 : outgoing.x1;
        double pz = incoming != null ? incoming.z2 : outgoing.z1;
        double angle = .24 * Math.sin(px * .009 - pz * .006 + .27);
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return new Tangent((x / length) * cos - (z / length) * sin, (x / length) * sin + (z / length) * cos);
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

    private static Sample sample(List<PathPoint> path, int x, int z) {
        if (path.size() == 2) {
            PathPoint a = path.get(0), b = path.get(1);
            Sample result = sample(new Segment(0, 0, (int) Math.round(a.x), (int) Math.round(a.z),
                    (int) Math.round(b.x), (int) Math.round(b.z), (int) Math.round(a.water), (int) Math.round(b.water),
                    a.width, b.width), x, z);
            return new Sample(result.distance, bankWidth(result.width, x, z), result.water);
        }
        double best = Double.POSITIVE_INFINITY, bestWidth = 0;
        int bestWater = Integer.MIN_VALUE;
        for (int i = 1; i < path.size(); i++) {
            PathPoint a = path.get(i - 1), b = path.get(i);
            double dx = b.x - a.x, dz = b.z - a.z, length2 = dx * dx + dz * dz;
            double t = length2 < 1e-9 ? 0.0 : Math.max(0.0, Math.min(1.0, ((x - a.x) * dx + (z - a.z) * dz) / length2));
            double px = a.x + t * dx, pz = a.z + t * dz, distance = Math.hypot(x - px, z - pz);
            if (distance < best) {
                best = distance;
                bestWidth = a.width + t * (b.width - a.width);
                bestWater = (int) Math.round(a.water + t * (b.water - a.water));
            }
        }
        return new Sample(best, bankWidth(bestWidth, x, z), bestWater);
    }

    private static double bankWidth(double width, int x, int z) {
        // Sub-block to one-block irregularities break parallel engineered banks.
        // Evaluate the same bounded field for every reach and every safety check.
        double edge = .6 * Math.sin(x * .23 + z * .17)
                + .4 * Math.sin(x * .13 - z * .29 + 1.7);
        return Math.max(2.0, Math.min(24.0, width + edge));
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
    private record Tangent(double x, double z) { }
}
