package com.github.xandergos.terraindiffusionmc.world.river;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;
import java.util.*;
import java.util.function.Predicate;

/** Deterministic sparse hierarchy selection; native reaches are never rewritten. */
public final class NaturalRiverHierarchy {
    private static final double EPS = 1.0e-9;
    private NaturalRiverHierarchy() {}
    public record Selection(List<Integer> retainedIndices, int rejectedSources) {}

    public static Selection select(List<Reach> input, Predicate<Reach> protectedReach,
                                   double minTributaryLength, double sourceSpacing) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(protectedReach, "protectedReach");
        if (!validThreshold(minTributaryLength) || !validThreshold(sourceSpacing)) {
            throw new IllegalArgumentException("thresholds must be finite and non-negative");
        }
        for (Reach reach : input) if (reach == null || !finite(reach)) return unchanged(input.size());
        if (input.isEmpty()) return new Selection(List.of(), 0);
        Graph graph = new Graph(input);
        Set<Arc> forced = new HashSet<>();
        Set<Node> seeds = new HashSet<>();
        for (Arc arc : graph.arcs) {
            for (int index : arc.indices) {
                if (protectedReach.test(input.get(index))) {
                    forced.add(arc);
                    seeds.add(arc.from);
                    break;
                }
            }
        }
        Map<Node, Integer> degree = new HashMap<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        for (Node node : graph.nodes.values()) {
            degree.put(node, node.in.size());
            if (node.in.isEmpty()) queue.add(node);
            if (node.out.size() > 1) seeds.add(node);
        }
        while (!queue.isEmpty()) {
            for (Arc arc : queue.remove().out) {
                if (degree.merge(arc.to, -1, Integer::sum) == 0) queue.add(arc.to);
            }
        }
        for (Node node : graph.nodes.values()) if (degree.get(node) > 0) seeds.add(node);
        closeDownstream(seeds, forced);
        Set<Node> targets = targets(graph, forced);
        Route route = routes(graph, targets);
        for (List<Node> component : components(graph)) {
            for (Node node : component) {
                if (!route.distance.containsKey(node)) {
                    seeds.addAll(component);
                    break;
                }
            }
        }
        closeDownstream(seeds, forced);
        targets = targets(graph, forced);
        route = routes(graph, targets);

        List<List<Node>> componentList = components(graph);
        Set<Node> terminals = terminals(graph);
        Route originalRoute = routes(graph, terminals);

        // Complete the non-negotiable protected closure before admitting optional
        // tributaries.  A closure can start at a former interior node; its restored
        // upstream route is real retained geometry and must therefore occupy both
        // the live graph and the spacing index during admission.
        Set<Arc> chosen = new HashSet<>(forced);
        preserveMainstems(componentList, originalRoute, terminals, chosen);
        completeSelectedHeads(graph, chosen);
        Set<Node> live = new HashSet<>(targets);
        for (Arc arc : chosen) { live.add(arc.from); live.add(arc.to); }
        Spatial spatial = new Spatial(sourceSpacing);
        for (Arc arc : chosen) spatial.add(arc);
        int rejected = 0;
        for (List<Node> component : componentList) {
            List<Node> sources = new ArrayList<>();
            for (Node node : component) if (node.in.isEmpty() && route.distance.containsKey(node)) sources.add(node);
            if (sources.isEmpty()) continue;
            final Route stableRoute = route;
            sources.sort((a, b) -> {
                int byDistance = Double.compare(stableRoute.distance.get(b), stableRoute.distance.get(a));
                return byDistance != 0 ? byDistance : a.key.compareTo(b.key);
            });
            for (int i = 0; i < sources.size(); i++) {
                Node source = sources.get(i);
                if (live.contains(source)) continue;
                List<Arc> path = walk(source, route, live);
                double length = 0;
                for (Arc arc : path) length += arc.length;
                if (!path.isEmpty() && length + EPS >= minTributaryLength && spatial.clear(source)) {
                    add(path, chosen, live, spatial);
                } else {
                    rejected++;
                }
            }
        }
        for (Node node : graph.nodes.values()) for (Arc self : node.self) if (forced.contains(self) || live.contains(node)) chosen.add(self);
        boolean[] retained = new boolean[input.size()];
        for (Arc arc : chosen) for (int index : arc.indices) retained[index] = true;
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < retained.length; i++) if (retained[i]) result.add(i);
        return new Selection(List.copyOf(result), rejected);
    }

    private static Set<Node> terminals(Graph graph) {
        Set<Node> result = new HashSet<>();
        for (Node node : graph.nodes.values()) if (node.out.isEmpty()) result.add(node);
        return result;
    }
    /** Retain each component's longest native source-to-terminal shortest route. */
    private static void preserveMainstems(List<List<Node>> componentList, Route originalRoute,
                                          Set<Node> terminals, Set<Arc> chosen) {
        for (List<Node> component : componentList) {
            Node mainstem = null;
            for (Node node : component) {
                if (!node.in.isEmpty() || !originalRoute.distance.containsKey(node)) continue;
                if (mainstem == null
                        || originalRoute.distance.get(node) > originalRoute.distance.get(mainstem) + EPS
                        || (Math.abs(originalRoute.distance.get(node) - originalRoute.distance.get(mainstem)) <= EPS
                            && node.key.compareTo(mainstem.key) < 0)) mainstem = node;
            }
            if (mainstem != null) chosen.addAll(walk(mainstem, originalRoute, terminals));
        }
    }

    private static void closeDownstream(Set<Node> seeds, Set<Arc> forced) {
        ArrayDeque<Node> queue = new ArrayDeque<>(seeds);
        Set<Node> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Node node = queue.remove();
            if (!seen.add(node)) continue;
            for (Arc arc : node.out) {
                forced.add(arc);
                queue.add(arc.to);
            }
        }
    }
    private static Set<Node> targets(Graph graph, Set<Arc> forced) {
        Set<Node> result = new HashSet<>();
        for (Node node : graph.nodes.values()) if (node.out.isEmpty()) result.add(node);
        for (Arc arc : forced) { result.add(arc.from); result.add(arc.to); }
        return result;
    }
    private static List<Arc> walk(Node node, Route route, Set<Node> live) {
        List<Arc> result = new ArrayList<>(); Set<Node> seen = new HashSet<>();
        while (!live.contains(node)) {
            if (!seen.add(node)) return List.of();
            Arc arc = route.next.get(node);
            if (arc == null) return List.of();
            result.add(arc); node = arc.to;
        }
        return result;
    }
    private static void add(List<Arc> path, Set<Arc> chosen, Set<Node> live, Spatial spatial) {
        for (Arc arc : path) {
            if (chosen.add(arc)) spatial.add(arc);
            live.add(arc.from); live.add(arc.to);
        }
    }

    /**
     * A protected downstream closure may otherwise begin at a former interior node.
     * Complete each such head with one original, longest upstream route.  The shared
     * incoming map means overlapping completions stop at the route already retained.
     */
    private static void completeSelectedHeads(Graph graph, Set<Arc> chosen) {
        Map<Node, Arc> longestParent = new HashMap<>();
        Map<Node, Double> upstreamLength = new HashMap<>();
        Map<Node, Integer> remaining = new HashMap<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        for (Node node : graph.nodes.values()) {
            remaining.put(node, node.in.size());
            if (node.in.isEmpty()) {
                upstreamLength.put(node, 0.0);
                queue.add(node);
            }
        }
        while (!queue.isEmpty()) {
            Node node = queue.remove();
            double length = upstreamLength.getOrDefault(node, Double.NEGATIVE_INFINITY);
            for (Arc arc : node.out) {
                if (length > Double.NEGATIVE_INFINITY) {
                    double candidate = length + arc.length;
                    double old = upstreamLength.getOrDefault(arc.to, Double.NEGATIVE_INFINITY);
                    if (candidate > old + EPS || (Math.abs(candidate - old) <= EPS && parentBefore(arc, longestParent.get(arc.to)))) {
                        upstreamLength.put(arc.to, candidate);
                        longestParent.put(arc.to, arc);
                    }
                }
                if (remaining.merge(arc.to, -1, Integer::sum) == 0) queue.add(arc.to);
            }
        }

        Map<Node, Boolean> selectedIncoming = new HashMap<>();
        for (Node node : graph.nodes.values()) selectedIncoming.put(node, false);
        for (Arc arc : chosen) if (arc.from != arc.to) selectedIncoming.put(arc.to, true);
        List<Node> heads = new ArrayList<>();
        for (Arc arc : chosen) {
            if (arc.from != arc.to) { heads.add(arc.from); heads.add(arc.to); }
        }
        heads = new ArrayList<>(new HashSet<>(heads));
        heads.sort(Comparator.comparing(node -> node.key));

        List<List<Node>> componentList = components(graph);
        Map<Node, List<Node>> componentOf = new HashMap<>();
        for (List<Node> component : componentList) for (Node node : component) componentOf.put(node, component);
        Set<List<Node>> failOpen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node head : heads) {
            if (head.in.isEmpty() || selectedIncoming.get(head)) continue;
            Node node = head;
            while (!selectedIncoming.get(node) && !node.in.isEmpty()) {
                Arc parent = longestParent.get(node);
                if (parent == null) {
                    failOpen.add(componentOf.get(node));
                    break;
                }
                chosen.add(parent);
                selectedIncoming.put(node, true);
                node = parent.from;
            }
        }
        if (!failOpen.isEmpty()) {
            for (Arc arc : graph.arcs) {
                if (failOpen.contains(componentOf.get(arc.from))) chosen.add(arc);
            }
        }
    }
    private static boolean parentBefore(Arc a, Arc b) {
        return b == null || a.from.key.compareTo(b.from.key) < 0;
    }

    private record Entry(Node node, double distance) {}
    private record Route(Map<Node, Double> distance, Map<Node, Arc> next) {}
    private static Route routes(Graph graph, Set<Node> targets) {
        Map<Node, Double> distance = new HashMap<>(); Map<Node, Arc> next = new HashMap<>();
        PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator.comparingDouble(Entry::distance).thenComparing(e -> e.node.key));
        for (Node node : targets) { distance.put(node, 0.0); queue.add(new Entry(node, 0.0)); }
        while (!queue.isEmpty()) { Entry entry = queue.remove(); if (entry.distance > distance.getOrDefault(entry.node, Double.POSITIVE_INFINITY) + EPS) continue;
            for (Arc arc : entry.node.in) { double candidate = entry.distance + arc.length, old = distance.getOrDefault(arc.from, Double.POSITIVE_INFINITY);
                if (candidate < old - EPS || (Math.abs(candidate - old) <= EPS && before(arc, next.get(arc.from)))) { distance.put(arc.from, candidate); next.put(arc.from, arc); queue.add(new Entry(arc.from, candidate)); }
            }
        } return new Route(distance, next);
    }
    private static boolean before(Arc a, Arc b) { return b == null || a.to.key.compareTo(b.to.key) < 0; }
    private static List<List<Node>> components(Graph graph) {
        Set<Node> unseen = new HashSet<>(graph.nodes.values());
        List<List<Node>> result = new ArrayList<>();
        while (!unseen.isEmpty()) {
            ArrayDeque<Node> queue = new ArrayDeque<>();
            List<Node> component = new ArrayList<>();
            queue.add(unseen.iterator().next());
            while (!queue.isEmpty()) {
                Node node = queue.remove();
                if (!unseen.remove(node)) continue;
                component.add(node);
                for (Arc arc : node.in) queue.add(arc.from);
                for (Arc arc : node.out) queue.add(arc.to);
            }
            component.sort(Comparator.comparing(node -> node.key));
            result.add(component);
        }
        // Spatial admission is global.  This order prevents HashMap iteration from
        // changing which of two nearby, otherwise independent tributaries survives.
        result.sort(Comparator.comparing(component -> component.get(0).key));
        return result;
    }

    private record Key(long x, long z) implements Comparable<Key> { public int compareTo(Key other) { int c = Long.compare(x, other.x); return c != 0 ? c : Long.compare(z, other.z); } }
    private record ArcKey(Key from, Key to) {}
    private static final class Node { final Key key; final List<Arc> in = new ArrayList<>(), out = new ArrayList<>(), self = new ArrayList<>(); Node(Key key) { this.key = key; } }
    private static final class Arc { final Node from, to; final List<Integer> indices = new ArrayList<>(); double length; Arc(Node from, Node to, double length) { this.from = from; this.to = to; this.length = length; } }
    private static final class Graph { final Map<Key, Node> nodes = new HashMap<>(); final List<Arc> arcs = new ArrayList<>(); Graph(List<Reach> input) { Map<ArcKey, Arc> unique = new HashMap<>(); for (int index = 0; index < input.size(); index++) { Reach r = input.get(index); Node from = node(r.x1(), r.z1()), to = node(r.x2(), r.z2()); ArcKey key = new ArcKey(from.key, to.key); Arc arc = unique.get(key); double length = Math.hypot(r.x2() - r.x1(), r.z2() - r.z1()); if (arc == null) { arc = new Arc(from, to, length); unique.put(key, arc); arcs.add(arc); if (from == to) from.self.add(arc); else { from.out.add(arc); to.in.add(arc); } } else arc.length = Math.max(arc.length, length); arc.indices.add(index); } } Node node(double x, double z) { Key key = new Key(Math.round(x * 16.0), Math.round(z * 16.0)); return nodes.computeIfAbsent(key, Node::new); } }

    /** Exact segment distance, with a bounded grid insertion for unusually long reaches. */
    private static final class Spatial { static final int MAX_CELLS = 256; final double spacing, cell; final Map<Key, List<Arc>> bins = new HashMap<>(); final List<Arc> large = new ArrayList<>(); Spatial(double spacing) { this.spacing = spacing; cell = Math.max(1.0, spacing); } void add(Arc arc) { if (spacing <= 0) return; long x0 = floor(Math.min(x(arc.from), x(arc.to)) / cell), x1 = floor(Math.max(x(arc.from), x(arc.to)) / cell), z0 = floor(Math.min(z(arc.from), z(arc.to)) / cell), z1 = floor(Math.max(z(arc.from), z(arc.to)) / cell); if (x1 - x0 + 1 > MAX_CELLS || z1 - z0 + 1 > MAX_CELLS || (x1 - x0 + 1) * (z1 - z0 + 1) > MAX_CELLS) { large.add(arc); return; } for (long x = x0; x <= x1; x++) for (long z = z0; z <= z1; z++) bins.computeIfAbsent(new Key(x, z), ignored -> new ArrayList<>()).add(arc); } boolean clear(Node node) { if (spacing <= 0) return true; double px = x(node), pz = z(node); long cx = floor(px / cell), cz = floor(pz / cell); Set<Arc> checked = new HashSet<>(); for (long x = cx - 1; x <= cx + 1; x++) for (long z = cz - 1; z <= cz + 1; z++) for (Arc arc : bins.getOrDefault(new Key(x, z), List.of())) if (checked.add(arc) && near(px, pz, arc)) return false; for (Arc arc : large) if (near(px, pz, arc)) return false; return true; } boolean near(double px, double pz, Arc arc) { double ax = x(arc.from), az = z(arc.from), dx = x(arc.to) - ax, dz = z(arc.to) - az, q = dx * dx + dz * dz; double t = q == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / q)); return Math.hypot(px - ax - t * dx, pz - az - t * dz) < spacing; } static long floor(double value) { return (long)Math.floor(value); } static double x(Node node) { return node.key.x / 16.0; } static double z(Node node) { return node.key.z / 16.0; } }
    private static boolean validThreshold(double value) { return Double.isFinite(value) && value >= 0; }
    private static boolean finite(Reach r) { return Double.isFinite(r.x1()) && Double.isFinite(r.z1()) && Double.isFinite(r.y1()) && Double.isFinite(r.w1()) && Double.isFinite(r.x2()) && Double.isFinite(r.z2()) && Double.isFinite(r.y2()) && Double.isFinite(r.w2()) && Double.isFinite(r.speed1()) && Double.isFinite(r.speed2()); }
    private static Selection unchanged(int size) { List<Integer> all = new ArrayList<>(size); for (int i = 0; i < size; i++) all.add(i); return new Selection(List.copyOf(all), 0); }
}
