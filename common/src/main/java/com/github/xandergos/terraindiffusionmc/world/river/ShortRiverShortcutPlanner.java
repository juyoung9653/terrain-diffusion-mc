package com.github.xandergos.terraindiffusionmc.world.river;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Ground;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Replaces a short unbranched detour with a straight, roofed mountain chord.
 *
 * <p>It owns no water or block writes.  Endpoints are existing native graph
 * nodes, all discarded pieces are strictly internal to an unbranched chain, and
 * {@link ShortRiverTunnelPlanner} remains the final roof/portal authority.</p>
 */
public final class ShortRiverShortcutPlanner {
    private static final double EPS = 1e-7, SAMPLE = 2, START_STRIDE = 16, TARGET_STRIDE = 16;
    private static final double MIN_WIDTH = .71, MAX_WIDTH = 5, MAX_PATH = 768, MIN_CHORD = 32, MAX_CHORD = 512;
    private static final int MAX_SHORTCUTS = 32;
    private ShortRiverShortcutPlanner() { }

    /** Immutable graph replacement plus the already-approved roof plans. */
    public record Change(List<Integer> replaced, List<Reach> chord, List<ShortRiverTunnelPlanner.Tunnel> tunnels) {
        public Change { replaced=List.copyOf(replaced); chord=List.copyOf(chord); tunnels=List.copyOf(tunnels); }
    }
    public record Result(List<Reach> reaches, List<ShortRiverTunnelPlanner.Tunnel> tunnels, List<Change> changes) {
        public Result { reaches = List.copyOf(reaches); tunnels = List.copyOf(tunnels); changes=List.copyOf(changes); }
        public Result(List<Reach> reaches,List<ShortRiverTunnelPlanner.Tunnel> tunnels) { this(reaches,tunnels,List.of()); }
    }

    public static List<Reach> refine(List<Reach> input, Ground ground,
                                     NaturalRiverPlanform.Corridor corridor, int seaLevel, int waterInset) {
        return plan(input, ground, corridor, seaLevel, waterInset).reaches();
    }

    public static Result plan(List<Reach> input, Ground ground,
                              NaturalRiverPlanform.Corridor corridor, int seaLevel, int waterInset) {
        if (input == null || ground == null || corridor == null) throw new NullPointerException();
        if (input.isEmpty()) return new Result(List.of(), List.of());
        Map<Key, Node> nodes = graph(input);
        if (nodes == null) return new Result(input, List.of());
        Map<Long, Double> heights = new HashMap<>();
        Ground cachedGround = (x,z) -> heights.computeIfAbsent(((long)x << 32) ^ (z & 0xffffffffL),
                ignored -> ground.height(x,z));
        boolean[] seen = new boolean[input.size()];
        List<Candidate> candidates = new ArrayList<>();
        for (int edge = 0; edge < input.size(); edge++) {
            if (!seen[edge] && usable(input.get(edge)) && starts(edge, input, nodes)) {
                candidates.addAll(candidates(trace(edge, input, nodes, seen), cachedGround, corridor, seaLevel, waterInset));
            }
        }
        candidates.sort(Comparator.comparingDouble((Candidate c) -> c.a.x)
                .thenComparingDouble(c -> c.a.z).thenComparingDouble(c -> c.b.x).thenComparingDouble(c -> c.b.z));
        List<Candidate> accepted = new ArrayList<>(); Set<Integer> taken = new HashSet<>();
        for (Candidate candidate : candidates) {
            if (accepted.size() == MAX_SHORTCUTS) break;
            if (candidate.edges.stream().anyMatch(taken::contains) || crossesOtherRiver(candidate, input, true)) continue;
            if (accepted.stream().anyMatch(other -> crossesOtherRiver(candidate, other.chord, false))) continue;
            accepted.add(candidate); taken.addAll(candidate.edges);
        }
        if (accepted.isEmpty()) return new Result(input, List.of());
        return compose(input,accepted.stream().map(c->new Change(c.edges,c.chord,c.tunnels)).toList());
    }

    /** Compose only individually admitted proposals; rejected proposals retain their original reaches. */
    public static Result compose(List<Reach> input,List<Change> changes) {
        Map<Integer, Change> anchor = new HashMap<>();
        Set<Integer> taken = new HashSet<>();
        for(Change c:changes) {
            for(int edge:c.replaced) if(edge<0 || edge>=input.size() || !taken.add(edge))
                throw new IllegalArgumentException("Overlapping or invalid shortcut proposal");
            anchor.put(c.replaced.stream().mapToInt(Integer::intValue).min().orElseThrow(),c);
        }
        List<Reach> output = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            Change c = anchor.get(i);
            if (c != null) output.addAll(c.chord);
            if (!taken.contains(i)) output.add(input.get(i));
        }
        List<ShortRiverTunnelPlanner.Tunnel> tunnels = new ArrayList<>();
        for (Change candidate : changes) tunnels.addAll(candidate.tunnels);
        return new Result(output, tunnels,changes);
    }

    private static List<Candidate> candidates(Path path, Ground ground, NaturalRiverPlanform.Corridor corridor,
                                               int sea, int inset) {
        List<Candidate> result = new ArrayList<>();
        int previousStart = 0;
        for (int a = 0; a < path.nodes.size() - 1; a++) {
            if (a > 0 && path.nodes.get(a).distance - path.nodes.get(previousStart).distance < START_STRIDE - EPS) continue;
            previousStart = a;
            int previousTarget = a;
            for (int b = a + 1; b < path.nodes.size(); b++) {
                double span = path.nodes.get(b).distance - path.nodes.get(a).distance;
                if (span > MAX_PATH + EPS) break;
                if (b > a + 1 && path.nodes.get(b).distance - path.nodes.get(previousTarget).distance < TARGET_STRIDE - EPS
                        && b != path.nodes.size() - 2) continue;
                previousTarget = b;
                Candidate candidate = candidate(path, a, b, ground, corridor, sea, inset);
                if (candidate != null) result.add(candidate);
            }
        }
        return result;
    }

    private static Candidate candidate(Path path, int first, int last, Ground ground,
                                       NaturalRiverPlanform.Corridor corridor, int sea, int inset) {
        Point a = path.nodes.get(first), b = path.nodes.get(last);
        double pathLength = b.distance - a.distance, chordLength = Math.hypot(b.x - a.x, b.z - a.z);
        if (chordLength < MIN_CHORD || chordLength > MAX_CHORD || pathLength > MAX_PATH
                || chordLength / pathLength < .45 || chordLength / pathLength > .95) return null;
        double waterA = ShortRiverTunnelPlanner.physicalWater(a.y, a.width, inset);
        double waterB = ShortRiverTunnelPlanner.physicalWater(b.y, b.width, inset);
        if (waterA < sea + 1 || waterB < sea + 1 || waterA - waterB < 1
                || (waterA - waterB) / chordLength > .08 + EPS) return null;
        if (!portal(a, waterA, ground) || !portal(b, waterB, ground)) return null;
        Reach whole = new Reach(a.x, a.z, a.y, a.width, b.x, b.z, b.y, b.width, a.speed, b.speed);
        if (!chordCovered(whole, ground, waterA, waterB)) return null;
        // This repeats the production roof and apron checks rather than making
        // a shortcut-specific approximation of caves or bank thickness.
        List<ShortRiverTunnelPlanner.Tunnel> tunnels = ShortRiverTunnelPlanner.plan(List.of(whole), ground, corridor, sea, inset);
        // A shortcut replaces the whole native detour, so it must be a mountain
        // crossing for essentially its whole length.  A short buried patch used
        // to admit a long exposed chord; Streams then carved that chord as a
        // visibly artificial straight surface channel.  The shared tunnel
        // planner may leave up to sixteen blocks at either physical portal open
        // (and may trim that same amount during native-column verification), but
        // no extra exposed approach is valid for a shortcut.
        if (tunnels.size() != 1 || !roofCoversChord(whole, tunnels.getFirst())) return null;
        List<Integer> edges = new ArrayList<>();
        for (int i = first; i < last; i++) edges.add(path.edges.get(i));
        return new Candidate(a, b, List.copyOf(edges), subdivide(whole), tunnels);
    }

    /**
     * The only exposed part of a shortcut is its physical portal apron.  The
     * portal-to-mask gap and the open part inside the mask consume the same
     * sixteen-block allowance; treating them as separate allowances would
     * recreate a 32-block exposed trench after native roof fitting.
     */
    public static boolean roofCoversChord(Reach chord, ShortRiverTunnelPlanner.Tunnel tunnel) {
        if (chord == null || tunnel == null) return false;
        ShortRiverTunnelPlanner.Point entry = tunnel.centreline().getFirst();
        ShortRiverTunnelPlanner.Point exit = tunnel.centreline().getLast();
        double startGap = Math.hypot(entry.x() - chord.x1(), entry.z() - chord.z1());
        double endGap = Math.hypot(exit.x() - chord.x2(), exit.z() - chord.z2());
        return startGap + tunnel.coveredStart() <= 16 + EPS
                && endGap + tunnel.length() - tunnel.coveredEnd() <= 16 + EPS;
    }

    private static boolean crossesOtherRiver(Candidate candidate, List<Reach> reaches, boolean original) {
        Set<Integer> removed = original ? new HashSet<>(candidate.edges) : Set.of();
        Set<Integer> sourceMouth = original ? mouthNeighborhood(candidate.a, reaches, removed) : Set.of();
        Set<Integer> destinationMouth = original ? mouthNeighborhood(candidate.b, reaches, removed) : Set.of();
        double length = Math.hypot(candidate.b.x-candidate.a.x, candidate.b.z-candidate.a.z);
        // Check the whole chord at native two-block resolution. An original
        // reach incident to an existing endpoint is harmless only near that
        // endpoint; the untouched graph still owns those mouths.
        for (double along = 0; along <= length + EPS; along += SAMPLE) {
            double t=along/length, x=lerp(candidate.a.x,candidate.b.x,t), z=lerp(candidate.a.z,candidate.b.z,t);
            double width=lerp(candidate.a.width,candidate.b.width,t);
            for (int i=0;i<reaches.size();i++) {
                if (removed.contains(i)) continue;
                Reach r=reaches.get(i);
                if (along <= 16 + EPS && sourceMouth.contains(i)) continue;
                if (length - along <= 16 + EPS && destinationMouth.contains(i)) continue;
                double dx=r.x2()-r.x1(), dz=r.z2()-r.z1(), squared=dx*dx+dz*dz;
                if (squared<=EPS) continue;
                double u=Math.max(0,Math.min(1,((x-r.x1())*dx+(z-r.z1())*dz)/squared));
                if (Math.hypot(x-lerp(r.x1(),r.x2(),u),z-lerp(r.z1(),r.z2(),u))
                        < width+lerp(r.w1(),r.w2(),u)+4) return true;
            }
        }
        return false;
    }

    private static Set<Integer> mouthNeighborhood(Point mouth, List<Reach> reaches, Set<Integer> removed) {
        Map<Key, List<Integer>> incident = new HashMap<>();
        for (int i = 0; i < reaches.size(); i++) {
            if (removed.contains(i)) continue;
            Reach reach = reaches.get(i);
            incident.computeIfAbsent(key(reach.x1(), reach.z1()), ignored -> new ArrayList<>()).add(i);
            incident.computeIfAbsent(key(reach.x2(), reach.z2()), ignored -> new ArrayList<>()).add(i);
        }
        Map<Key, Double> distance = new HashMap<>();
        PriorityQueue<Visit> pending = new PriorityQueue<>(Comparator.comparingDouble(Visit::distance));
        Key start = key(mouth.x, mouth.z);
        distance.put(start, 0.0); pending.add(new Visit(start, 0));
        Set<Integer> nearby = new HashSet<>();
        while (!pending.isEmpty()) {
            Visit visit = pending.remove();
            if (visit.distance > distance.get(visit.node) + EPS || visit.distance > 16 + EPS) continue;
            for (int edge : incident.getOrDefault(visit.node, List.of())) {
                nearby.add(edge);
                Reach reach = reaches.get(edge);
                Key next = key(reach.x1(), reach.z1()).equals(visit.node)
                        ? key(reach.x2(), reach.z2()) : key(reach.x1(), reach.z1());
                double nextDistance = visit.distance + length(reach);
                if (nextDistance <= 16 + EPS && nextDistance + EPS < distance.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    distance.put(next, nextDistance); pending.add(new Visit(next, nextDistance));
                }
            }
        }
        return nearby;
    }

    private static boolean chordCovered(Reach r, Ground ground, double waterA, double waterB) {
        double length = length(r); int steps = Math.max(1, (int) Math.ceil(length / SAMPLE));
        double covered = 0, longest = 0;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps, x = lerp(r.x1(), r.x2(), t), z = lerp(r.z1(), r.z2(), t);
            double water = lerp(waterA, waterB, t), height = ground.height(floor(x), floor(z));
            if (!Double.isFinite(height) || height < water - 3 - EPS) return false;
            if (height >= water + 9 - EPS) covered += i == 0 ? 0 : length / steps;
            else { longest = Math.max(longest, covered); covered = 0; }
        }
        return Math.max(longest, covered) >= 8 - EPS;
    }

    private static List<Reach> subdivide(Reach r) {
        int count = Math.max(1, (int) Math.ceil(length(r) / SAMPLE)); List<Reach> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double a = (double) i / count, b = (double) (i + 1) / count;
            out.add(new Reach(lerp(r.x1(), r.x2(), a), lerp(r.z1(), r.z2(), a), lerp(r.y1(), r.y2(), a), lerp(r.w1(), r.w2(), a),
                    lerp(r.x1(), r.x2(), b), lerp(r.z1(), r.z2(), b), lerp(r.y1(), r.y2(), b), lerp(r.w1(), r.w2(), b),
                    lerp(r.speed1(), r.speed2(), a), lerp(r.speed1(), r.speed2(), b)));
        }
        return List.copyOf(out);
    }

    private static Path trace(int first, List<Reach> input, Map<Key, Node> graph, boolean[] seen) {
        List<Point> points = new ArrayList<>(); List<Integer> edges = new ArrayList<>(); int edge = first; double distance = 0;
        while (!seen[edge] && usable(input.get(edge))) {
            seen[edge] = true; edges.add(edge); Reach r = input.get(edge);
            if (points.isEmpty()) points.add(new Point(r.x1(), r.z1(), r.y1(), r.w1(), r.speed1(), distance));
            distance += length(r); points.add(new Point(r.x2(), r.z2(), r.y2(), r.w2(), r.speed2(), distance));
            Node end = graph.get(key(r.x2(), r.z2()));
            if (end.in.size() != 1 || end.out.size() != 1) break;
            edge = onlyOut(input, end);
            if (edge < 0) break;
        }
        return new Path(List.copyOf(points), List.copyOf(edges));
    }

    private static Map<Key, Node> graph(List<Reach> input) {
        Map<Key, Node> nodes = new HashMap<>();
        for (int i = 0; i < input.size(); i++) { Reach r = input.get(i); if (!finite(r)) return null;
            node(nodes, r.x1(), r.z1()).out.add(i); node(nodes, r.x2(), r.z2()).in.add(i); }
        return nodes;
    }
    private static boolean starts(int edge, List<Reach> input, Map<Key, Node> nodes) {
        Node node = nodes.get(key(input.get(edge).x1(), input.get(edge).z1()));
        return node.in.size() != 1 || node.out.size() != 1 || !usable(input.get(node.in.get(0)));
    }
    private static boolean portal(Point p, double water, Ground ground) { double h = ground.height(floor(p.x), floor(p.z)); return Double.isFinite(h) && h >= water - 3 - EPS && h <= water + 3 + EPS; }
    private static boolean usable(Reach r) { return finite(r) && r.w1() >= MIN_WIDTH && r.w2() >= MIN_WIDTH && r.w1() <= MAX_WIDTH && r.w2() <= MAX_WIDTH && length(r) > EPS; }
    private static boolean finite(Reach r) { return r != null && Double.isFinite(r.x1())&&Double.isFinite(r.z1())&&Double.isFinite(r.y1())&&Double.isFinite(r.w1())&&Double.isFinite(r.x2())&&Double.isFinite(r.z2())&&Double.isFinite(r.y2())&&Double.isFinite(r.w2())&&Double.isFinite(r.speed1())&&Double.isFinite(r.speed2()); }
    private static int validIn(List<Reach> input, Node n) { int c=0; for(int i:n.in) if(usable(input.get(i)))c++; return c; }
    private static int validOut(List<Reach> input, Node n) { int c=0; for(int i:n.out) if(usable(input.get(i)))c++; return c; }
    private static int onlyOut(List<Reach> input, Node n) { for(int i:n.out)if(usable(input.get(i)))return i; return -1; }
    private static Node node(Map<Key,Node> map,double x,double z){return map.computeIfAbsent(key(x,z),ignored->new Node());}
    private static Key key(double x,double z){return new Key(x,z);} private static int floor(double x){return (int)Math.floor(x);} private static double length(Reach r){return Math.hypot(r.x2()-r.x1(),r.z2()-r.z1());} private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    private record Key(double x,double z){} private record Visit(Key node,double distance){} private record Point(double x,double z,double y,double width,double speed,double distance){} private record Path(List<Point> nodes,List<Integer> edges){} private record Candidate(Point a,Point b,List<Integer> edges,List<Reach> chord,List<ShortRiverTunnelPlanner.Tunnel> tunnels){} private static final class Node{final List<Integer>in=new ArrayList<>(),out=new ArrayList<>();}
}
