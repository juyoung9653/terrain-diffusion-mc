package com.github.xandergos.terraindiffusionmc.world.river;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * Width-only tributary response; squared native width is an area proxy, not measured discharge.
 * Growth is evaluated on native segments, so subdividing them can slightly change display clamps.
 * Protected approaches deliberately retain native endpoint differences to meet unchanged outlets.
 */
public final class NaturalRiverConfluenceWidths {
    private static final double SEED = .65;
    private static final double NATIVE_SHARE = .10;
    private static final double SLOW = .001;
    private static final double FAST = .02;
    private static final double PROTECT_DISTANCE = 128;
    private static final Comparator<Node> NODE_ORDER = Comparator.comparingDouble((Node n) -> n.key.x)
            .thenComparingDouble(n -> n.key.z);

    private NaturalRiverConfluenceWidths() { }

    public static List<NaturalRiverMorphology.Reach> refine(List<NaturalRiverMorphology.Reach> input,
                                                            Predicate<NaturalRiverMorphology.Reach> protectedReach) {
        Objects.requireNonNull(input);
        Objects.requireNonNull(protectedReach);
        Map<Key, Node> nodes = new HashMap<>();
        List<Edge> edges = new ArrayList<>(input.size());
        for (var reach : input) {
            if (reach == null || !finite(reach)) return List.copyOf(input);
            Node a = nodes.computeIfAbsent(new Key(reach.x1(), reach.z1()), Node::new);
            Node b = nodes.computeIfAbsent(new Key(reach.x2(), reach.z2()), Node::new);
            Edge edge = new Edge(reach, a, b, protectedReach.test(reach));
            edges.add(edge);
            a.out.add(edge);
            b.in.add(edge);
        }
        // A canonical summation order makes serialized edge ordering irrelevant.
        Comparator<Edge> incomingOrder = Comparator.comparing((Edge e) -> e.a, NODE_ORDER)
                .thenComparingDouble(e -> e.original.w1()).thenComparingDouble(e -> e.original.w2());
        for (Node n : nodes.values()) {
            n.in.sort(incomingOrder);
            // A bifurcation has no reliable proxy split; preserve its native exits.
            if (n.out.size() > 1) for (Edge e : n.out) e.protectedEdge = true;
        }
        List<Component> components = components(nodes);
        List<Node> order = topologicalOrder(nodes);
        for (Node n : order) n.component.ordered++;
        for (Component c : components) c.invalid |= c.ordered != c.nodes.size();
        for (Edge e : edges) {
            if (e.length <= 0 || e.original.w1() < 0 || e.original.w2() < 0) e.a.component.invalid = true;
        }
        for (Node n : order) {
            if (n.component.invalid) continue;
            double area = 0, displayed = 0, fastArea = 0, nativeArea = 0, largestNativeArea = 0;
            boolean seeded = !n.in.isEmpty();
            for (Edge e : n.in) {
                area += e.area;
                displayed = Math.max(displayed, e.end);
                seeded &= e.seeded;
                // Persist unfinished join widening, without promoting latent background area.
                fastArea += Math.max(e.fastArea, e.end * e.end);
                double contribution = e.original.w2() * e.original.w2();
                nativeArea += contribution;
                largestNativeArea = Math.max(largestNativeArea, contribution);
            }
            if (n.in.size() > 1) seeded = true;
            double balance = nativeArea > 0 ? (nativeArea - largestNativeArea) / nativeArea : 0;
            double joinStrength = n.in.size() > 1 ? Math.min(1, 4 * balance * balance) : 0;
            for (Edge e : n.out) {
                if (e.protectedEdge) {
                    e.area = e.end * e.end;
                    e.fastArea = e.area;
                    e.seeded = true;
                } else shape(e, n.in.isEmpty(), seeded, area, displayed, fastArea, joinStrength);
            }
        }
        blendDistances(nodes, edges);
        List<NaturalRiverMorphology.Reach> result = new ArrayList<>(edges.size());
        for (Edge e : edges) {
            var r = e.original;
            double a = e.a.component.invalid ? r.w1() : blend(r.w1(), e.start, e.a.distance);
            double b = e.a.component.invalid ? r.w2() : blend(r.w2(), e.end, e.b.distance);
            result.add(new NaturalRiverMorphology.Reach(r.x1(), r.z1(), r.y1(), a,
                    r.x2(), r.z2(), r.y2(), b, r.speed1(), r.speed2()));
        }
        return List.copyOf(result);
    }

    private static void shape(Edge e, boolean source, boolean seeded, double area, double displayed,
                              double fastArea, double joinStrength) {
        var r = e.original;
        e.start = source ? r.w1() : Math.min(r.w1(), displayed);
        if (source) {
            area = Math.max(SEED * SEED, r.w1() * r.w1());
            fastArea = r.w1() * r.w1();
            seeded = r.w1() >= SEED;
        }
        // Meaningful tributary joins recover the native main-river scale. A tiny tributary
        // only restores a proportionate fraction, never the whole suppressed background area.
        if (joinStrength > 0) {
            double joined = e.start + joinStrength * Math.max(0, r.w1() - e.start);
            fastArea = Math.max(fastArea, joined * joined);
            area = Math.max(area, fastArea);
        }
        // Use the native capacity increment, not the entire remaining deficit on every segment.
        double nativeStart = seeded ? r.w1() : Math.max(SEED, r.w1());
        e.area = area + NATIVE_SHARE * Math.max(0, r.w2() * r.w2() - nativeStart * nativeStart);
        e.fastArea = Math.min(e.area, fastArea);
        double length = e.length;
        double start = e.start;
        if (!seeded) {
            if (r.w2() < SEED) {
                e.end = r.w2(); // Preserve the short native source taper, including exact zero.
                return;
            }
            double seedFraction = r.w2() > r.w1() ? (SEED - r.w1()) / (r.w2() - r.w1()) : 0;
            length *= 1 - Math.max(0, Math.min(1, seedFraction));
            start = Math.max(start, SEED);
        }
        e.seeded = true;
        double slowLimit = start + SLOW * length;
        double joinLimit = Math.min(Math.sqrt(e.fastArea), start + FAST * length);
        e.end = Math.max(0, Math.min(r.w2(), Math.min(Math.sqrt(e.area), Math.max(slowLimit, joinLimit))));
    }

    private static List<Component> components(Map<Key, Node> nodes) {
        List<Component> result = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        for (Node root : nodes.values()) {
            if (root.component != null) continue;
            Component c = new Component();
            result.add(c);
            root.component = c;
            queue.add(root);
            while (!queue.isEmpty()) {
                Node n = queue.remove();
                c.nodes.add(n);
                for (Edge e : n.in) attach(e.a, c, queue);
                for (Edge e : n.out) attach(e.b, c, queue);
            }
        }
        return result;
    }

    private static void attach(Node n, Component c, ArrayDeque<Node> queue) {
        if (n.component == null) { n.component = c; queue.add(n); }
    }

    private static List<Node> topologicalOrder(Map<Key, Node> nodes) {
        PriorityQueue<Node> queue = new PriorityQueue<>(NODE_ORDER);
        for (Node n : nodes.values()) { n.remaining = n.in.size(); if (n.remaining == 0) queue.add(n); }
        List<Node> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            Node n = queue.remove();
            order.add(n);
            for (Edge e : n.out) if (--e.b.remaining == 0) queue.add(e.b);
        }
        return order;
    }

    private static void blendDistances(Map<Key, Node> nodes, List<Edge> edges) {
        PriorityQueue<Distance> queue = new PriorityQueue<>(Comparator.comparingDouble(Distance::value));
        for (Node n : nodes.values()) if (n.out.isEmpty()) pin(n, queue);
        for (Edge e : edges) if (e.protectedEdge) { pin(e.a, queue); pin(e.b, queue); }
        while (!queue.isEmpty()) {
            Distance d = queue.remove();
            if (d.value > d.node.distance) continue;
            for (Edge e : d.node.in) relax(e.a, d.value + e.length, queue);
            for (Edge e : d.node.out) relax(e.b, d.value + e.length, queue);
        }
    }

    private static void pin(Node n, PriorityQueue<Distance> queue) {
        if (n.distance != 0) { n.distance = 0; queue.add(new Distance(n, 0)); }
    }

    private static void relax(Node n, double distance, PriorityQueue<Distance> queue) {
        if (distance < n.distance && distance < PROTECT_DISTANCE) {
            n.distance = distance;
            queue.add(new Distance(n, distance));
        }
    }

    private static double blend(double original, double candidate, double distance) {
        if (distance >= PROTECT_DISTANCE) return candidate;
        return Math.max(0, Math.min(original, original + (candidate - original) * distance / PROTECT_DISTANCE));
    }

    private static boolean finite(NaturalRiverMorphology.Reach r) {
        return Double.isFinite(r.x1()) && Double.isFinite(r.z1()) && Double.isFinite(r.y1()) && Double.isFinite(r.w1())
                && Double.isFinite(r.x2()) && Double.isFinite(r.z2()) && Double.isFinite(r.y2()) && Double.isFinite(r.w2())
                && Double.isFinite(r.speed1()) && Double.isFinite(r.speed2());
    }

    private record Key(double x, double z) { }
    private record Distance(Node node, double value) { }
    private static final class Component { final List<Node> nodes = new ArrayList<>(); int ordered; boolean invalid; }
    private static final class Node {
        final Key key;
        final List<Edge> in = new ArrayList<>(), out = new ArrayList<>();
        Component component;
        int remaining;
        double distance = Double.POSITIVE_INFINITY;
        Node(Key key) { this.key = key; }
    }
    private static final class Edge {
        final NaturalRiverMorphology.Reach original;
        final Node a, b;
        final double length;
        boolean protectedEdge, seeded;
        double start, end, area, fastArea;
        Edge(NaturalRiverMorphology.Reach r, Node a, Node b, boolean protect) {
            original = r; this.a = a; this.b = b; protectedEdge = protect;
            length = Math.hypot(r.x2() - r.x1(), r.z2() - r.z1());
            start = r.w1(); end = r.w2();
        }
    }
}
