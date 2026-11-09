package com.github.xandergos.terraindiffusionmc.world.river;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plans roof-retention masks over short, existing surface-river cuts. */
public final class ShortRiverTunnelPlanner {
    public static final double DEFAULT_MAX_LENGTH = 512.0;
    private static final double EPS = 1.0e-7;
    private static final double MIN_WIDTH = .71, MAX_WIDTH = 5.0, SAMPLE = 2.0;
    private static final double TRANSITION = 128.0, MIN_CORE = 8.0;
    private static final double MIN_LENGTH = 32.0, APRON = 8.0;
    private static final double BURIED_COVER = 9.0, PORTAL_TOLERANCE = 3.0;

    private ShortRiverTunnelPlanner() { }

    /** Streams 2.16.2's width-scaled ditch inset, away from excluded lake mouths. */
    public static int physicalWater(double grade, double halfWidth, int ditchInset) {
        int inset = (int) Math.round(ditchInset * (.5 + .5 * Math.min(1, halfWidth / 3)));
        return (int) Math.floor(grade) - inset;
    }

    @FunctionalInterface public interface Water { int at(int x,int z); }

    /** Move only the two mouths inward (at most 16 blocks each) to meet whole-column rock cover. */
    public static Tunnel fitRoof(Tunnel original, Ground ground, Water water) {
        int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for (Point p:original.centreline) {
            minX=Math.min(minX,floor(p.x-p.halfWidth-APRON)); maxX=Math.max(maxX,(int)Math.ceil(p.x+p.halfWidth+APRON));
            minZ=Math.min(minZ,floor(p.z-p.halfWidth-APRON)); maxZ=Math.max(maxZ,(int)Math.ceil(p.z+p.halfWidth+APRON));
        }
        Tunnel mask=original;
        for (int attempt=0;attempt<34;attempt++) {
            double badAlong=Double.NaN;
            scan: for(int x=minX;x<=maxX;x++) for(int z=minZ;z<=maxZ;z++) {
                if(mask.roofFloorY(x,z,0)==Integer.MIN_VALUE) continue;
                int level=water.at(x,z);
                if(level==Integer.MIN_VALUE) return null;
                if(!(ground.height(x,z)>mask.roofFloorY(x,z,level)+2.0)) {
                    badAlong=mask.project(x,z).along; break scan;
                }
            }
            if(Double.isNaN(badAlong)) return mask;
            double start=mask.coveredStart,end=mask.coveredEnd;
            if(badAlong-original.coveredStart<=16 && badAlong-start<=end-badAlong) start=badAlong+1;
            else if(original.coveredEnd-badAlong<=16) end=badAlong-1;
            else return null;
            if(start-original.coveredStart>16 || original.coveredEnd-end>16 || end-start<MIN_CORE) return null;
            mask=new Tunnel(original.centreline,original.length,start,end);
        }
        return null;
    }

    /**
     * Confirms that a reconstructed native graph has the same retained roof as
     * the graph which was admitted.  {@link #fitRoof} may move either portal
     * inward by at most sixteen blocks; that is the only permitted omission.
     * A one-block longitudinal allowance covers integer rasterisation at a
     * subdivided native segment boundary.  It does not permit a missing bank or
     * an interior hole.
     */
    public static boolean sameRoofFootprint(Tunnel expected, Tunnel actual) {
        if (expected == null || actual == null) return false;
        final double tolerance = 1.0, maxTrim = 16.0;
        if (actual.coveredStart < expected.coveredStart - tolerance
                || actual.coveredStart - expected.coveredStart > maxTrim + EPS
                || actual.coveredEnd > expected.coveredEnd + tolerance
                || expected.coveredEnd - actual.coveredEnd > maxTrim + EPS) return false;
        int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for (Point point : expected.centreline) {
            minX=Math.min(minX,floor(point.x-point.halfWidth-APRON-1));
            maxX=Math.max(maxX,(int)Math.ceil(point.x+point.halfWidth+APRON+1));
            minZ=Math.min(minZ,floor(point.z-point.halfWidth-APRON-1));
            maxZ=Math.max(maxZ,(int)Math.ceil(point.z+point.halfWidth+APRON+1));
        }
        for (Point point : actual.centreline) {
            minX=Math.min(minX,floor(point.x-point.halfWidth-APRON-1));
            maxX=Math.max(maxX,(int)Math.ceil(point.x+point.halfWidth+APRON+1));
            minZ=Math.min(minZ,floor(point.z-point.halfWidth-APRON-1));
            maxZ=Math.max(maxZ,(int)Math.ceil(point.z+point.halfWidth+APRON+1));
        }
        for (int x=minX;x<=maxX;x++) for (int z=minZ;z<=maxZ;z++) {
            int wanted=expected.roofFloorY(x,z,0), got=actual.roofFloorY(x,z,0);
            if (wanted==Integer.MIN_VALUE && got==Integer.MIN_VALUE) continue;
            if (wanted!=Integer.MIN_VALUE && got!=Integer.MIN_VALUE) {
                if (wanted!=got) return false;
                continue;
            }
            if (wanted!=Integer.MIN_VALUE) {
                // An omitted expected column is legal only in the deliberately
                // trimmed mouth; never silently lose an interior roof column.
                double along=expected.project(x,z).along;
                if (along < actual.coveredStart + tolerance || along > actual.coveredEnd - tolerance) continue;
                return false;
            }
            // Do not let reconstruction add a roof beyond the one-cell portal
            // rasterisation allowance either.
            double along=actual.project(x,z).along;
            if (Math.abs(along-expected.coveredStart)<=tolerance
                    || Math.abs(along-expected.coveredEnd)<=tolerance) continue;
            return false;
        }
        return true;
    }

    /** A physical (post-inset) native water sample, upstream to downstream. */
    public record Point(double x, double z, double water, double halfWidth) { }

    /** Immutable mask only; it cannot change native routing, bed, or water. */
    public record Tunnel(List<Point> centreline, double length, double coveredStart, double coveredEnd) {
        public Tunnel {
            centreline = List.copyOf(centreline);
            if (centreline.size() < 2 || !Double.isFinite(length) || length < MIN_LENGTH - EPS
                    || coveredStart < 0 || coveredEnd - coveredStart < MIN_CORE - EPS || coveredEnd > length) {
                throw new IllegalArgumentException("Invalid tunnel");
            }
        }

        /** First protected rock Y, or MIN_VALUE for open portal/outside columns. */
        public int roofFloorY(int x, int z, int liveWaterY) {
            Projection projection = project(x, z);
            if (projection == null || projection.along < coveredStart - EPS || projection.along > coveredEnd + EPS
                    || projection.distance > projection.width + APRON) return Integer.MIN_VALUE;
            int offset = roofOffset(projection.distance / (projection.width + APRON));
            return offset == 0 ? Integer.MIN_VALUE : liveWaterY + offset;
        }

        private Projection project(int x, int z) {
            double best = Double.POSITIVE_INFINITY, along = 0, before = 0, width = 0;
            for (int i = 1; i < centreline.size(); i++) {
                Point a = centreline.get(i - 1), b = centreline.get(i);
                double dx = b.x - a.x, dz = b.z - a.z, squared = dx * dx + dz * dz;
                if (squared <= EPS) continue;
                double segment = Math.sqrt(squared);
                double t = clamp(((x - a.x) * dx + (z - a.z) * dz) / squared, 0, 1);
                double distance = Math.hypot(x - (a.x + dx * t), z - (a.z + dz * t));
                if (distance < best) {
                    best = distance;
                    along = before + segment * t;
                    width = lerp(a.halfWidth, b.halfWidth, t);
                }
                before += segment;
            }
            return Double.isFinite(best) ? new Projection(best, along, width) : null;
        }
    }

    public static List<Tunnel> plan(List<Reach> input, Ground ground,
                                    NaturalRiverPlanform.Corridor corridor, int seaLevel, int waterInset) {
        return plan(input, ground, corridor, seaLevel, waterInset, DEFAULT_MAX_LENGTH);
    }

    public static List<Tunnel> plan(List<Reach> input, Ground ground,
                                    NaturalRiverPlanform.Corridor corridor, int seaLevel,
                                    int waterInset, double maxLength) {
        if (input == null || ground == null || corridor == null) throw new NullPointerException();
        if (!Double.isFinite(maxLength) || maxLength < MIN_LENGTH) throw new IllegalArgumentException("maxLength");
        Map<Key, Node> nodes = graph(input);
        if (nodes == null) return List.of();
        boolean[] used = new boolean[input.size()];
        List<Tunnel> tunnels = new ArrayList<>();
        for (int edge = 0; edge < input.size(); edge++) {
            if (!used[edge] && usable(input.get(edge)) && startsRun(edge, input, nodes)) {
                tunnels.addAll(selectHills(trace(edge, input, nodes, used, waterInset), ground, corridor, seaLevel, maxLength));
            }
        }
        tunnels.sort(Comparator.comparingDouble((Tunnel t) -> t.centreline().get(0).x())
                .thenComparingDouble(t -> t.centreline().get(0).z())
                .thenComparingDouble(t -> t.centreline().get(t.centreline().size() - 1).x())
                .thenComparingDouble(t -> t.centreline().get(t.centreline().size() - 1).z()));
        return List.copyOf(tunnels);
    }

    private static List<Tunnel> selectHills(Path path, Ground ground, NaturalRiverPlanform.Corridor corridor,
                                             int seaLevel, double maxLength) {
        HeightCache heights = new HeightCache(ground);
        List<Tunnel> result = new ArrayList<>();
        int index = 0;
        while (index < path.samples.size()) {
            if (!buried(path.samples.get(index), heights)) { index++; continue; }
            int coreStart = index;
            while (index < path.samples.size() && buried(path.samples.get(index), heights)) index++;
            int coreEnd = index - 1;
            double coreLength = path.samples.get(coreEnd).distance - path.samples.get(coreStart).distance;
            // Tunnel enforces the exact eight-block covered span.  Do not let
            // sampling slack construct a 6–8 block record and throw later.
            if (coreLength < MIN_CORE - EPS) continue;
            int entry = previousPortal(path, coreStart, heights);
            int exit = nextPortal(path, coreEnd, heights);
            if (entry < 0 || exit < 0) continue;
            Tunnel tunnel = validate(path, entry, exit, coreStart, coreEnd, heights, corridor, seaLevel, maxLength);
            if (tunnel != null) result.add(tunnel);
        }
        return result;
    }

    private static int previousPortal(Path path, int coreStart, HeightCache heights) {
        double limit = path.samples.get(coreStart).distance - TRANSITION;
        for (int i = coreStart - 1; i >= 0 && path.samples.get(i).distance >= limit - EPS; i--) {
            if (portal(path.samples.get(i), heights)) return i;
        }
        return -1;
    }

    private static int nextPortal(Path path, int coreEnd, HeightCache heights) {
        double limit = path.samples.get(coreEnd).distance + TRANSITION;
        for (int i = coreEnd + 1; i < path.samples.size() && path.samples.get(i).distance <= limit + EPS; i++) {
            if (portal(path.samples.get(i), heights)) return i;
        }
        return -1;
    }

    private static Tunnel validate(Path path, int first, int last, int coreStart, int coreEnd, HeightCache heights,
                                   NaturalRiverPlanform.Corridor corridor, int seaLevel, double maxLength) {
        if ((first == 0 && path.beginJunction) || (last == path.samples.size() - 1 && path.endJunction)) return null;
        List<Sample> range = path.samples.subList(first, last + 1);
        double length = range.get(range.size() - 1).distance - range.get(0).distance;
        if (length < MIN_LENGTH - EPS || length > maxLength + EPS) return null;
        Point entry = range.get(0).point, exit = range.get(range.size() - 1).point;
        if (entry.water < seaLevel + 1 || exit.water < seaLevel + 1 || entry.water - exit.water < 1
                || (entry.water - exit.water) / length > .08 + EPS) return null;
        List<Point> points = new ArrayList<>();
        for (Sample sample : range) points.add(sample.point);
        double coveredStart = path.samples.get(coreStart).distance - range.get(0).distance;
        double coveredEnd = path.samples.get(coreEnd).distance - range.get(0).distance;
        Tunnel tunnel = new Tunnel(points, length, coveredStart, coveredEnd);
        for (int i = 0; i < range.size(); i++) {
            Sample sample = range.get(i);
            if (i > 0 && sample.point.water > range.get(i - 1).point.water + EPS) return null;
            double along = sample.distance - range.get(0).distance;
            if (!corridor.allows(sample.point.x, sample.point.z, sample.point.halfWidth + APRON + 1)
                    || (along >= coveredStart - EPS && along <= coveredEnd + EPS
                    && !coveredCrossSection(sample, range, i, along, length, heights))) return null;
        }
        return tunnel;
    }

    private static boolean coveredCrossSection(Sample sample, List<Sample> samples, int index, double along,
                                               double length, HeightCache heights) {
        Sample before = samples.get(Math.max(0, index - 1));
        Sample after = samples.get(Math.min(samples.size() - 1, index + 1));
        double dx = after.point.x - before.point.x, dz = after.point.z - before.point.z;
        double norm = Math.hypot(dx, dz);
        if (norm <= EPS) return false;
        double nx = -dz / norm, nz = dx / norm, radius = sample.point.halfWidth + APRON;
        for (double side = -radius; side <= radius + EPS; side += SAMPLE) {
            int roof = roofOffset(Math.abs(side) / radius);
            if (roof == 0) continue;
            int x = (int) Math.floor(sample.point.x + nx * side);
            int z = (int) Math.floor(sample.point.z + nz * side);
            // Heightmap/base-height is the first air cell.  Preserve at least
            // two blocks of rock above the retained ceiling, not a paper roof.
            double height = heights.at(x, z);
            if (!Double.isFinite(height) || height <= sample.point.water + roof + 2) return false;
        }
        return true;
    }

    private static boolean buried(Sample sample, HeightCache heights) {
        return heights.at(floor(sample.point.x), floor(sample.point.z)) >= sample.point.water + BURIED_COVER;
    }

    private static boolean portal(Sample sample, HeightCache heights) {
        double surface = heights.at(floor(sample.point.x), floor(sample.point.z));
        return surface >= sample.point.water - 3 - EPS && surface <= sample.point.water + PORTAL_TOLERANCE + EPS;
    }

    private static int roofOffset(double sideFraction) {
        return (int) Math.round(4 + 2 * (1 - sideFraction * sideFraction));
    }

    private static Path trace(int first, List<Reach> input, Map<Key, Node> nodes, boolean[] used, int inset) {
        List<Sample> samples = new ArrayList<>();
        int edge = first; double distance = 0; boolean endJunction = false;
        while (!used[edge] && usable(input.get(edge))) {
            used[edge] = true;
            Reach reach = input.get(edge); int count = Math.max(1, (int) Math.ceil((length(reach) - EPS) / SAMPLE));
            for (int part = 0; part <= count; part++) {
                if (!samples.isEmpty() && part == 0) continue;
                double t = (double) part / count;
                Point point = new Point(lerp(reach.x1(), reach.x2(), t), lerp(reach.z1(), reach.z2(), t),
                        physicalWater(lerp(reach.y1(), reach.y2(), t), lerp(reach.w1(), reach.w2(), t), inset),
                        lerp(reach.w1(), reach.w2(), t));
                if (!samples.isEmpty()) distance += Math.hypot(point.x - samples.get(samples.size() - 1).point.x,
                        point.z - samples.get(samples.size() - 1).point.z);
                samples.add(new Sample(point, distance));
            }
            Node node = nodes.get(key(reach.x2(), reach.z2()));
            if (validIn(input, node) != 1 || validOut(input, node) != 1) { endJunction = node.degree() > 2; break; }
            edge = onlyValidOut(input, node);
        }
        Node begin = nodes.get(key(input.get(first).x1(), input.get(first).z1()));
        return new Path(samples, begin.degree() > 2, endJunction);
    }

    private static Map<Key, Node> graph(List<Reach> input) {
        Map<Key, Node> nodes = new HashMap<>();
        for (int i = 0; i < input.size(); i++) {
            Reach reach = input.get(i); if (!finite(reach)) return null;
            node(nodes, reach.x1(), reach.z1()).out.add(i); node(nodes, reach.x2(), reach.z2()).in.add(i);
        }
        return nodes;
    }
    private static boolean startsRun(int edge, List<Reach> input, Map<Key, Node> nodes) {
        Node node = nodes.get(key(input.get(edge).x1(), input.get(edge).z1()));
        return validIn(input, node) != 1 || validOut(input, node) != 1;
    }
    private static int validIn(List<Reach> input, Node node) { int count = 0; for (int edge : node.in) if (usable(input.get(edge))) count++; return count; }
    private static int validOut(List<Reach> input, Node node) { int count = 0; for (int edge : node.out) if (usable(input.get(edge))) count++; return count; }
    private static int onlyValidOut(List<Reach> input, Node node) { for (int edge : node.out) if (usable(input.get(edge))) return edge; throw new IllegalStateException(); }
    private static boolean usable(Reach r) { return finite(r) && r.w1() >= MIN_WIDTH && r.w2() >= MIN_WIDTH && r.w1() <= MAX_WIDTH && r.w2() <= MAX_WIDTH && length(r) > EPS; }
    private static boolean finite(Reach r) { return r != null && Double.isFinite(r.x1()) && Double.isFinite(r.z1()) && Double.isFinite(r.y1()) && Double.isFinite(r.w1()) && Double.isFinite(r.x2()) && Double.isFinite(r.z2()) && Double.isFinite(r.y2()) && Double.isFinite(r.w2()) && Double.isFinite(r.speed1()) && Double.isFinite(r.speed2()); }
    private static double length(Reach r) { return Math.hypot(r.x2() - r.x1(), r.z2() - r.z1()); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int floor(double value) { return (int) Math.floor(value); }
    private static Key key(double x, double z) { return new Key(x, z); }
    private static Node node(Map<Key, Node> nodes, double x, double z) { return nodes.computeIfAbsent(key(x, z), ignored -> new Node()); }
    private record Projection(double distance, double along, double width) { }
    private record Key(double x, double z) { }
    private record Sample(Point point, double distance) { }
    private record Path(List<Sample> samples, boolean beginJunction, boolean endJunction) { }
    private static final class Node { final List<Integer> in = new ArrayList<>(), out = new ArrayList<>(); int degree() { return in.size() + out.size(); } }
    private static final class HeightCache {
        private final Ground ground; private final Map<Long, Double> values = new HashMap<>();
        HeightCache(Ground ground) { this.ground = ground; }
        double at(int x, int z) { return values.computeIfAbsent((((long) x) << 32) ^ (z & 0xffffffffL), ignored -> ground.height(x, z)); }
    }
}
