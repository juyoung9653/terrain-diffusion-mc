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
        legacyTerminalEndpointIsIndexed();
        naturalPathsKeepTopologyAndContainment();
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

    private static void naturalPathsKeepTopologyAndContainment() {
        DrainagePlanner.Plan p = plan();
        RiverGeometry.Geometry legacy = RiverGeometry.build(p, 6, (x, z) -> plannedGround(p, x, z));
        RiverGeometry.Geometry natural = RiverGeometry.buildNatural(p, 6, (x, z) -> plannedGround(p, x, z));
        check(natural.segments().size() == legacy.segments().size(), "natural appearance keeps accepted coverage");
        boolean curved = false;
        Map<Integer, int[]> starts = new HashMap<>();
        Map<Integer, Double> nodeWidthFactor = new HashMap<>();
        double minFactor = Double.POSITIVE_INFINITY, maxFactor = Double.NEGATIVE_INFINITY;
        for (RiverGeometry.Segment s : natural.segments()) {
            starts.put(s.source(), new int[] {s.x1(), s.z1()});
            var path = natural.path(s);
            check(path.size() >= 2, "every segment exposes a centreline");
            check(path.get(0).x() == s.x1() && path.get(0).z() == s.z1(), "path starts at Segment endpoint");
            var end = path.get(path.size() - 1);
            check(end.x() == s.x2() && end.z() == s.z2(), "path ends at Segment endpoint");
            check(path.get(0).water() == s.water1() && end.water() == s.water2(), "path retains hydraulic endpoints");
            double startFactor = path.get(0).width() / s.width1();
            double endFactor = end.width() / s.width2();
            checkSharedFactor(nodeWidthFactor, s.source(), startFactor);
            checkSharedFactor(nodeWidthFactor, s.target(), endFactor);
            minFactor = Math.min(minFactor, Math.min(startFactor, endFactor));
            maxFactor = Math.max(maxFactor, Math.max(startFactor, endFactor));
            for (RiverGeometry.PathPoint point : path) {
                check(Math.hypot(point.x() - s.x1(), point.z() - s.z1()) <= 1.5 * p.spacing(), "sample spacing is bounded");
                if (Math.abs((s.x2() - s.x1()) * (point.z() - s.z1()) - (s.z2() - s.z1()) * (point.x() - s.x1())) > .01) curved = true;
            }
            assertNaturalContained(natural, s);
        }
        for (RiverGeometry.Segment s : natural.segments()) {
            int[] join = starts.get(s.target());
            if (join != null) check(join[0] == s.x2() && join[1] == s.z2(), "shared graph nodes have one relocated endpoint");
        }
        assertCollinearTangents(natural);
        check(curved, "natural geometry bends at least one retained reach");
        check(maxFactor - minFactor > .08, "natural node widths vary at world scale");
        RiverGeometry.Geometry repeat = RiverGeometry.buildNatural(p, 6, (x, z) -> plannedGround(p, x, z));
        check(natural.segments().equals(repeat.segments()), "natural endpoints are deterministic");
        check(natural.path(natural.segments().get(0)).equals(repeat.path(repeat.segments().get(0))), "natural paths are deterministic");
    }

    private static void checkSharedFactor(Map<Integer, Double> factors, int node, double factor) {
        Double prior = factors.putIfAbsent(node, factor);
        check(prior == null || Math.abs(prior - factor) < 1e-9, "all reaches share a node width factor");
    }

    private static void assertCollinearTangents(RiverGeometry.Geometry geometry) {
        Map<Integer, RiverGeometry.Segment> outgoing = new HashMap<>();
        for (RiverGeometry.Segment segment : geometry.segments()) outgoing.put(segment.source(), segment);
        int checked = 0;
        for (RiverGeometry.Segment incoming : geometry.segments()) {
            RiverGeometry.Segment next = outgoing.get(incoming.target());
            if (next == null) continue;
            double ax = incoming.x2() - incoming.x1(), az = incoming.z2() - incoming.z1();
            double bx = next.x2() - next.x1(), bz = next.z2() - next.z1();
            double baseDot = (ax * bx + az * bz) / Math.hypot(ax, az) / Math.hypot(bx, bz);
            if (baseDot < .98) continue;
            var a = geometry.path(incoming); var b = geometry.path(next);
            var a0 = a.get(a.size() - 2); var a1 = a.get(a.size() - 1);
            var b0 = b.get(0); var b1 = b.get(1);
            double tx = a1.x() - a0.x(), tz = a1.z() - a0.z();
            double ux = b1.x() - b0.x(), uz = b1.z() - b0.z();
            double tangentDot = (tx * ux + tz * uz) / Math.hypot(tx, tz) / Math.hypot(ux, uz);
            check(tangentDot > .98, "collinear natural reaches share their node tangent");
            checked++;
        }
        check(checked > 0, "synthetic network exercises collinear natural joins");
    }

    private static void legacyTerminalEndpointIsIndexed() {
        DrainagePlanner.Plan p = plan();
        RiverGeometry.Geometry g = RiverGeometry.build(p, 100, (x, z) -> 62 + (Math.abs(x) + Math.abs(z)) / 64);
        RiverGeometry.Segment intoTerminal = g.segments().stream().filter(s -> p.downstream(s.target()) < 0).findFirst()
                .orElseThrow(() -> new AssertionError("synthetic ocean has inbound segment"));
        RiverGeometry.Column terminal = g.column(intoTerminal.x2(), intoTerminal.z2());
        check(terminal.active() && terminal.waterY() != Integer.MIN_VALUE,
                "legacy index includes a segment's far endpoint across a 64-block bin");
    }

    private static void assertNaturalContained(RiverGeometry.Geometry geometry, RiverGeometry.Segment segment) {
        var path = geometry.path(segment);
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (RiverGeometry.PathPoint point : path) {
            minX = Math.min(minX, point.x()); maxX = Math.max(maxX, point.x());
            minZ = Math.min(minZ, point.z()); maxZ = Math.max(maxZ, point.z());
        }
        for (int x = (int) Math.floor(minX) - 40; x <= (int) Math.ceil(maxX) + 40; x++)
            for (int z = (int) Math.floor(minZ) - 40; z <= (int) Math.ceil(maxZ) + 40; z++) {
                RiverGeometry.Column c = geometry.column(x, z);
                if (c.waterY() == Integer.MIN_VALUE) continue;
                check(c.originalGroundY() - c.groundY() <= 6, "natural channel respects maxCut");
                if (c.waterY() > 62) for (int[] d : new int[][] {{-1,0},{1,0},{0,-1},{0,1}}) {
                    RiverGeometry.Column side = geometry.column(x + d[0], z + d[1]);
                    check(side.waterY() != Integer.MIN_VALUE || side.groundY() >= c.waterY(), "natural inland water has contained sides");
                }
            }
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
