import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverWatersheds;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class NaturalRiverWatershedsTest {
    public static void main(String[] args) {
        removesOnlySmallCompleteCoastalBasin();
        retainsLargeYAndNativeGeometry();
        prunesShortTwigButKeepsTrunkAndSibling();
        retainsOneOfAllShortSiblings();
        guardsAndUnsafeTopologyFailOpen();
        shuffleAndRoundedParallelTopologyAreStable();
        prunedSelfEdgesDoNotLeaveOrphansAndJunctionSelfEdgesRemain();
        protectedSourceOrInteriorSelfEdgeKeepsSupportingLeaf();
        handlesLargeGraphWithoutQuadraticComponentScan();
        protectedDownstreamDoesNotBlockSafeLeafPruning();
        protectedLeafAndFullyProtectedComponentRemain();
        splitSourceIsNotClassifiedAsLeaf();
        invalidInputFailsOpenAndInvalidLengthsFailClearly();
        parallelMarineClassificationIsOrderIndependent();
        System.out.println("NaturalRiverWatershedsTest: passed");
    }

    private static void removesOnlySmallCompleteCoastalBasin() {
        List<Reach> input = List.of(r(0,0,5,0), r(5,0,10,0), r(100,0,110,0), r(110,0,120,0), r(120,0,130,0));
        NaturalRiverWatersheds.Selection s = select(input, Set.of(10.0), Set.of(), 20, 0);
        check(s.retainedIndices().equals(List.of(2,3,4)) && s.rejectedBasins() == 1, "small marine basin removed whole");
    }

    private static void retainsLargeYAndNativeGeometry() {
        List<Reach> input = List.of(r(-20,0,-10,0), r(-10,0,0,0), r(0,10,0,0), r(0,0,10,0), r(10,0,20,0));
        NaturalRiverWatersheds.Selection s = select(input, Set.of(20.0), Set.of(), 25, 5);
        check(s.retainedIndices().equals(List.of(0,1,2,3,4)), "large Y retained");
        check(input.get(0).w1() == 1.25 && input.get(4).w2() == 2.75, "selection exposes original reach geometry only");
    }

    private static void prunesShortTwigButKeepsTrunkAndSibling() {
        List<Reach> input = List.of(r(-30,0,-10,0),r(-10,0,0,0),r(0,20,0,0),r(0,0,10,0),r(10,0,20,0),r(20,0,30,0));
        NaturalRiverWatersheds.Selection s = select(input, Set.of(30.0), Set.of(), 25, 25);
        check(s.retainedIndices().equals(List.of(0,1,3,4,5)) && s.prunedHeadwaters() == 1, "short side twig removed once");
    }

    private static void retainsOneOfAllShortSiblings() {
        List<Reach> input = List.of(r(-5,0,0,0), r(0,6,0,0), r(0,0,10,0), r(10,0,20,0), r(20,0,30,0));
        NaturalRiverWatersheds.Selection s = select(input, Set.of(30.0), Set.of(), 20, 10);
        check(s.retainedIndices().contains(1) && !s.retainedIndices().contains(0), "longest short sibling survives");
        check(s.retainedIndices().containsAll(List.of(2,3,4)), "downstream trunk survives");
    }

    private static void guardsAndUnsafeTopologyFailOpen() {
        List<Reach> tiny = List.of(r(0,0,5,0), r(5,0,10,0));
        check(select(tiny, Set.of(10.0), Set.of(0), 20, 2).retainedIndices().size() == 2, "protected lake/boundary reach preserves component");
        check(select(tiny, Set.of(), Set.of(), 20, 2).retainedIndices().size() == 2, "unknown terminal preserves component");
        List<Reach> multiple = List.of(r(0,0,10,0),r(10,0,20,0),r(10,0,10,10));
        check(select(multiple, Set.of(20.0, 10.0), Set.of(), 50, 2).retainedIndices().size() == 3, "multiple terminals preserve");
        List<Reach> cycle = List.of(r(0,0,10,0),r(10,0,0,0));
        check(select(cycle, Set.of(), Set.of(), 50, 2).retainedIndices().size() == 2, "cycle preserves");
    }

    private static void shuffleAndRoundedParallelTopologyAreStable() {
        List<Reach> input = List.of(r(0.001,0,9.999,0), r(0.002,0,10.030,0), r(10.001,0,20,0));
        NaturalRiverWatersheds.Selection a = select(input, Set.of(20.0), Set.of(), 20.02, 1);
        List<Reach> shuffled = List.of(input.get(2), input.get(0), input.get(1));
        NaturalRiverWatersheds.Selection b = select(shuffled, Set.of(20.0), Set.of(), 20.02, 1);
        check(a.retainedIndices().size() == 3 && b.retainedIndices().size() == 3, "longest rounded parallel geometry determines threshold independent of order");
        check(new HashSet<>(selected(input,a)).equals(new HashSet<>(selected(shuffled,b))), "shuffling preserves selected geometry");
    }

    private static void prunedSelfEdgesDoNotLeaveOrphansAndJunctionSelfEdgesRemain() {
        List<Reach> input = List.of(
                r(-5,0,0,0), r(-5.001,0.001,-4.999,0.002), // short leaf and its collapsed geometry
                r(0,12,0,0), r(0.001,0.001,0.002,0.002), // longer sibling and junction geometry
                r(0,0,10,0), r(10,0,20,0), r(20,0,30,0));
        NaturalRiverWatersheds.Selection s = select(input, Set.of(30.0), Set.of(), 20, 10);
        check(!s.retainedIndices().contains(0) && !s.retainedIndices().contains(1), "pruned leaf removes its orphan collapsed edge");
        check(s.retainedIndices().contains(3), "collapsed junction edge remains attached to retained trunk");
    }

    private static void protectedSourceOrInteriorSelfEdgeKeepsSupportingLeaf() {
        List<Reach> input = List.of(
                r(-10,0,-5,0), r(-5,0,0,0), r(-10.001,0.001,-9.999,0.002), r(-5.001,0.001,-4.999,0.002),
                r(0,20,0,0), r(0,0,10,0), r(10,0,20,0), r(20,0,30,0));
        for (int protectedSelf : List.of(2, 3)) {
            NaturalRiverWatersheds.Selection s = select(input, Set.of(30.0), Set.of(protectedSelf), 20, 15);
            check(s.retainedIndices().containsAll(List.of(0,1,protectedSelf)),
                    "protected source/interior self placeholder keeps its supporting short leaf");
        }
    }

    private static void handlesLargeGraphWithoutQuadraticComponentScan() {
        int size = 100_000;
        List<Reach> input = new ArrayList<>(size);
        for (int i = 0; i < size; i++) input.add(r(i, 0, i + 1, 0));
        NaturalRiverWatersheds.Selection s = NaturalRiverWatersheds.select(input, new NaturalRiverWatersheds.Guard() {
            @Override public boolean protectedReach(Reach r) { return false; }
            @Override public boolean marineTerminal(Reach r) { return r.x2() == size; }
        }, 10, 0);
        check(s.retainedIndices().size() == size, "100k linear component retained");
    }

    private static void protectedDownstreamDoesNotBlockSafeLeafPruning() {
        List<Reach> input = List.of(r(-5,0,0,0), r(0,20,0,0), r(0,0,10,0), r(10,0,20,0), r(20,0,30,0));
        NaturalRiverWatersheds.Selection s = select(input, Set.of(30.0), Set.of(3), 100, 10);
        check(!s.retainedIndices().contains(0), "protected downstream lake does not block safe short leaf pruning");
        check(s.retainedIndices().containsAll(List.of(1,2,3,4)), "protected downstream and trunk remain");
    }

    private static void protectedLeafAndFullyProtectedComponentRemain() {
        List<Reach> input = List.of(r(-5,0,0,0), r(0,20,0,0), r(0,0,10,0), r(10,0,20,0), r(20,0,30,0));
        check(select(input, Set.of(30.0), Set.of(0), 100, 10).retainedIndices().equals(List.of(0,1,2,3,4)),
                "protected leaf cannot be pruned");
        check(select(input, Set.of(30.0), Set.of(0,1,2,3,4), 100, 10).retainedIndices().equals(List.of(0,1,2,3,4)),
                "fully protected component remains unchanged");
    }

    private static void splitSourceIsNotClassifiedAsLeaf() {
        List<Reach> input = List.of(r(-5,0,0,0), r(-5,0,0,5), r(0,5,0,0), r(0,0,10,0), r(10,0,20,0));
        NaturalRiverWatersheds.Selection s = select(input, Set.of(20.0), Set.of(0), 100, 10);
        check(s.retainedIndices().equals(List.of(0,1,2,3,4)), "split source branches are not prunable leaves");
    }

    private static void invalidInputFailsOpenAndInvalidLengthsFailClearly() {
        List<Reach> bad = List.of(r(0,0,1,0), new Reach(Double.NaN,0,0,1,1,0,0,1,1,1));
        check(select(bad, Set.of(), Set.of(), 2, 1).retainedIndices().equals(List.of(0,1)), "NaN input fails open");
        try { select(List.of(r(0,0,1,0)), Set.of(1.0), Set.of(), Double.NaN, 1); throw new AssertionError("NaN threshold accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static NaturalRiverWatersheds.Selection select(List<Reach> input, Set<Double> marine, Set<Integer> protectedIndices, double main, double head) {
        return NaturalRiverWatersheds.select(input, new NaturalRiverWatersheds.Guard() {
            @Override public boolean protectedReach(Reach r) { return protectedIndices.contains(input.indexOf(r)); }
            @Override public boolean marineTerminal(Reach r) { return marine.contains(r.x2()); }
        }, main, head);
    }
    private static void parallelMarineClassificationIsOrderIndependent() {
        Reach a=r(0,0,10.001,0), b=r(.001,0,10.002,0);
        for (List<Reach> input : List.of(List.of(a,b),List.of(b,a))) {
            NaturalRiverWatersheds.Selection result=select(input,Set.of(10.002),Set.of(),20,0);
            check(result.retainedIndices().isEmpty(), "marine classification considers all rounded parallel geometry");
        }
    }
    private static List<Reach> selected(List<Reach> input, NaturalRiverWatersheds.Selection s) { List<Reach> out = new ArrayList<>(); for (int i : s.retainedIndices()) out.add(input.get(i)); return out; }
    private static Reach r(double x1,double z1,double x2,double z2) { return new Reach(x1,z1,100,1.25,x2,z2,99,2.75,3,4); }
    private static void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); }
}
