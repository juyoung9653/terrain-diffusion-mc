package com.github.xandergos.terraindiffusionmc.world.river;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Conservative post-processing for native river graph grades.
 *
 * <p>This deliberately owns neither drainage nor planform geometry.  It only
 * lowers small kinks on unbranched, gently sloped reaches, retaining native
 * coordinates, widths, topology, and all junction/source/outlet grades.</p>
 */
public final class NaturalRiverMorphology {
    private static final double GENTLE_SLOPE = 0.08;
    private static final double MAX_EXTRA_INCISION = 1.0;
    private static final double EPSILON = 1.0e-9;

    private NaturalRiverMorphology() {}

    public record Reach(double x1, double z1, double y1, double w1,
                        double x2, double z2, double y2, double w2,
                        double speed1, double speed2) {}

    @FunctionalInterface
    public interface Ground {
        double height(int x, int z);
    }

    public static List<Reach> refine(List<Reach> input, Ground ground, int seaLevel) {
        if (input == null) throw new NullPointerException("input");
        if (ground == null) throw new NullPointerException("ground");
        if (input.size() < 2) return List.copyOf(input);

        Map<NodeKey, Node> nodes = new HashMap<>();
        for (int index = 0; index < input.size(); index++) {
            Reach reach = input.get(index);
            if (reach == null || !finite(reach)) return List.copyOf(input);
            node(nodes, reach.x1, reach.z1).outgoing.add(index);
            node(nodes, reach.x2, reach.z2).incoming.add(index);
        }
        for (Node node : nodes.values()) {
            node.grade = commonGrade(node, input);
            node.continuous = Double.isFinite(node.grade);
        }

        Map<NodeKey, Double> lowered = new HashMap<>();
        boolean[] consumed = new boolean[input.size()];
        for (int index = 0; index < input.size(); index++) {
            Reach first = input.get(index);
            Node start = nodes.get(new NodeKey(first.x1, first.z1));
            if (consumed[index] || eligible(start)) continue;
            List<Integer> chain = new ArrayList<>();
            int current = index;
            while (!consumed[current]) {
                consumed[current] = true;
                chain.add(current);
                Reach reach = input.get(current);
                Node end = nodes.get(new NodeKey(reach.x2, reach.z2));
                if (!eligible(end)) break;
                current = end.outgoing.get(0);
            }
            smooth(chain, input, nodes, lowered, ground, seaLevel);
        }
        // A component made entirely of degree-one nodes is a cycle.  It has no
        // endpoint that can safely pin a grade, so retain native grades.
        if (lowered.isEmpty()) return List.copyOf(input);

        List<Reach> result = new ArrayList<>(input.size());
        for (Reach reach : input) {
            double y1 = lowered.getOrDefault(new NodeKey(reach.x1, reach.z1), reach.y1);
            double y2 = lowered.getOrDefault(new NodeKey(reach.x2, reach.z2), reach.y2);
            result.add(new Reach(reach.x1, reach.z1, y1, reach.w1,
                    reach.x2, reach.z2, y2, reach.w2, reach.speed1, reach.speed2));
        }
        return List.copyOf(result);
    }

    private static void smooth(List<Integer> chain, List<Reach> input, Map<NodeKey, Node> nodes,
                               Map<NodeKey, Double> lowered, Ground ground, int seaLevel) {
        if (chain.size() < 2) return;
        List<NodeKey> points = new ArrayList<>(chain.size() + 1);
        Reach first = input.get(chain.get(0));
        points.add(new NodeKey(first.x1, first.z1));
        for (int edge : chain) {
            Reach reach = input.get(edge);
            points.add(new NodeKey(reach.x2, reach.z2));
        }
        double[] grades = new double[points.size()];
        double[] distances = new double[points.size()];
        for (int i = 0; i < points.size(); i++) grades[i] = nodes.get(points.get(i)).grade;
        for (int i = 1; i < points.size(); i++) {
            Reach reach = input.get(chain.get(i - 1));
            double length = Math.hypot(reach.x2 - reach.x1, reach.z2 - reach.z1);
            if (!(length > EPSILON)) return;
            double slope = (grades[i - 1] - grades[i]) / length;
            if (slope < -EPSILON || slope >= GENTLE_SLOPE) return;
            distances[i] = distances[i - 1] + length;
        }
        if (grades[0] < grades[grades.length - 1] - EPSILON || distances[distances.length - 1] <= EPSILON) return;

        double[] adjusted = grades.clone();
        double total = distances[distances.length - 1];
        for (int i = 1; i < adjusted.length - 1; i++) {
            double linear = grades[0] + (grades[grades.length - 1] - grades[0]) * distances[i] / total;
            adjusted[i] = Math.max(grades[i] - MAX_EXTRA_INCISION, Math.min(grades[i], linear));
            if (adjusted[i] < seaLevel - EPSILON) return;
            if (adjusted[i] > adjusted[i - 1] + EPSILON) return;
        }
        if (adjusted[adjusted.length - 2] < adjusted[adjusted.length - 1] - EPSILON) return;

        // Ground is sampled only for nodes that would change.  It is a guard
        // against invalid live terrain data; the grade delta itself bounds
        // additional incision to one block.
        for (int i = 1; i < adjusted.length - 1; i++) {
            if (Math.abs(adjusted[i] - grades[i]) <= EPSILON) continue;
            NodeKey point = points.get(i);
            double surface = ground.height((int) Math.floor(point.x), (int) Math.floor(point.z));
            if (!Double.isFinite(surface)
                    || surface - adjusted[i] > surface - grades[i] + MAX_EXTRA_INCISION + EPSILON) return;
        }
        for (int i = 1; i < adjusted.length - 1; i++) {
            if (adjusted[i] < grades[i] - EPSILON) lowered.put(points.get(i), adjusted[i]);
        }
    }

    private static boolean eligible(Node node) {
        return node != null && node.continuous && node.incoming.size() == 1 && node.outgoing.size() == 1;
    }

    private static Node node(Map<NodeKey, Node> nodes, double x, double z) {
        return nodes.computeIfAbsent(new NodeKey(x, z), ignored -> new Node());
    }

    private static double commonGrade(Node node, List<Reach> input) {
        Double grade = null;
        for (int edge : node.incoming) grade = same(grade, input.get(edge).y2);
        for (int edge : node.outgoing) grade = same(grade, input.get(edge).y1);
        return grade == null ? Double.NaN : grade;
    }

    private static Double same(Double existing, double candidate) {
        if (!Double.isFinite(candidate)) return Double.NaN;
        return existing == null || Double.doubleToLongBits(existing) == Double.doubleToLongBits(candidate)
                ? candidate : Double.NaN;
    }

    private static boolean finite(Reach reach) {
        return Double.isFinite(reach.x1) && Double.isFinite(reach.z1) && Double.isFinite(reach.y1)
                && Double.isFinite(reach.w1) && Double.isFinite(reach.x2) && Double.isFinite(reach.z2)
                && Double.isFinite(reach.y2) && Double.isFinite(reach.w2)
                && Double.isFinite(reach.speed1) && Double.isFinite(reach.speed2);
    }

    private record NodeKey(double x, double z) {}

    private static final class Node {
        final List<Integer> incoming = new ArrayList<>();
        final List<Integer> outgoing = new ArrayList<>();
        double grade;
        boolean continuous;
    }
}
