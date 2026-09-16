import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverHierarchy;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class NaturalRiverHierarchyTest {
    public static void main(String[] args) {
        malformedAndThresholds(); protectedAndDownstream(); protectedMidreachCompletesOriginalSourcePath(); restoredProtectedPathBlocksNearbyOptionalSource(); longestNativeMainstemSurvivesProtectedShortcut(); cyclesAndMultipleOutlets();
        nativeRoundedParallelAndSelf(); adjacentLongSourcesCompete(); longTributarySurvives(); independentShortMainstemSurvives();
        stableWhenShuffled(); crossComponentSpacingIsStableWhenShuffled(); largeChainsAreLinearEnough();
        System.out.println("NaturalRiverHierarchyTest: passed");
    }
    private static void malformedAndThresholds() {
        List<Reach> bad = List.of(r(0, 0, 1, 0), new Reach(Double.NaN, 0, 0, 1, 1, 0, 0, 1, 1, 1));
        check(select(bad, 1, 1).retainedIndices().equals(List.of(0, 1)), "malformed input fails open");
        try { select(List.of(r(0,0,1,0)), -1, 0); throw new AssertionError("negative threshold accepted"); } catch (IllegalArgumentException expected) { }
    }
    private static void protectedAndDownstream() {
        List<Reach> input = List.of(r(-1,0,0,0), r(-100,0,0,0), r(0,0,10,0), r(10,0,20,0), r(20,0,30,0));
        NaturalRiverHierarchy.Selection s = NaturalRiverHierarchy.select(input, reach -> reach.x1() == 0, 100, 0);
        check(s.retainedIndices().containsAll(List.of(1,2,3,4)) && !s.retainedIndices().contains(0), "protected edge and downstream closure retained");
    }
    private static void protectedMidreachCompletesOriginalSourcePath() {
        List<Reach> input = List.of(r(0,0,10,0), r(10,0,20,0), r(20,0,30,0), r(30,0,40,0));
        NaturalRiverHierarchy.Selection s = NaturalRiverHierarchy.select(input, reach -> reach.x1() == 20, 1_000, 0);
        check(s.retainedIndices().equals(List.of(0,1,2,3)), "protected midreach does not create an artificial source");
    }
    private static void restoredProtectedPathBlocksNearbyOptionalSource() {
        List<Reach> input = List.of(
                r(-100,0,0,0), r(0,0,100,0), r(-50,2,0,0));
        NaturalRiverHierarchy.Selection s = NaturalRiverHierarchy.select(input, reach -> reach.x1() == 0, 1, 5);
        check(s.retainedIndices().equals(List.of(0,1)) && s.rejectedSources() == 1,
                "optional source near a restored protected path is rejected during admission");
    }
    private static void independentShortMainstemSurvives() {
        List<Reach> input = List.of(r(0,0,5,0));
        check(select(input, 10, 5).retainedIndices().equals(List.of(0)),
                "an independent component keeps its native mainstem below tributary length");
    }
    private static void longestNativeMainstemSurvivesProtectedShortcut() {
        List<Reach> input = List.of(
                r(-100,0,0,0), r(0,0,10,0), r(-10,10,10,0), r(5,20,10,0));
        NaturalRiverHierarchy.Selection s = NaturalRiverHierarchy.select(input,
                reach -> reach.x1() == -10 && reach.z1() == 10, 50, 0);
        check(s.retainedIndices().equals(List.of(0,1,2)) && s.rejectedSources() == 1,
                "the longest native mainstem survives a protected shortcut and a short optional source rejects");
    }
    private static void cyclesAndMultipleOutlets() {
        List<Reach> cycle = List.of(r(0,0,10,0), r(10,0,0,0), r(10,0,20,0));
        check(select(cycle, 100, 5).retainedIndices().equals(List.of(0,1,2)), "cycle closure retained");
        List<Reach> split = List.of(r(0,0,10,0), r(10,0,20,0), r(10,0,10,10));
        check(select(split, 100, 5).retainedIndices().equals(List.of(0,1,2)), "divergent multi-outlet closure retained");
    }
    private static void nativeRoundedParallelAndSelf() {
        List<Reach> input = List.of(r(-.03125,0,0,0), r(-.0312,.001,-.030,0), r(0,0,10,0), r(.001,.001,.002,.002));
        NaturalRiverHierarchy.Selection s = select(input, 100, 0);
        check(s.retainedIndices().containsAll(List.of(0,1,2,3)), "Math.round negative endpoint, parallel originals, and live self retained");
    }
    private static void adjacentLongSourcesCompete() {
        List<Reach> input = List.of(r(-100,0,0,0), r(-99,4,0,0), r(0,0,100,0));
        NaturalRiverHierarchy.Selection s = select(input, 10, 8);
        check(s.retainedIndices().containsAll(List.of(0,2)) && !s.retainedIndices().contains(1) && s.rejectedSources() == 1, "nearby source rejected after primary geometry");
    }
    private static void longTributarySurvives() {
        List<Reach> input = List.of(r(-50,0,0,0), r(0,30,0,0), r(0,0,50,0));
        NaturalRiverHierarchy.Selection s = select(input, 20, 5);
        check(s.retainedIndices().equals(List.of(0,1,2)), "long remaining tributary retained");
    }
    private static void stableWhenShuffled() {
        List<Reach> input = List.of(r(-30,0,0,0), r(-29,8,0,0), r(0,0,30,0));
        List<Reach> shuffled = List.of(input.get(2), input.get(1), input.get(0));
        Set<String> first = shapes(input, select(input, 10, 10)); Set<String> second = shapes(shuffled, select(shuffled, 10, 10));
        check(first.equals(second), "selection is stable under input shuffle");
    }
    private static void crossComponentSpacingIsStableWhenShuffled() {
        List<Reach> input = List.of(
                r(0,0,100,0), r(100,30,100,0), r(100,0,105,0),
                r(110,-100,110,0), r(110,0,200,0), r(110,30,200,0));
        List<Reach> shuffled = List.of(input.get(5), input.get(2), input.get(0), input.get(4), input.get(1), input.get(3));
        NaturalRiverHierarchy.Selection firstSelection = select(input, 1, 25);
        NaturalRiverHierarchy.Selection secondSelection = select(shuffled, 1, 25);
        Set<String> first = shapes(input, firstSelection);
        Set<String> second = shapes(shuffled, secondSelection);
        check(first.equals(second), "global cross-component spacing is stable under shuffle");
        check(first.contains("100.0,30.0:100.0,0.0") && !first.contains("110.0,30.0:200.0,0.0"),
                "lowest-coordinate component wins nearby secondary-source competition");
    }
    private static void largeChainsAreLinearEnough() {
        int count = 100_000; List<Reach> chain = new ArrayList<>(count);
        for (int i = 0; i < count; i++) chain.add(r(i, 0, i + 1, 0));
        long started = System.nanoTime();
        check(select(chain, 1, 0).retainedIndices().size() == count, "100k chain retained");
        check(NaturalRiverHierarchy.select(chain, reach -> reach.x1() == 0, 1, 0).retainedIndices().size() == count, "100k protected closure retained");
        check(NaturalRiverHierarchy.select(chain, reach -> reach.x1() == count / 2, 1_000_000, 0).retainedIndices().size() == count,
                "100k protected tail completes one original source path");
        check((System.nanoTime() - started) < 15_000_000_000L, "large chains took too long");
    }
    private static NaturalRiverHierarchy.Selection select(List<Reach> input, double length, double spacing) { return NaturalRiverHierarchy.select(input, reach -> false, length, spacing); }
    private static Set<String> shapes(List<Reach> input, NaturalRiverHierarchy.Selection selection) { Set<String> result = new HashSet<>(); for (int i : selection.retainedIndices()) { Reach r = input.get(i); result.add(r.x1()+","+r.z1()+":"+r.x2()+","+r.z2()); } return result; }
    private static Reach r(double x1, double z1, double x2, double z2) { return new Reach(x1,z1,20,1,x2,z2,19,1,1,1); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
