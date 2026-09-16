import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;

import java.util.ArrayList;
import java.util.List;

public final class NaturalRiverMorphologyTest {
    private static final NaturalRiverMorphology.Ground FLAT = (x, z) -> 120.0;

    public static void main(String[] args) {
        smoothsGentleKinkWithoutMovingNativeGeometry();
        pinsYJunctionAndEndpoints();
        preservesMonotonicFlowAndCorrectionLimit();
        inputOrderDoesNotChangeGeometry();
        preservesSteepReaches();
        invalidGroundLeavesReachUntouched();
        doesNotMutateInput();
        System.out.println("NaturalRiverMorphologyTest: passed");
    }

    private static void smoothsGentleKinkWithoutMovingNativeGeometry() {
        List<Reach> input = List.of(
                r(0, 0, 100, 4, 10, 0, 99.80, 5),
                r(10, 0, 99.80, 5, 20, 0, 99.20, 6),
                r(20, 0, 99.20, 6, 30, 0, 98.80, 7));
        List<Reach> output = NaturalRiverMorphology.refine(input, FLAT, 63);
        check(output.get(1).y1() < input.get(1).y1(), "gentle high kink should lower");
        check(equal(output.get(0).x1(), input.get(0).x1()) && equal(output.get(2).z2(), input.get(2).z2()),
                "coordinates must remain exact");
        check(equal(output.get(0).w1(), input.get(0).w1()) && equal(output.get(2).w2(), input.get(2).w2()),
                "native widths must remain exact");
    }

    private static void pinsYJunctionAndEndpoints() {
        List<Reach> input = List.of(
                r(-20, 0, 102, 3, -10, 0, 101.8, 3),
                r(-10, 0, 101.8, 3, 0, 0, 101.2, 4),
                r(0, 0, 101.2, 4, 10, 0, 100.9, 5),
                r(0, 10, 103, 3, 0, 0, 101.2, 4),
                r(10, 0, 100.9, 5, 20, 0, 100.5, 6));
        List<Reach> output = NaturalRiverMorphology.refine(input, FLAT, 63);
        check(equal(output.get(2).y1(), 101.2) && equal(output.get(3).y2(), 101.2), "junction grade pinned");
        check(equal(output.get(0).y1(), 102) && equal(output.get(4).y2(), 100.5), "component endpoints pinned");
    }

    private static void preservesMonotonicFlowAndCorrectionLimit() {
        List<Reach> input = List.of(
                r(0, 0, 100, 4, 10, 0, 99.85, 4),
                r(10, 0, 99.85, 4, 20, 0, 99.10, 4),
                r(20, 0, 99.10, 4, 30, 0, 98.80, 4));
        List<Reach> output = NaturalRiverMorphology.refine(input, FLAT, 63);
        for (int i = 0; i < output.size(); i++) {
            Reach before = input.get(i), after = output.get(i);
            check(after.y1() + 1e-9 >= after.y2(), "no uphill flow");
            check(after.y1() >= before.y1() - 1.0000001 && after.y2() >= before.y2() - 1.0000001,
                    "extra cut no more than one block");
        }
    }

    private static void inputOrderDoesNotChangeGeometry() {
        List<Reach> ordered = List.of(
                r(0, 0, 100, 4, 10, 0, 99.8, 4),
                r(10, 0, 99.8, 4, 20, 0, 99.2, 4),
                r(20, 0, 99.2, 4, 30, 0, 98.8, 4));
        List<Reach> shuffled = List.of(ordered.get(2), ordered.get(0), ordered.get(1));
        List<Reach> a = NaturalRiverMorphology.refine(ordered, FLAT, 63);
        List<Reach> b = NaturalRiverMorphology.refine(shuffled, FLAT, 63);
        for (Reach reach : a) check(find(b, reach.x1(), reach.z1(), reach.x2(), reach.z2()).equals(reach), "shuffled graph result differs");
    }

    private static void preservesSteepReaches() {
        List<Reach> input = List.of(
                r(0, 0, 100, 4, 10, 0, 99.0, 4),
                r(10, 0, 99.0, 4, 20, 0, 98.6, 4),
                r(20, 0, 98.6, 4, 30, 0, 98.2, 4));
        List<Reach> output = NaturalRiverMorphology.refine(input, FLAT, 63);
        check(output.equals(input), "steep native grades must remain unchanged");
    }

    private static void invalidGroundLeavesReachUntouched() {
        List<Reach> input = List.of(
                r(0, 0, 100, 4, 10, 0, 99.8, 4), r(10, 0, 99.8, 4, 20, 0, 99.2, 4), r(20, 0, 99.2, 4, 30, 0, 98.8, 4));
        List<Reach> output = NaturalRiverMorphology.refine(input, (x, z) -> Double.NaN, 63);
        check(output.equals(input), "non-finite surface cannot alter a reach");
    }

    private static void doesNotMutateInput() {
        List<Reach> input = new ArrayList<>(List.of(
                r(0, 0, 100, 4, 10, 0, 99.8, 4), r(10, 0, 99.8, 4, 20, 0, 99.2, 4), r(20, 0, 99.2, 4, 30, 0, 98.8, 4)));
        List<Reach> snapshot = List.copyOf(input);
        NaturalRiverMorphology.refine(input, FLAT, 63);
        check(input.equals(snapshot), "input list or reaches mutated");
    }

    private static Reach find(List<Reach> reaches, double x1, double z1, double x2, double z2) {
        return reaches.stream().filter(r -> equal(r.x1(), x1) && equal(r.z1(), z1) && equal(r.x2(), x2) && equal(r.z2(), z2)).findFirst().orElseThrow();
    }
    private static Reach r(double x1, double z1, double y1, double w1, double x2, double z2, double y2, double w2) {
        return new Reach(x1, z1, y1, w1, x2, z2, y2, w2, 1, 1);
    }
    private static boolean equal(double a, double b) { return Double.doubleToLongBits(a) == Double.doubleToLongBits(b); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}