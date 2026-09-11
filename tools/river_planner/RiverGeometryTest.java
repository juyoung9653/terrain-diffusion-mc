import com.github.xandergos.terraindiffusionmc.world.river.DrainagePlanner;
import com.github.xandergos.terraindiffusionmc.world.river.RiverGeometry;
import java.util.HashMap;
import java.util.Map;

/** Standalone geometry checks; compile with DrainagePlanner and RiverGeometry. */
public final class RiverGeometryTest {
    public static void main(String[] args) {
        continuousWaterAndConfluenceWidening();
        incisionBoundAndStableColumns();
        barrierRejectsUpstreamWithoutTerminal();
        shallowInlandGapRejectsUpstream();
        lowSideGapIsRejected();
        System.out.println("RiverGeometryTest: OK");
    }

    private static void continuousWaterAndConfluenceWidening() {
        DrainagePlanner.Plan p = plan();
        RiverGeometry.Geometry g = RiverGeometry.build(p, 100, (x, z) -> plannedGround(p, x, z));
        check(!g.segments().isEmpty(), "synthetic slope renders channels");
        boolean inlandWater = false;
        for (RiverGeometry.Segment s : g.segments()) {
            RiverGeometry.Column a = g.column(s.x1(), s.z1()), b = g.column(s.x2(), s.z2());
            check(a.waterY() >= 62 && b.waterY() >= 62, "water remains continuous to sea level");
            inlandWater |= a.waterY() > 62;
            check(s.width2() + 1e-9 >= s.width1() || s.water2() == 62, "joins do not narrow downstream");
        }
        check(inlandWater, "synthetic slope has inland water above sea level");
        check(g.segments().stream().anyMatch(s -> s.width2() > s.width1()), "confluence widens the mainstem");
    }

    private static void incisionBoundAndStableColumns() {
        DrainagePlanner.Plan p = plan();
        RiverGeometry.Geometry g = RiverGeometry.build(p, 3, (x, z) -> plannedGround(p, x, z));
        RiverGeometry.Column a = g.column(-64, 0), b = g.column(-64, 0);
        check(a.equals(b), "repeated negative-coordinate query is stable");
        check(a.originalGroundY() - a.groundY() <= 3, "cut never exceeds maxCut");
    }

    private static void barrierRejectsUpstreamWithoutTerminal() {
        DrainagePlanner.Plan p = plan();
        RiverGeometry.Geometry g = RiverGeometry.build(p, 2,
                (x, z) -> x == 0 && z == 0 ? 150 : plannedGround(p, x, z));
        for (RiverGeometry.Segment s : g.segments()) check(s.target() >= 0, "no retained artificial terminal");
        check(g.rejectedSegments() > 0, "unsafe barrier rejects a route");
    }

    private static void shallowInlandGapRejectsUpstream() {
        int w = 9; double[] terrain = radialTerrain(w); terrain[4 + 3 * w] = 62;
        boolean[] ocean = new boolean[w * w]; ocean[4 + 4 * w] = true;
        DrainagePlanner.Plan p = DrainagePlanner.plan(-256, -256, 64, w, w, terrain, null,
                new DrainagePlanner.Settings(62, 1, 1, 30), ocean);
        RiverGeometry.Geometry g = RiverGeometry.build(p, 100, (x, z) -> plannedGround(p, x, z));
        Map<Integer, Integer> downstream = new HashMap<>();
        for (RiverGeometry.Segment s : g.segments()) downstream.put(s.source(), s.target());
        for (RiverGeometry.Segment s : g.segments()) {
            int node = s.source();
            while (downstream.containsKey(node)) node = downstream.get(node);
            check(p.downstream(node) < 0 && p.terminal(node) == DrainagePlanner.Terminal.OCEAN,
                    "retained channel reaches an actual ocean root");
        }
        check(g.rejectedSegments() > 0, "non-ocean shallow gap rejects its upstream route");
    }

    private static void lowSideGapIsRejected() {
        DrainagePlanner.Plan p = plan();
        RiverGeometry.Geometry baseline = RiverGeometry.build(p, 100, (x, z) -> plannedGround(p, x, z));
        int[] gap = findDrySide(baseline);
        check(gap != null, "test terrain has a dry bank beside inland water");
        RiverGeometry.Geometry repaired = RiverGeometry.build(p, 100,
                (x, z) -> x == gap[0] && z == gap[1] ? 60 : plannedGround(p, x, z));
        check(repaired.rejectedSegments() > baseline.rejectedSegments(), "low dry side rejects contributors");
        for (RiverGeometry.Segment s : repaired.segments()) assertContained(repaired, s);
    }

    private static int[] findDrySide(RiverGeometry.Geometry geometry) {
        for (RiverGeometry.Segment s : geometry.segments()) {
            for (int x = Math.min(s.x1(), s.x2()) - 16; x <= Math.max(s.x1(), s.x2()) + 16; x++)
                for (int z = Math.min(s.z1(), s.z2()) - 16; z <= Math.max(s.z1(), s.z2()) + 16; z++) {
                    RiverGeometry.Column c = geometry.column(x, z);
                    if (c.waterY() <= 62) continue;
                    for (int[] d : new int[][] {{-1,0},{1,0},{0,-1},{0,1}})
                        if (geometry.column(x + d[0], z + d[1]).waterY() == Integer.MIN_VALUE) return new int[] {x + d[0], z + d[1]};
                }
        }
        return null;
    }

    private static void assertContained(RiverGeometry.Geometry geometry, RiverGeometry.Segment s) {
        for (int x = Math.min(s.x1(), s.x2()) - 5; x <= Math.max(s.x1(), s.x2()) + 5; x++)
            for (int z = Math.min(s.z1(), s.z2()) - 5; z <= Math.max(s.z1(), s.z2()) + 5; z++) {
                RiverGeometry.Column c = geometry.column(x, z);
                if (c.waterY() <= 62) continue;
                for (int[] d : new int[][] {{-1,0},{1,0},{0,-1},{0,1}}) {
                    RiverGeometry.Column side = geometry.column(x + d[0], z + d[1]);
                    check(side.waterY() != Integer.MIN_VALUE || side.groundY() >= c.waterY(), "inland water has contained sides");
                }
            }
    }

    private static DrainagePlanner.Plan plan() {
        int w = 9; double[] terrain = radialTerrain(w);
        return DrainagePlanner.plan(-256, -256, 64, w, w, terrain, null,
                new DrainagePlanner.Settings(62, 1, 1, 30));
    }
    private static double[] radialTerrain(int width) {
        double[] terrain = new double[width * width]; int middle = width / 2;
        for (int z = 0; z < width; z++) for (int x = 0; x < width; x++)
            terrain[x + z * width] = 62 + Math.abs(x - middle) + Math.abs(z - middle);
        return terrain;
    }
    private static int plannedGround(DrainagePlanner.Plan p, int x, int z) {
        return 62 + (Math.abs(x) + Math.abs(z)) / 64;
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
