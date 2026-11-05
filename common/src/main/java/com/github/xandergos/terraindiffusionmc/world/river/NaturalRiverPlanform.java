package com.github.xandergos.terraindiffusionmc.world.river;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Set;

/**
 * Conservative planform repair for long, grid-aligned native reaches.
 *
 * <p>The repair is deliberately a no-op unless a run within an unbranched path is
 * long, narrow and almost collinear.  It pins both native end nodes, so it
 * cannot move a confluence, source, outlet or the neighbouring graph.  The
 * returned pieces retain their native source index for callers which carry
 * opaque per-segment metadata.</p>
 */
public final class NaturalRiverPlanform {
    private static final double EPSILON = 1.0e-7;
    private static final double MIN_LENGTH = 32.0;
    private static final double MAX_HALF_WIDTH = 5.0;
    private static final double MAX_SLOPE = 0.08;
    private static final double MAX_OFFSET = 4.0;
    private static final double SAMPLE_SPACING = 4.0;

    private NaturalRiverPlanform() { }

    public record Piece(int sourceIndex, Reach reach) { }

    @FunctionalInterface
    public interface Corridor {
        /** True only when this point and its bank radius are safe to wet. */
        boolean allows(double x, double z, double radius);
    }

    public static List<Piece> refine(List<Reach> input, Ground ground, Corridor corridor,
                                     long seed, int seaLevel) {
        return refine(input, ground, corridor, seed, seaLevel, 0);
    }

    /** Native graph grades precede the planner's fixed water-surface inset. */
    public static List<Piece> refine(List<Reach> input, Ground ground, Corridor corridor,
                                     long seed, int seaLevel, int waterInset) {
        if (input == null || ground == null || corridor == null) throw new NullPointerException();
        List<Piece> unchanged = identities(input);
        if (input.isEmpty()) return unchanged;
        Map<NodeKey, Node> nodes = new HashMap<>();
        boolean[] seen = new boolean[input.size()];
        for (int i = 0; i < input.size(); i++) {
            Reach r = input.get(i);
            if (r == null || !finite(r)) return unchanged;
            node(nodes, r.x1(), r.z1()).outgoing.add(i);
            node(nodes, r.x2(), r.z2()).incoming.add(i);
            // Native sink placeholders pin their node but must not disable
            // independent straight reaches elsewhere in the same network.
            seen[i] = length(r) <= EPSILON || r.w1() <= 0 || r.w2() <= 0;
        }
        Map<Integer, List<Piece>> replacements = new HashMap<>();
        for (int i = 0; i < input.size(); i++) {
            Node start = nodes.get(key(input.get(i).x1(), input.get(i).z1()));
            if (!seen[i] && (start.incoming.size() != 1 || start.outgoing.size() != 1)) {
                appendChain(i, input, nodes, seen, replacements, ground, corridor, seed, seaLevel, waterInset);
            }
        }
        // A degree-one-only component is a cycle.  Its endpoints are not safe
        // anchors, so preserve it as supplied.
        List<Piece> result = new ArrayList<>();
        // Native segment order is part of the persisted graph contract.  Group
        // replacement pieces under their source record, but never reorder other
        // records while doing so.
        for (int i = 0; i < input.size(); i++) result.addAll(replacements.getOrDefault(i, List.of(new Piece(i, input.get(i)))));
        return List.copyOf(result);
    }

    private static void appendChain(int first, List<Reach> input, Map<NodeKey, Node> nodes,
                                    boolean[] seen, Map<Integer, List<Piece>> replacements, Ground ground,
                                    Corridor corridor, long seed, int seaLevel, int waterInset) {
        List<Integer> chain = new ArrayList<>();
        int current = first;
        while (!seen[current]) {
            seen[current] = true;
            chain.add(current);
            Reach r = input.get(current);
            Node end = nodes.get(key(r.x2(), r.z2()));
            if (end.incoming.size() != 1 || end.outgoing.size() != 1) break;
            current = end.outgoing.get(0);
        }
        for (List<Integer> run : straightRuns(chain, input)) {
            List<Piece> bent = bend(run, input, nodes, ground, corridor, seed, seaLevel, waterInset);
            if (bent != null) {
                for (int index : run) replacements.put(index, new ArrayList<>());
                for (Piece piece : bent) replacements.get(piece.sourceIndex()).add(piece);
            }
        }
    }

    /** Split an unbranched path at visible direction or grade changes. */
    private static List<List<Integer>> straightRuns(List<Integer> chain, List<Reach> input) {
        List<List<Integer>> result = new ArrayList<>();
        List<Integer> run = new ArrayList<>();
        double ux = 0, uz = 0;
        for (int index : chain) {
            Reach r = input.get(index); double length = length(r);
            double vx = (r.x2() - r.x1()) / length, vz = (r.z2() - r.z1()) / length;
            boolean grade = r.y1() + EPSILON < r.y2()
                    || r.y1() - r.y2() > Math.max(1.0, MAX_SLOPE * length) + EPSILON;
            boolean turn = !run.isEmpty() && ux * vx + uz * vz < .985;
            if (grade || turn) {
                if (!run.isEmpty()) result.add(List.copyOf(run));
                run = new ArrayList<>();
            }
            if (!grade) {
                if (run.isEmpty()) { ux = vx; uz = vz; }
                run.add(index);
            }
        }
        if (!run.isEmpty()) result.add(List.copyOf(run));
        return result;
    }

    private static List<Piece> bend(List<Integer> chain, List<Reach> input, Map<NodeKey, Node> nodes, Ground ground,
                                    Corridor corridor, long seed, int seaLevel, int waterInset) {
        Reach first = input.get(chain.get(0));
        Reach last = input.get(chain.get(chain.size() - 1));
        double dx = last.x2() - first.x1(), dz = last.z2() - first.z1();
        double chord = Math.hypot(dx, dz);
        double total = 0, maximumWidth = 0;
        for (int index : chain) {
            Reach reach = input.get(index);
            if (reach.y1() + EPSILON < reach.y2()
                    || reach.y1() - reach.y2() > Math.max(1.0, MAX_SLOPE * length(reach)) + EPSILON) return null;
            total += length(reach); maximumWidth = Math.max(maximumWidth, Math.max(reach.w1(), reach.w2()));
        }
        if (total < MIN_LENGTH || chord < MIN_LENGTH || maximumWidth > MAX_HALF_WIDTH
                || total > chord * 1.025 || first.y1() - waterInset < seaLevel + 1 || last.y2() - waterInset < seaLevel + 1
                || (first.y1() - last.y2()) / total > MAX_SLOPE || first.y1() + EPSILON < last.y2()) return null;
        // Existing lateral variation is already a planform: do not straighten
        // or overwrite it merely because the endpoints happen to align.
        double nx = -dz / chord, nz = dx / chord;
        for (int index : chain) {
            Reach r = input.get(index);
            if (Math.abs((r.x1() - first.x1()) * nx + (r.z1() - first.z1()) * nz) > 0.75
                    || Math.abs((r.x2() - first.x1()) * nx + (r.z2() - first.z1()) * nz) > 0.75) return null;
        }
        double amplitude = Math.min(MAX_OFFSET, Math.max(1.25, total * 0.045));
        long hash = mix(seed ^ Double.doubleToLongBits(first.x1()) ^ Long.rotateLeft(Double.doubleToLongBits(first.z1()), 19)
                ^ Long.rotateLeft(Double.doubleToLongBits(last.x2()), 37));
        double sign = (hash & 1L) == 0 ? 1.0 : -1.0;
        Set<Integer> atStart = junctionEnvelope(first.x1(), first.z1(), input, nodes);
        Set<Integer> atEnd = junctionEnvelope(last.x2(), last.z2(), input, nodes);
        for (double scale : new double[] {1.0, .65, .4}) {
            for (double candidateSign : new double[] {sign, -sign}) {
                List<Piece> candidate = pieces(chain, input, first, dx, dz, chord, nx, nz,
                        candidateSign * amplitude * scale);
                if (safe(candidate, input, chain, atStart, atEnd, ground, corridor, seaLevel, waterInset)) return candidate;
            }
        }
        return null;
    }

    private static List<Piece> pieces(List<Integer> chain, List<Reach> input, Reach first,
                                      double dx, double dz, double chord, double nx, double nz,
                                      double amplitude) {
        List<Piece> result = new ArrayList<>();
        double before = 0;
        double total = 0; for (int index : chain) total += length(input.get(index));
        for (int source : chain) {
            Reach r = input.get(source); double len = length(r);
            int count = Math.max(1, (int) Math.ceil(len / SAMPLE_SPACING));
            for (int part = 0; part < count; part++) {
                double a = (before + len * part / count) / total;
                double b = (before + len * (part + 1) / count) / total;
                double x1 = first.x1() + dx * a + nx * offset(a, amplitude);
                double z1 = first.z1() + dz * a + nz * offset(a, amplitude);
                double x2 = first.x1() + dx * b + nx * offset(b, amplitude);
                double z2 = first.z1() + dz * b + nz * offset(b, amplitude);
                // Exact original endpoints avoid floating point node-key drift.
                if (part == 0 && before == 0) { x1 = r.x1(); z1 = r.z1(); }
                if (part == count - 1 && before + len == total) { x2 = r.x2(); z2 = r.z2(); }
                double localA = (double) part / count, localB = (double) (part + 1) / count;
                result.add(new Piece(source, new Reach(x1, z1, lerp(r.y1(), r.y2(), localA), lerp(r.w1(), r.w2(), localA),
                        x2, z2, lerp(r.y1(), r.y2(), localB), lerp(r.w1(), r.w2(), localB),
                        lerp(r.speed1(), r.speed2(), localA), lerp(r.speed1(), r.speed2(), localB))));
            }
            before += len;
        }
        return result;
    }

    private static boolean safe(List<Piece> candidate, List<Reach> original, List<Integer> chain,
                                Set<Integer> atStart, Set<Integer> atEnd,
                                Ground ground, Corridor corridor, int seaLevel, int waterInset) {
        Set<Integer> members = new HashSet<>(chain);
        Reach first = original.get(chain.get(0)), last = original.get(chain.get(chain.size() - 1));
        for (Piece piece : candidate) {
            Reach r = piece.reach();
            for (double t : new double[] {0, .25, .5, .75, 1}) {
                double x = lerp(r.x1(), r.x2(), t), z = lerp(r.z1(), r.z2(), t);
                double water = Math.floor(lerp(r.y1(), r.y2(), t) - waterInset);
                double radius = lerp(r.w1(), r.w2(), t) + 1.0;
                double surface = ground.height((int) Math.floor(x), (int) Math.floor(z));
                Reach nativeReach = original.get(piece.sourceIndex());
                double nativeT = project(x, z, nativeReach);
                double nativeX = lerp(nativeReach.x1(), nativeReach.x2(), nativeT);
                double nativeZ = lerp(nativeReach.z1(), nativeReach.z2(), nativeT);
                double nativeWater = Math.floor(lerp(nativeReach.y1(), nativeReach.y2(), nativeT) - waterInset);
                double nativeSurface = ground.height((int) Math.floor(nativeX), (int) Math.floor(nativeZ));
                if (!Double.isFinite(surface) || !Double.isFinite(nativeSurface) || water < seaLevel + 1
                        || surface < water + 1.0 - EPSILON || nativeSurface - nativeWater + 2.0 + EPSILON < surface - water
                        || !corridor.allows(x, z, radius)) return false;
                double tx = r.x2() - r.x1(), tz = r.z2() - r.z1();
                double norm = Math.hypot(tx, tz);
                if (norm <= EPSILON) return false;
                double bx = -tz / norm * radius, bz = tx / norm * radius;
                if (!bankSafe(x + bx, z + bz, water, ground) || !bankSafe(x - bx, z - bz, water, ground)) return false;
                for (int i = 0; i < original.size(); i++) if (!members.contains(i)) {
                    Reach other = original.get(i);
                    // The immediately connected native pieces share a pinned
                    // endpoint; their local junction envelope is intentional.
                    double clearance = radius + Math.max(other.w1(), other.w2()) + MAX_OFFSET;
                    if ((atStart.contains(i) && Math.hypot(x - first.x1(), z - first.z1()) <= clearance)
                            || (atEnd.contains(i) && Math.hypot(x - last.x2(), z - last.z2()) <= clearance)) continue;
                    // Reserve another possible bend's maximum movement too.
                    // Independently accepted runs cannot approach one another
                    // merely because each was checked against the native graph.
                    if (nearBox(x, z, clearance, other)
                            && distanceToSegmentSquared(x, z, other.x1(), other.z1(), other.x2(), other.z2())
                            < clearance * clearance) return false;
                }
            }
        }
        // Projection along the run's chord is strictly monotone, so this
        // generated curve cannot cross itself. Adjacent wet pieces may overlap.
        return true;
    }

    private static boolean bankSafe(double x, double z, double water, Ground ground) {
        double bank = ground.height((int) Math.floor(x), (int) Math.floor(z));
        return Double.isFinite(bank) && bank >= water + 1.0 - EPSILON;
    }
    /** Native curves may contain many half-block records at one pinned join. */
    private static Set<Integer> junctionEnvelope(double x, double z, List<Reach> input, Map<NodeKey, Node> nodes) {
        Set<Integer> result = new HashSet<>();
        Set<NodeKey> visited = new HashSet<>();
        var queue = new ArrayDeque<NodeKey>();
        queue.add(key(x, z));
        double radius = 2 * MAX_HALF_WIDTH + MAX_OFFSET + 1;
        while (!queue.isEmpty()) {
            NodeKey at = queue.removeFirst();
            if (!visited.add(at) || Math.hypot(at.x - x, at.z - z) > radius) continue;
            Node node = nodes.get(at);
            for (List<Integer> edges : List.of(node.incoming, node.outgoing)) for (int index : edges) {
                result.add(index);
                Reach reach = input.get(index);
                queue.add(key(reach.x1(), reach.z1()));
                queue.add(key(reach.x2(), reach.z2()));
            }
        }
        return result;
    }
    private static boolean point(double x1, double z1, double x2, double z2) { return Math.abs(x1-x2) <= EPSILON && Math.abs(z1-z2) <= EPSILON; }
    private static boolean nearBox(double x, double z, double radius, Reach r) { return x >= Math.min(r.x1(),r.x2())-radius && x <= Math.max(r.x1(),r.x2())+radius && z >= Math.min(r.z1(),r.z2())-radius && z <= Math.max(r.z1(),r.z2())+radius; }
    private static double project(double x, double z, Reach r) { double dx=r.x2()-r.x1(), dz=r.z2()-r.z1(), d=dx*dx+dz*dz; return d <= EPSILON ? 0 : Math.max(0, Math.min(1, ((x-r.x1())*dx+(z-r.z1())*dz)/d)); }

    private static double offset(double t, double amplitude) { return amplitude * Math.sin(Math.PI * t) * Math.sin(Math.PI * t); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double length(Reach r) { return Math.hypot(r.x2() - r.x1(), r.z2() - r.z1()); }
    private static List<Piece> identities(List<Reach> input) { List<Piece> out = new ArrayList<>(); for (int i=0;i<input.size();i++) out.add(new Piece(i,input.get(i))); return List.copyOf(out); }
    private static Node node(Map<NodeKey, Node> nodes, double x, double z) { return nodes.computeIfAbsent(key(x,z), ignored -> new Node()); }
    private static NodeKey key(double x, double z) { return new NodeKey(x,z); }
    private static boolean finite(Reach r) { return Double.isFinite(r.x1()) && Double.isFinite(r.z1()) && Double.isFinite(r.y1()) && Double.isFinite(r.w1()) && Double.isFinite(r.x2()) && Double.isFinite(r.z2()) && Double.isFinite(r.y2()) && Double.isFinite(r.w2()) && Double.isFinite(r.speed1()) && Double.isFinite(r.speed2()) && r.w1() >= 0 && r.w2() >= 0; }
    private static long mix(long value) { value ^= value >>> 33; value *= 0xff51afd7ed558ccdL; value ^= value >>> 33; value *= 0xc4ceb9fe1a85ec53L; return value ^ value >>> 33; }
    private static double distanceToSegmentSquared(double x, double z, double ax, double az, double bx, double bz) { double dx=bx-ax,dz=bz-az; double d=dx*dx+dz*dz; double t=d <= EPSILON ? 0 : Math.max(0,Math.min(1,((x-ax)*dx+(z-az)*dz)/d)); double ex=x-(ax+t*dx), ez=z-(az+t*dz); return ex*ex+ez*ez; }
    private record NodeKey(double x, double z) { }
    private static final class Node { final List<Integer> incoming = new ArrayList<>(); final List<Integer> outgoing = new ArrayList<>(); }
}
