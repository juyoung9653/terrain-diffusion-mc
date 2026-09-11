import com.github.xandergos.terraindiffusionmc.world.river.DrainagePlanner;
import java.util.Arrays;

/** Standalone JDK test: no Minecraft or Gradle classpath required. */
public final class DrainagePlannerTest {
    private static final DrainagePlanner.Settings SETTINGS = new DrainagePlanner.Settings(10, 3, 1, 20);
    public static void main(String[] args) {
        branchedSlopeAccumulatesAtConfluence();
        flatPlateauIsAcyclicAndDeterministic();
        dryCellStillReceivesUpstreamFlow();
        deepDepressionRejectsItsEntireUpstreamRoute();
        boundaryIsExternalNotOceanChannel();
        belowSeaInlandNeedsExplicitOceanMask();
        shallowCoastalLandConnectsToActualOcean();
        inputsAreNotAliasedAndOriginsDoNotAffectTopology();
        System.out.println("DrainagePlannerTest: OK");
    }

    private static void branchedSlopeAccumulatesAtConfluence() {
        // Two high arms meet at the centre, then descend to an actual ocean outlet.
        double[] g = {30, 30, 30, 30, 30,  30, 28, 26, 28, 30,  30, 26, 22, 30, 30,  30, 30, 30, 18, 30,  30, 30, 30, 30, 5};
        DrainagePlanner.Plan p = DrainagePlanner.plan(0, 0, 1, 5, 5, g, null, SETTINGS);
        int join = at(2, 2, 5), stem = at(3, 3, 5);
        check(p.terminal(join) == DrainagePlanner.Terminal.OCEAN, "tributaries reach ocean");
        check(p.downstream(join) == stem, "joined flow enters mainstem");
        check(p.accumulation(join) >= 3, "confluence accumulates tributaries");
        check(p.accumulation(stem) >= p.accumulation(join), "mainstem widens downstream");
        check(p.channel(join) && p.channel(stem), "threshold identifies joined mainstem");
    }

    private static void flatPlateauIsAcyclicAndDeterministic() {
        double[] g = new double[25]; Arrays.fill(g, 50);
        DrainagePlanner.Plan a = DrainagePlanner.plan(0, 0, 1, 5, 5, g, null, SETTINGS);
        DrainagePlanner.Plan b = DrainagePlanner.plan(0, 0, 1, 5, 5, g, null, SETTINGS);
        for (int i = 0; i < g.length; i++) {
            check(a.downstream(i) == b.downstream(i), "flat routing is repeatable");
            int cursor = i;
            for (int steps = 0; cursor >= 0 && steps <= g.length; steps++) cursor = a.downstream(cursor);
            check(cursor < 0, "flat routing has no cycle");
        }
    }

    private static void dryCellStillReceivesUpstreamFlow() {
        double[] g = {40,40,40,40,40, 40,30,40,40,40, 40,40,20,40,40, 40,40,40,15,40, 40,40,40,40,5};
        double[] r = new double[25]; Arrays.fill(r, 1); r[at(2,2,5)] = 0;
        DrainagePlanner.Plan p = DrainagePlanner.plan(0, 0, 1, 5, 5, g, r, new DrainagePlanner.Settings(10, 1, 1, 20));
        check(p.accumulation(at(2,2,5)) > 0, "zero-local-runoff cell receives upstream accumulation");
    }

    private static void deepDepressionRejectsItsEntireUpstreamRoute() {
        double[] g = {50,50,50,50,50, 50,50,50,50,50, 50,50,20,50,50, 50,50,50,50,50, 50,50,50,50,5};
        DrainagePlanner.Plan p = DrainagePlanner.plan(0, 0, 1, 5, 5, g, null, new DrainagePlanner.Settings(10, 1, 1, 2));
        check(!p.channel(at(2,2,5)), "overfilled depression is rejected");
        check(p.renderedAccumulation(at(2,2,5)) == 0, "rejected basin contributes no rendered flow");
    }

    private static void boundaryIsExternalNotOceanChannel() {
        double[] g = {30,30,30, 30,20,30, 30,30,30};
        DrainagePlanner.Plan p = DrainagePlanner.plan(0, 0, 1, 3, 3, g, null, new DrainagePlanner.Settings(10, 1, 1, 20));
        check(p.terminal(4) == DrainagePlanner.Terminal.EXTERNAL, "finite boundary is external");
        check(!p.channel(4), "external drainage cannot become an ocean channel");
    }

    private static void belowSeaInlandNeedsExplicitOceanMask() {
        double[] g = {30,30,30, 30,0,30, 30,30,30};
        boolean[] notOcean = new boolean[9];
        DrainagePlanner.Plan p = DrainagePlanner.plan(0, 0, 1, 3, 3, g, null,
                new DrainagePlanner.Settings(10, 1, 1, 0), notOcean);
        check(p.terminal(4) == DrainagePlanner.Terminal.EXTERNAL,
                "below-sea inland cell without ocean mask is not an ocean root");
        check(!p.channel(4), "below-sea inland cell is not rendered as an ocean channel");
    }

    private static void shallowCoastalLandConnectsToActualOcean() {
        // The ocean root is explicit.  The 62-high shore is land despite
        // matching sea level, so it must remain a channel-capable connector.
        double[] g = {90,90,90,90,90, 90,63,62,60,90, 90,90,90,90,90};
        boolean[] ocean = new boolean[15]; ocean[at(3,1,5)] = true;
        DrainagePlanner.Plan p = DrainagePlanner.plan(0, 0, 1, 5, 3, g, null,
                new DrainagePlanner.Settings(62, 1, 1, 1), ocean);
        int upstream = at(1,1,5), shore = at(2,1,5), root = at(3,1,5);
        check(p.terminal(upstream) == DrainagePlanner.Terminal.OCEAN
                && p.terminal(shore) == DrainagePlanner.Terminal.OCEAN
                && p.terminal(root) == DrainagePlanner.Terminal.OCEAN,
                "coastal chain reaches only the actual ocean root");
        check(p.downstream(upstream) == shore && p.downstream(shore) == root,
                "coastal chain does not route to preview boundary");
        check(p.channel(upstream) && p.channel(shore) && !p.channel(root),
                "shallow land connector renders while actual ocean root does not");
    }

    private static void inputsAreNotAliasedAndOriginsDoNotAffectTopology() {
        double[] g = {30,30,30, 30,20,30, 30,11,5}; double[] r = {1,1,1, 1,2,1, 1,1,1};
        DrainagePlanner.Plan a = DrainagePlanner.plan(-99, -17, 2, 3, 3, g, r, SETTINGS);
        DrainagePlanner.Plan b = DrainagePlanner.plan(99, 17, 2, 3, 3, g, r, SETTINGS);
        g[4] = -999; r[4] = 999;
        check(a.ground(4) == 20 && a.accumulation(4) != 999 * 4, "plan does not alias inputs");
        for (int i = 0; i < 9; i++) check(a.downstream(i) == b.downstream(i), "negative origin preserves topology");
    }

    private static int at(int x, int z, int width) { return x + z * width; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
