package com.github.xandergos.terraindiffusionmc.world.river;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative, geometry-preserving selection of native river reaches.
 * Invalid thresholds throw {@link IllegalArgumentException}; a null or non-finite reach
 * fails open and returns every input index unchanged. */
public final class NaturalRiverWatersheds {
    private NaturalRiverWatersheds() { }

    public interface Guard {
        boolean protectedReach(Reach r);
        boolean marineTerminal(Reach terminalIncomingReach);
    }

    public record Selection(List<Integer> retainedIndices, int rejectedBasins, int prunedHeadwaters) { }

    public static Selection select(List<Reach> input, Guard guard, double minimumMainstemLength,
                                   double minimumHeadwaterLength) {
        if (input == null || guard == null) throw new NullPointerException(input == null ? "input" : "guard");
        if (!finiteNonnegative(minimumMainstemLength) || !finiteNonnegative(minimumHeadwaterLength))
            throw new IllegalArgumentException("minimum lengths must be finite and non-negative");
        for (Reach r : input) if (r == null || !finite(r)) return unchanged(input.size());
        if (input.isEmpty()) return new Selection(List.of(), 0, 0);

        Graph graph = new Graph(input);
        boolean[] keep = new boolean[input.size()];
        java.util.Arrays.fill(keep, true);
        Map<Integer, Boolean> protectedCache = new HashMap<>();
        Map<Integer, Boolean> marineCache = new HashMap<>();
        int rejected = 0, pruned = 0;
        for (Component component : graph.components()) {
            if (!component.acyclic() || component.terminals().size() != 1) continue;
            Node terminal = component.terminals().get(0);
            if (!component.marine(terminal, input, guard, marineCache)) continue;
            boolean protectedComponent = component.protectedAny(input, guard, protectedCache);
            if (!protectedComponent && component.longestMainstem() < minimumMainstemLength) {
                for (Arc arc : component.arcs) for (int i : arc.indices) keep[i] = false;
                rejected++;
                continue;
            }
            pruned += component.pruneShortLeaves(minimumHeadwaterLength, keep, input, guard, protectedCache);
        }
        List<Integer> retained = new ArrayList<>();
        for (int i = 0; i < keep.length; i++) if (keep[i]) retained.add(i);
        return new Selection(List.copyOf(retained), rejected, pruned);
    }

    private static Selection unchanged(int size) {
        List<Integer> all = new ArrayList<>(size);
        for (int i = 0; i < size; i++) all.add(i);
        return new Selection(List.copyOf(all), 0, 0);
    }
    private static boolean finiteNonnegative(double value) { return Double.isFinite(value) && value >= 0; }
    private static boolean finite(Reach r) {
        return Double.isFinite(r.x1()) && Double.isFinite(r.z1()) && Double.isFinite(r.y1()) && Double.isFinite(r.w1())
                && Double.isFinite(r.x2()) && Double.isFinite(r.z2()) && Double.isFinite(r.y2()) && Double.isFinite(r.w2())
                && Double.isFinite(r.speed1()) && Double.isFinite(r.speed2());
    }

    private static final class Graph {
        final Map<Key, Node> nodes = new HashMap<>(); final List<Arc> arcs = new ArrayList<>();
        Graph(List<Reach> input) {
            Map<Pair, Arc> unique = new HashMap<>();
            for (int i = 0; i < input.size(); i++) {
                Reach r = input.get(i); Node from = node(r.x1(), r.z1()), to = node(r.x2(), r.z2());
                double length = Math.hypot(r.x2() - r.x1(), r.z2() - r.z1());
                Arc arc = unique.computeIfAbsent(new Pair(from.key, to.key), ignored -> {
                    Arc a = new Arc(from, to, length); arcs.add(a); if (from == to) from.self.add(a); return a;
                });
                arc.length = Math.max(arc.length, length);
                arc.indices.add(i);
            }
            for (Arc a : arcs) if (a.from != a.to) { a.from.out.add(a); a.to.in.add(a); }
        }
        Node node(double x, double z) { return nodes.computeIfAbsent(new Key(Math.round(x * 16), Math.round(z * 16)), Node::new); }
        List<Component> components() {
            List<Component> result = new ArrayList<>(); Set<Node> seen = new HashSet<>();
            for (Node start : nodes.values()) if (seen.add(start)) {
                Component c = new Component(); ArrayDeque<Node> todo = new ArrayDeque<>(); todo.add(start);
                while (!todo.isEmpty()) { Node n = todo.remove(); c.nodes.add(n);
                    for (Arc a : n.in) { c.arcs.add(a); if (seen.add(a.from)) todo.add(a.from); }
                    for (Arc a : n.out) { c.arcs.add(a); if (seen.add(a.to)) todo.add(a.to); }
                    // Collapsed self-edges have no directed topology, but belong to this node.
                    c.arcs.addAll(n.self);
                }
                result.add(c);
            } return result;
        }
    }
    private record Key(long x, long z) { }
    private record Pair(Key from, Key to) { }
    private static final class Node { final Key key; final List<Arc> in = new ArrayList<>(), out = new ArrayList<>(), self = new ArrayList<>(); Node(Key key) { this.key = key; } }
    private static final class Arc {
        final Node from, to; double length; final List<Integer> indices = new ArrayList<>();
        Arc(Node from, Node to, double length) { this.from = from; this.to = to; this.length = length; }
        double lengthFor() { return length; }
    }
    private static final class Component {
        final Set<Node> nodes = new HashSet<>(); final Set<Arc> arcs = new HashSet<>();
        List<Node> terminals() { return nodes.stream().filter(n -> n.out.isEmpty()).toList(); }
        boolean protectedAny(List<Reach> input, Guard guard, Map<Integer, Boolean> cache) {
            for (Arc a : arcs) for (int i : a.indices)
                if (cache.computeIfAbsent(i, x -> guard.protectedReach(input.get(x)))) return true;
            return false;
        }
        boolean marine(Node terminal, List<Reach> input, Guard guard, Map<Integer, Boolean> cache) {
            for (Arc a : terminal.in) for (int i : a.indices) {
                if (cache.computeIfAbsent(i, x -> guard.marineTerminal(input.get(x)))) return true;
            }
            return false;
        }
        boolean acyclic() {
            Map<Node, Integer> degree = new HashMap<>(); ArrayDeque<Node> q = new ArrayDeque<>(); int count = 0;
            for (Node n : nodes) { degree.put(n, n.in.size()); if (n.in.isEmpty()) q.add(n); }
            while (!q.isEmpty()) { Node n = q.remove(); count++; for (Arc a : n.out) if (degree.merge(a.to, -1, Integer::sum) == 0) q.add(a.to); }
            return count == nodes.size();
        }
        double longestMainstem() {
            Map<Node, Integer> deg = new HashMap<>(); ArrayDeque<Node> q = new ArrayDeque<>(); Map<Node, Double> best = new HashMap<>();
            for (Node n : nodes) { deg.put(n, n.in.size()); if (n.in.isEmpty()) { q.add(n); best.put(n, 0.0); } }
            while (!q.isEmpty()) { Node n = q.remove(); for (Arc a : n.out) { best.merge(a.to, best.get(n) + a.lengthFor(), Math::max); if (deg.merge(a.to, -1, Integer::sum) == 0) q.add(a.to); } }
            double answer = 0; for (Node n : terminals()) answer = Math.max(answer, best.getOrDefault(n, 0.0)); return answer;
        }
        int pruneShortLeaves(double minimum, boolean[] keep, List<Reach> input, Guard guard, Map<Integer, Boolean> protectedCache) {
            int removed = 0;
            for (Node junction : nodes) if (junction.in.size() >= 2) {
                List<Leaf> leaves = new ArrayList<>();
                for (Arc incoming : junction.in) {
                    Leaf leaf = leaf(incoming);
                    if (leaf != null && leaf.length < minimum && !leaf.protectedAny(input, guard, protectedCache)) leaves.add(leaf);
                }
                if (leaves.size() == junction.in.size()) leaves.remove(leaves.stream().max(Leaf.ORDER).orElseThrow());
                for (Leaf leaf : leaves) { for (Arc a : leaf.arcs) for (int i : a.indices) keep[i] = false; removed++; }
            }
            // Remove collapsed geometry only after its rounded node has lost
            // every retained non-self reach.  A junction self-edge remains.
            for (Node node : nodes) if (!node.self.isEmpty() && disconnected(node, keep))
                for (Arc self : node.self) for (int i : self.indices) keep[i] = false;
            return removed;
        }
        private static boolean disconnected(Node node, boolean[] keep) {
            for (Arc a : node.in) if (kept(a, keep)) return false;
            for (Arc a : node.out) if (kept(a, keep)) return false;
            return true;
        }
        private static boolean kept(Arc arc, boolean[] keep) { for (int i : arc.indices) if (keep[i]) return true; return false; }
        Leaf leaf(Arc last) {
            List<Arc> chain = new ArrayList<>(); double length = 0; Arc current = last;
            while (true) { chain.add(current); length += current.lengthFor(); Node up = current.from;
                if (up.in.isEmpty()) return up.out.size() == 1 ? new Leaf(chain, length, current.from.key) : null;
                if (up.in.size() != 1 || up.out.size() != 1) return null;
                current = up.in.get(0);
            }
        }
    }
    private record Leaf(List<Arc> arcs, double length, Key source) {
        boolean protectedAny(List<Reach> input, Guard guard, Map<Integer, Boolean> cache) {
            for (Arc arc : arcs) {
                for (int i : arc.indices)
                    if (cache.computeIfAbsent(i, x -> guard.protectedReach(input.get(x)))) return true;
                // Cleanup removes self geometry at source/interior nodes when
                // their supporting leaf is removed.  Include only the from
                // side: the last arc's to node is the retained junction.
                for (Arc self : arc.from.self) for (int i : self.indices)
                    if (cache.computeIfAbsent(i, x -> guard.protectedReach(input.get(x)))) return true;
            }
            return false;
        }
        static final Comparator<Leaf> ORDER = Comparator.comparingDouble(Leaf::length).thenComparing(l -> l.source.x).thenComparing(l -> l.source.z);
    }
}
