import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverShallowProfile;

import java.util.List;
import java.util.Random;

public final class NaturalRiverShallowProfileTest {
    private static final NaturalRiverMorphology.Ground HIGH_GROUND = (x, z) -> 100.0;

    public static void main(String[] args) {
        liftsIncisedFlatTributary();
        projectsUphillBumpWithoutWorseningFlow();
        keepsConfluenceCoherent();
        fadesWideDownstreamToZero();
        pinsLakeEndAndSmoothsTransition();
        pinsNanSeam();
        capsHighEndpointsAtLowInteriorSaddle();
        pinsProtectedInteriorCrossing();
        staysBelowSampledGroundClearance();
        doesNotRaiseExistingOvergroundReach();
        invalidInputIsConservative();
        pinsZeroLengthEdges();
        preventsUphillFromInconsistentSharedGrade();
        handlesSourceFreeCycles();
        keepsIntegerBoundaryMonotonicAfterFloor();
        flattensArtificialSteepDropBeforeOutlet();
        keepsGradesIndependentOfInputOrder();
        randomDagPreservesBoundsAndShape();
        System.out.println("NaturalRiverShallowProfileTest: passed");
    }

    private static void liftsIncisedFlatTributary() {
        List<Reach> before = List.of(r(0, 0, 70, 1, 10, 0, 69, 1), r(10, 0, 69, 1, 20, 0, 68, 1));
        List<Reach> after = refine(before, HIGH_GROUND);
        check(after.get(0).y1() > 70, "source did not lift toward high ground");
        check(after.get(0).y2() > 69, "interior tributary did not lift");
        check(after.get(1).y2() == 68, "terminal must remain pinned");
        nonAscending(after);
    }

    private static void projectsUphillBumpWithoutWorseningFlow() {
        List<Reach> before = List.of(r(0, 0, 80, 1, 10, 0, 81, 1));
        List<Reach> after = refine(before, HIGH_GROUND);
        check(after.get(0).y1() == 80 && after.get(0).y2() == 81, "uphill native edge must be pinned");
    }

    private static void keepsConfluenceCoherent() {
        List<Reach> before = List.of(
                r(0, 0, 80, 1, 10, 0, 78, 1), r(0, 10, 80, 1, 10, 0, 78, 1),
                r(10, 0, 78, 1, 20, 0, 76, 1));
        List<Reach> after = refine(before, HIGH_GROUND);
        check(after.get(0).y2() == after.get(1).y2() && after.get(0).y2() == after.get(2).y1(),
                "confluence grade diverged");
        nonAscending(after);
    }

    private static void fadesWideDownstreamToZero() {
        List<Reach> before = List.of(r(0, 0, 80, 1, 10, 0, 78, 4));
        List<Reach> after = refine(before, HIGH_GROUND);
        check(after.get(0).y1() > 80, "narrow node did not lift");
        check(after.get(0).y2() == 78, "four-block half width did not suppress lift");
    }

    private static void pinsLakeEndAndSmoothsTransition() {
        List<Reach> before = List.of(r(0, 0, 80, 1, 10, 0, 79, 1), r(10, 0, 79, 1, 20, 0, 70, 1));
        List<Reach> after = refine(before, (x, z) -> x >= 20 ? Double.NaN : 100.0);
        check(after.get(1).y2() == 70, "NaN lake end changed");
        check((after.get(0).y2() - before.get(0).y2()) - (after.get(1).y2() - before.get(1).y2()) <= 10 * 0.5 + 1e-9,
                "lift transition into pin exceeds smooth cap");
        nonAscending(after);
    }

    private static void pinsNanSeam() {
        List<Reach> before = List.of(r(0, 0, 80, 1, 10, 0, 78, 1));
        List<Reach> after = refine(before, (x, z) -> x == 10 ? Double.NaN : 100.0);
        check(after.get(0).y2() == 78, "NaN seam node changed");
    }

    private static void capsHighEndpointsAtLowInteriorSaddle() {
        List<Reach> before = List.of(r(0, 0, 80, 1, 10, 0, 79, 1));
        List<Reach> after = refine(before, (x, z) -> x == 5 ? 80.0 : 100.0);
        check(after.equals(before), "low interior saddle allowed endpoint uplift");
    }

    private static void pinsProtectedInteriorCrossing() {
        List<Reach> before = List.of(r(0, 0, 80, 1, 10, 0, 79, 1));
        List<Reach> after = refine(before, (x, z) -> x == 5 ? Double.NaN : 100.0);
        check(after.equals(before), "protected interior crossing changed");
    }

    private static void staysBelowSampledGroundClearance() {
        List<Reach> before = List.of(r(0, 0, 80, 1, 10, 0, 79, 1));
        List<Reach> after = refine(before, (x, z) -> 83.0);
        for (int x = 0; x <= 10; x++) {
            double t = x / 10.0;
            double base = before.get(0).y1() + (before.get(0).y2() - before.get(0).y1()) * t;
            double raised = after.get(0).y1() + (after.get(0).y2() - after.get(0).y1()) * t;
            check(base <= 81.0 && raised <= 81.0 + 1e-9, "uplift exceeded sampled ground clearance");
        }
    }

    private static void doesNotRaiseExistingOvergroundReach() {
        List<Reach> before = List.of(r(0, 0, 90, 1, 10, 0, 89, 1));
        check(refine(before, (x, z) -> 85.0).equals(before), "existing overground reach was raised");
    }
    private static void invalidInputIsConservative() {
        List<Reach> badWidth = List.of(r(0, 0, 80, -1, 10, 0, 78, 1));
        check(refine(badWidth, HIGH_GROUND).equals(badWidth), "negative width changed node");
        List<Reach> inconsistent = List.of(r(0, 0, 80, 1, 10, 0, 78, 1), r(10, 0, 77, 1, 20, 0, 76, 1));
        List<Reach> output = refine(inconsistent, HIGH_GROUND);
        check(output.get(0).y2() == 78 && output.get(1).y1() == 77, "inconsistent node changed");
        List<Reach> nonFinite = List.of(r(0, 0, 80, 1, 10, 0, Double.NaN, 1));
        check(refine(nonFinite, HIGH_GROUND).equals(nonFinite), "non-finite input changed");
    }

    private static void preventsUphillFromInconsistentSharedGrade() {
        List<Reach> before = List.of(
                r(0, 0, 90, 1, 10, 0, 80, 1),
                r(10, 0, 79, 1, 30, 0, 78, 1),
                r(30, 0, 78, 1, 50, 0, 77, 1));
        List<Reach> after = refine(before, HIGH_GROUND);
        check(after.get(1).y1() == 79, "inconsistent shared grade was not pinned");
        nonAscending(after);
    }
    private static void pinsZeroLengthEdges() {
        List<Reach> before = List.of(r(0, 0, 80, 1, 0, 0, 79, 1));
        check(refine(before, HIGH_GROUND).equals(before), "zero-length edge changed");
    }

    private static void keepsIntegerBoundaryMonotonicAfterFloor() {
        List<Reach> before = List.of(r(0, 0, 80.0001, 1, 10, 0, 80.0, 1));
        List<Reach> after = refine(before, HIGH_GROUND);
        check(after.get(0).y1() >= after.get(0).y2(), "final 1/64 floor introduced uphill edge");
        check(after.get(0).y1() >= before.get(0).y1(), "final floor lowered source grade");
    }
    private static void flattensArtificialSteepDropBeforeOutlet() {
        List<Reach> before = List.of(
                r(0, 0, 232, 1, 20, 0, 219, 1),
                r(20, 0, 219, 1, 60, 0, 219, 1),
                r(60, 0, 219, 1, 100, 0, 219, 1),
                r(100, 0, 219, 1, 140, 0, 219, 1));
        List<Reach> after = NaturalRiverShallowProfile.refine(before, (x, z) -> x < 20 ? 246.0 : 233.0, 63, 0);
        double downstreamLift = after.get(0).y2() - before.get(0).y2();
        check(downstreamLift >= 9.0, "native steep drop prevented downstream shallow lift");
        nonAscending(after);
        for (int i = 0; i < before.size(); i++) {
            double extraDescent = (after.get(i).y1() - before.get(i).y1())
                    - (after.get(i).y2() - before.get(i).y2());
            double length = Math.hypot(before.get(i).x2() - before.get(i).x1(), before.get(i).z2() - before.get(i).z1());
            check(extraDescent <= length * 0.5 + 1.0 / 64.0 + 1e-9,
                    "additional descent exceeded asymmetric smooth cap");
        }
    }
    private static void handlesSourceFreeCycles() {
        List<Reach> before = List.of(
                r(0, 0, 80, 1, 10, 0, 80, 1), r(10, 0, 80, 1, 5, 10, 80, 1),
                r(5, 10, 80, 1, 0, 0, 80, 1));
        List<Reach> after = refine(before, HIGH_GROUND);
        check(after.get(0).y1() > 80, "source-free cycle did not receive bounded lift");
        nonAscending(after);
    }
    private static void keepsGradesIndependentOfInputOrder() {
        Reach first = r(0, 0, 80, 1, 10, 0, 78, 1);
        Reach second = r(10, 0, 78, 1, 20, 0, 76, 1);
        List<Reach> ordered = refine(List.of(first, second), HIGH_GROUND);
        List<Reach> shuffled = refine(List.of(second, first), HIGH_GROUND);
        for (Reach reach : ordered) {
            Reach other = shuffled.stream().filter(candidate -> candidate.x1() == reach.x1()
                    && candidate.z1() == reach.z1() && candidate.x2() == reach.x2() && candidate.z2() == reach.z2())
                    .findFirst().orElseThrow();
            check(other.y1() == reach.y1() && other.y2() == reach.y2(), "input order changed graph grade");
        }
    }
    private static void randomDagPreservesBoundsAndShape() {
        Random random = new Random(0x51A110);
        for (int iteration = 0; iteration < 500; iteration++) {
            int count = 2 + random.nextInt(12);
            Reach[] reaches = new Reach[count - 1];
            double grade = 90 + random.nextInt(30);
            for (int i = 0; i < reaches.length; i++) {
                double next = grade - random.nextInt(4);
                reaches[i] = r(i * 10, 0, grade, 0.5 + random.nextDouble() * 3.5, (i + 1) * 10, 0, next,
                        0.5 + random.nextDouble() * 3.5);
                grade = next;
            }
            List<Reach> before = List.of(reaches);
            List<Reach> after = refine(before, HIGH_GROUND);
            for (int i = 0; i < before.size(); i++) {
                Reach a = before.get(i), b = after.get(i);
                check(b.y1() >= a.y1() && b.y1() - a.y1() <= 12.0 + 1e-9, "source lift out of bounds");
                check(b.y2() >= a.y2() && b.y2() - a.y2() <= 12.0 + 1e-9, "end lift out of bounds");
                check((b.y1() - a.y1()) - (b.y2() - a.y2()) <= 10 * 0.5 + 1.0 / 64.0 + 1e-9,
                        "final-grade quantization exceeded added-descent cap");
                check(b.x1() == a.x1() && b.z1() == a.z1() && b.w1() == a.w1() && b.speed1() == a.speed1(),
                        "source shape changed");
                check(b.x2() == a.x2() && b.z2() == a.z2() && b.w2() == a.w2() && b.speed2() == a.speed2(),
                        "end shape changed");
            }
            nonAscending(after);
        }
    }

    private static List<Reach> refine(List<Reach> input, NaturalRiverMorphology.Ground ground) {
        return NaturalRiverShallowProfile.refine(input, ground, 63, 0);
    }

    private static void nonAscending(List<Reach> reaches) {
        for (Reach reach : reaches) check(reach.y1() + 1e-9 >= reach.y2(), "output introduced uphill edge");
    }

    private static Reach r(double x1, double z1, double y1, double w1, double x2, double z2, double y2, double w2) {
        return new Reach(x1, z1, y1, w1, x2, z2, y2, w2, 1, 1);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}