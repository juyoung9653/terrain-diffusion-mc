package com.github.xandergos.terraindiffusionmc.world.river;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Conservative, graph-wide uplift policy for shallow native river profiles.
 * It only computes grades; callers retain ownership of terrain and block safety.
 */
public final class NaturalRiverShallowProfile {
    private static final double FULL_HALF_WIDTH = 1.5;
    private static final double ZERO_HALF_WIDTH = 4.0;
    private static final double MAX_LIFT = 12.0;
    private static final double EXTRA_DESCENT_PER_BLOCK = 0.5;
    private static final double EPSILON = 1.0e-9;

    private NaturalRiverShallowProfile() {}

    public static List<NaturalRiverMorphology.Reach> refine(List<NaturalRiverMorphology.Reach> input,
                                                              NaturalRiverMorphology.Ground ground,
                                                              int seaLevel, int plannerInset) {
        if (input == null) throw new NullPointerException("input");
        if (ground == null) throw new NullPointerException("ground");
        if (input.isEmpty()) return List.of();

        Map<NodeKey, Node> nodes = new HashMap<>();
        List<Edge> edges = new ArrayList<>(input.size());
        for (int index = 0; index < input.size(); index++) {
            NaturalRiverMorphology.Reach reach = input.get(index);
            if (reach == null || !finite(reach)) return List.copyOf(input);
            Node upstream = node(nodes, reach.x1(), reach.z1());
            Node downstream = node(nodes, reach.x2(), reach.z2());
            upstream.grades.add(reach.y1());
            downstream.grades.add(reach.y2());
            upstream.halfWidths.add(reach.w1());
            downstream.halfWidths.add(reach.w2());
            upstream.outdegree++;
            double length = Math.hypot(reach.x2() - reach.x1(), reach.z2() - reach.z1());
            edges.add(new Edge(upstream, downstream, length, reach.y1(), reach.y2()));
        }

        for (Node node : nodes.values()) configure(node, ground, seaLevel, plannerInset);
        for (Edge edge : edges) {
            if (!(edge.length > EPSILON) || edge.nativeUpstream < edge.nativeDownstream) {
                edge.upstream.pinned = true;
                edge.downstream.pinned = true;
                edge.active = false;
            }
        }
        for (Node node : nodes.values()) {
            if (node.pinned || node.outdegree == 0) node.cap = 0.0;
            else node.cap = liftCap(node);
            node.lift = node.cap;
        }
        applyInteriorCaps(edges, ground, plannerInset);
        for (Node node : nodes.values()) node.lift = node.cap;

        // Dijkstra computes min(cap(source) + path weight) at every node.  This
        // is the pointwise-largest field below all caps and directed difference
        // constraints. Cycles are harmless: every edge weight is non-negative, so a
        // source-free strongly connected component needs no special pinning.
        PriorityQueue<Entry> queue = new PriorityQueue<>((a, b) -> Double.compare(a.value, b.value));
        for (Node node : nodes.values()) queue.add(new Entry(node, node.lift));
        while (!queue.isEmpty()) {
            Entry current = queue.remove();
            if (current.value > current.node.lift) continue;
            for (Edge edge : current.node.edges) {
                if (!edge.active) continue;
                Node next = edge.other(current.node);
                double weight = edge.weightFrom(current.node);
                double candidate = current.node.lift + weight;
                if (candidate < next.lift) {
                    next.lift = candidate;
                    queue.add(new Entry(next, candidate));
                }
            }
        }

        List<NaturalRiverMorphology.Reach> output = new ArrayList<>(input.size());
        for (int index = 0; index < input.size(); index++) {
            NaturalRiverMorphology.Reach reach = input.get(index);
            Edge edge = edges.get(index);
            output.add(new NaturalRiverMorphology.Reach(
                    reach.x1(), reach.z1(), raised(reach.y1(), edge.upstream.lift), reach.w1(),
                    reach.x2(), reach.z2(), raised(reach.y2(), edge.downstream.lift), reach.w2(),
                    reach.speed1(), reach.speed2()));
        }
        return List.copyOf(output);
    }

    private static void configure(Node node, NaturalRiverMorphology.Ground ground, int seaLevel, int plannerInset) {
        node.grade = node.grades.get(0);
        for (double grade : node.grades) if (Double.doubleToLongBits(grade) != Double.doubleToLongBits(node.grade)) node.pinned = true;
        for (double width : node.halfWidths) {
            if (!(width > 0.0)) node.pinned = true;
            node.maxHalfWidth = Math.max(node.maxHalfWidth, width);
        }
        if (node.grade <= (double) seaLevel + plannerInset + 2.0) node.pinned = true;
        if (node.pinned || node.outdegree == 0 || node.maxHalfWidth >= ZERO_HALF_WIDTH) return;
        double height = ground.height((int) Math.floor(node.x), (int) Math.floor(node.z));
        if (!Double.isFinite(height)) {
            node.pinned = true;
            node.groundBlocked = true;
        } else {
            node.groundFinite = true;
            node.target = height - 2.0 + plannerInset;
        }
    }

    private static void applyInteriorCaps(List<Edge> edges, NaturalRiverMorphology.Ground ground, int plannerInset) {
        for (Edge edge : edges) {
            if (!edge.active || (edge.upstream.cap <= 0.0 && edge.downstream.cap <= 0.0)) continue;
            if (edge.upstream.groundBlocked || edge.downstream.groundBlocked) {
                pin(edge);
                continue;
            }
            int steps = (int) Math.ceil(edge.length);
            if (steps <= 1) continue;
            double allowed = Double.POSITIVE_INFINITY;
            for (int step = 1; step < steps; step++) {
                double t = step / (double) steps;
                int x = (int) Math.floor(edge.upstream.x + (edge.downstream.x - edge.upstream.x) * t);
                int z = (int) Math.floor(edge.upstream.z + (edge.downstream.z - edge.upstream.z) * t);
                double height = ground.height(x, z);
                if (!Double.isFinite(height)) {
                    pin(edge);
                    allowed = 0.0;
                    break;
                }
                double baseGrade = edge.nativeUpstream + (edge.nativeDownstream - edge.nativeUpstream) * t;
                allowed = Math.min(allowed, Math.max(0.0, height - 2.0 + plannerInset - baseGrade));
            }
            edge.upstream.cap = Math.min(edge.upstream.cap, allowed);
            edge.downstream.cap = Math.min(edge.downstream.cap, allowed);
        }
    }

    private static void pin(Edge edge) {
        edge.upstream.pinned = true;
        edge.downstream.pinned = true;
        edge.upstream.cap = 0.0;
        edge.downstream.cap = 0.0;
    }
    private static double liftCap(Node node) {
        double widthFade = node.maxHalfWidth <= FULL_HALF_WIDTH ? 1.0
                : Math.max(0.0, (ZERO_HALF_WIDTH - node.maxHalfWidth) / (ZERO_HALF_WIDTH - FULL_HALF_WIDTH));
        return Math.min(MAX_LIFT * widthFade, Math.max(0.0, node.target - node.grade));
    }

    private static double raised(double grade, double lift) {
        if (lift <= EPSILON) return grade;
        double finalGrade = grade + lift;
        if (Math.abs(finalGrade) > Double.MAX_VALUE / 64.0) return finalGrade;
        // One sixty-fourth of a block is the policy precision for stable final grades.
        return Math.max(grade, Math.floor(finalGrade * 64.0) / 64.0);
    }

    private static Node node(Map<NodeKey, Node> nodes, double x, double z) {
        return nodes.computeIfAbsent(new NodeKey(x, z), ignored -> new Node(x, z));
    }

    private static boolean finite(NaturalRiverMorphology.Reach reach) {
        return Double.isFinite(reach.x1()) && Double.isFinite(reach.z1()) && Double.isFinite(reach.y1())
                && Double.isFinite(reach.w1()) && Double.isFinite(reach.x2()) && Double.isFinite(reach.z2())
                && Double.isFinite(reach.y2()) && Double.isFinite(reach.w2())
                && Double.isFinite(reach.speed1()) && Double.isFinite(reach.speed2());
    }

    private record NodeKey(double x, double z) {}

    private static final class Node {
        final double x;
        final double z;
        final List<Double> grades = new ArrayList<>();
        final List<Double> halfWidths = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();
        int outdegree;
        double grade;
        double target;
        double maxHalfWidth;
        double cap;
        double lift;
        boolean pinned;
        boolean groundFinite;
        boolean groundBlocked;

        Node(double x, double z) {
            this.x = x;
            this.z = z;
        }
    }

    private static final class Edge {
        final Node upstream;
        final Node downstream;
        final double length;
        final double nativeUpstream;
        final double nativeDownstream;
        boolean active = true;

        Edge(Node upstream, Node downstream, double length, double nativeUpstream, double nativeDownstream) {
            this.upstream = upstream;
            this.downstream = downstream;
            this.length = length;
            this.nativeUpstream = nativeUpstream;
            this.nativeDownstream = nativeDownstream;
            upstream.edges.add(this);
            downstream.edges.add(this);
        }

        Node other(Node node) { return node == upstream ? downstream : upstream; }

        double weightFrom(Node node) {
            double smooth = length * EXTRA_DESCENT_PER_BLOCK;
            // Native terrain can contain an artificial sharp fall. Preserve the no-uphill
            // limit downstream, but smooth only additional descent in the reverse direction.
            return node == upstream ? nativeUpstream - nativeDownstream : smooth;
        }
    }

    private record Entry(Node node, double value) {}
}