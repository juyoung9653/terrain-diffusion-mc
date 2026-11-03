import com.github.xandergos.terraindiffusionmc.world.geology.GeologyModel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-free checks for deterministic, scale-normalized underground geology. */
public final class GeologyModelTest {
    private static final long SEED = 91_357L;
    private static final int SEA = GeologyModel.SEA_LEVEL;
    private static final int[] ORES = {
            GeologyModel.ORE_COAL, GeologyModel.ORE_COPPER, GeologyModel.ORE_IRON,
            GeologyModel.ORE_GOLD, GeologyModel.ORE_REDSTONE, GeologyModel.ORE_LAPIS,
            GeologyModel.ORE_DIAMOND
    };
    private static final int[] SCALE_FLOORS = {-400, -736, -1072, -1408, -1744, -1968};

    public static void main(String[] args) throws Exception {
        verifyDeterminismSeedAndNegativeChunkSeams();
        verifyRawFloorsAndRoofs();
        verifyScaleEquivarianceAwayFromRawBounds();
        verifyOresAndHosts();
        verifyCacheEvictionAndSurfaceOrder();
        verifyEllipticCapsuleIntersection();
        System.out.println("GeologyModelTest: PASS");
    }

    private static void verifyDeterminismSeedAndNegativeChunkSeams() {
        List<Point> points = new ArrayList<>();
        for (int border : new int[] {-448, -336, -224, -112, -32, -16, 0, 16, 112, 224}) {
            for (int offset : new int[] {-2, -1, 0, 1, 2}) {
                points.add(new Point(border + offset, -border / 2 - 17));
                points.add(new Point(border + offset, 16 - border));
            }
        }
        Map<Point, String> forward = evaluate(new GeologyModel(SEED, -400, 1), points, false, 319);
        Map<Point, String> reverse = evaluate(new GeologyModel(SEED, -400, 1), points, true, 319);
        require(forward.equals(reverse), "negative-chunk samples changed with traversal order");
        Map<Point, String> fresh = evaluate(new GeologyModel(SEED, -400, 1), points, false, 319);
        require(forward.equals(fresh), "seam samples changed between equivalent model instances");
        Map<Point, String> otherSeed = evaluate(new GeologyModel(SEED + 1, -400, 1), points, false, 319);
        require(!forward.equals(otherSeed), "different seed produced identical seam samples");
    }

    private static void verifyRawFloorsAndRoofs() {
        for (int scale = 1; scale <= 6; scale++) {
            int floor = SCALE_FLOORS[scale - 1];
            int surface = SEA + 300 * scale;
            GeologyModel model = new GeologyModel(SEED, floor, scale);
            for (Point point : List.of(new Point(-112 * scale, 17 * scale), new Point(-1, -1),
                    new Point(0, 0), new Point(112 * scale - 1, -112 * scale + 1))) {
                GeologyModel.Column column = model.column(point.x(), point.z(), surface);
                for (int y = floor - 8; y <= floor + 8; y++)
                    require(column.cavity(y) == 0, "cavity crossed raw floor scale=" + scale + " y=" + y);
                for (int y = surface - GeologyModel.MIN_ROOF; y <= surface + 8; y++)
                    require(column.cavity(y) == 0, "cavity crossed raw roof scale=" + scale + " y=" + y);
            }
        }
    }

    private static void verifyScaleEquivarianceAwayFromRawBounds() {
        int baseFloor = SCALE_FLOORS[0], baseSurface = 280;
        GeologyModel one = new GeologyModel(SEED, baseFloor, 1);
        int comparedCaves = 0;
        for (int scale = 1; scale <= 6; scale++) {
            // Dimension floors are 16-aligned raw values, so their normalized
            // positions differ slightly.  Sample far above every floor.
            int floor = SCALE_FLOORS[scale - 1];
            int surface = SEA + (baseSurface - SEA) * scale;
            GeologyModel scaled = new GeologyModel(SEED, floor, scale);
            for (int baseX : new int[] {-333, -217, -113, -47, 19, 71, 143, 279}) {
                for (int baseZ : new int[] {-285, -149, -31, 83, 197}) {
                    GeologyModel.Column a = one.column(baseX, baseZ, baseSurface);
                    GeologyModel.Column b = scaled.column(baseX * scale, baseZ * scale, surface);
                    for (int baseY = -175; baseY <= 175; baseY += 7) {
                        int y = SEA + baseY, scaledY = SEA + baseY * scale;
                        require(a.rock(y) == b.rock(scaledY), "rock scale mismatch s=" + scale);
                        boolean caveA = a.cavity(y) != 0, caveB = b.cavity(scaledY) != 0;
                        require(caveA == caveB, "cave scale mismatch s=" + scale);
                        if (caveA) comparedCaves++;
                    }
                }
            }
        }
        require(comparedCaves > 0, "scale-equivalence sample did not cover a cave");
    }

    private static void verifyOresAndHosts() {
        GeologyModel model = new GeologyModel(SEED, -400, 1);
        Set<Integer> ores = new java.util.HashSet<>(), rocks = new java.util.HashSet<>();
        int oreBlocks = 0;
        // Grain noise is raw-block resolution, so test reachability/hosts, not scale identity.
        for (int x = -1536; x <= 1536; x += 32) for (int z = -1536; z <= 1536; z += 32) {
            GeologyModel.Column column = model.column(x, z, 340);
            for (int y = -391; y < 316; y += 4) {
                int rock = column.rock(y), material = column.material(y);
                rocks.add(rock);
                if (GeologyModel.isOre(material)) {
                    ores.add(material); oreBlocks++;
                    require(GeologyModel.acceptsHost(material, rock), "ore in incompatible host");
                }
            }
        }
        for (int ore : ORES) require(ores.contains(ore), "ore not reachable: " + ore);
        require(rocks.size() == 7, "not every rock family appeared: " + rocks);
        require(oreBlocks > 0, "no ore in deterministic mineral sample");
    }

    private static void verifyCacheEvictionAndSurfaceOrder() {
        GeologyModel model = new GeologyModel(SEED, -400, 1);
        String baseline = snapshot(model.column(-73, 111, 300), -385, 260);
        for (int i = -12; i <= 12; i++) model.column(i * 112 + 7, i * 71 - 9, 520);
        require(baseline.equals(snapshot(model.column(-73, 111, 300), -385, 260)), "cache churn changed a column");
        GeologyModel lowHighLow = new GeologyModel(SEED, -400, 1);
        String low = snapshot(lowHighLow.column(31, -47, 280), -385, 240);
        lowHighLow.column(31, -47, 680);
        require(low.equals(snapshot(lowHighLow.column(31, -47, 280), -385, 240)), "low-high-low changed geology");
        GeologyModel highLow = new GeologyModel(SEED, -400, 1);
        highLow.column(31, -47, 680);
        require(low.equals(snapshot(highLow.column(31, -47, 280), -385, 240)), "high-low query order changed geology");
    }

    private static void verifyEllipticCapsuleIntersection() throws Exception {
        CapsuleAccess access = new CapsuleAccess();
        Object a = access.node(15, 100, -10), b = access.node(85, 140, 50);
        Object sloped = access.segment(a, b, 8, .63, 2);
        assertCapsuleIntervals(access, sloped, new Node(15, 100, -10), new Node(85, 140, 50), 8, .63, 1.5,
                new double[][] {{15, -10}, {50, 20}, {85, 50}, {98, 50}});
        Object centre = access.node(0, 80, 0), sphere = access.segment(centre, centre, 5, .45, 1);
        assertCapsuleIntervals(access, sphere, new Node(0, 80, 0), new Node(0, 80, 0), 5, .45, -1,
                new double[][] {{0, 0}, {3, 4}, {6, 0}});
    }

    private static void assertCapsuleIntervals(CapsuleAccess access, Object segment, Node a, Node b,
                                               double radius, double aspect, double roughness, double[][] columns)
            throws Exception {
        for (double[] column : columns) {
            int[] actual = access.interval(segment, column[0], column[1], roughness);
            int[] expected = bruteInterval(a, b, radius, aspect, roughness, column[0], column[1]);
            require(java.util.Arrays.equals(actual, expected), "elliptic capsule mismatch at "
                    + column[0] + "," + column[1] + ": actual=" + java.util.Arrays.toString(actual)
                    + " expected=" + java.util.Arrays.toString(expected));
        }
    }

    private static int[] bruteInterval(Node a, Node b, double radius, double aspect, double roughness,
                                       double x, double z) {
        double r = Math.max(radius * .6, radius + roughness);
        int lo = (int) Math.floor(Math.min(a.y(), b.y()) - r * aspect - 3);
        int hi = (int) Math.ceil(Math.max(a.y(), b.y()) + r * aspect + 3);
        int first = Integer.MAX_VALUE, last = Integer.MIN_VALUE;
        for (int y = lo; y <= hi; y++) if (metricDistanceSquared(x, y / aspect, z, a, b, aspect) <= r * r + 1e-8) {
            first = Math.min(first, y); last = Math.max(last, y);
        }
        return first == Integer.MAX_VALUE ? null : new int[] {first, last};
    }

    private static double metricDistanceSquared(double x, double y, double z, Node a, Node b, double aspect) {
        double ax = a.x(), ay = a.y() / aspect, az = a.z();
        double dx = b.x() - ax, dy = b.y() / aspect - ay, dz = b.z() - az;
        double length = dx * dx + dy * dy + dz * dz;
        double t = length == 0 ? 0 : ((x - ax) * dx + (y - ay) * dy + (z - az) * dz) / length;
        t = Math.max(0, Math.min(1, t));
        double ex = x - (ax + dx * t), ey = y - (ay + dy * t), ez = z - (az + dz * t);
        return ex * ex + ey * ey + ez * ez;
    }

    private static Map<Point, String> evaluate(GeologyModel model, List<Point> points, boolean reverse, int surface) {
        Map<Point, String> result = new LinkedHashMap<>();
        for (int i = reverse ? points.size() - 1 : 0; reverse ? i >= 0 : i < points.size(); i += reverse ? -1 : 1) {
            Point point = points.get(i);
            result.put(point, snapshot(model.column(point.x(), point.z(), surface), -385, 260));
        }
        return result;
    }

    private static String snapshot(GeologyModel.Column column, int low, int high) {
        StringBuilder result = new StringBuilder();
        for (int y = low; y <= high; y += 7)
            result.append((char) column.rock(y)).append((char) column.material(y)).append((char) column.cavity(y));
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Point(int x, int z) {}
    private record Node(double x, double y, double z) {}

    private static final class CapsuleAccess {
        private final Constructor<?> node, segment;
        private final Method intersect;

        CapsuleAccess() throws Exception {
            Class<?> nodeClass = Class.forName(GeologyModel.class.getName() + "$Node");
            Class<?> segmentClass = Class.forName(GeologyModel.class.getName() + "$Segment");
            node = nodeClass.getDeclaredConstructor(double.class, double.class, double.class);
            segment = segmentClass.getDeclaredConstructor(nodeClass, nodeClass, double.class, double.class, double.class);
            intersect = segmentClass.getDeclaredMethod("intersectColumn", double.class, double.class, double.class);
            node.setAccessible(true); segment.setAccessible(true); intersect.setAccessible(true);
        }

        Object node(double x, double y, double z) throws Exception { return node.newInstance(x, y, z); }
        Object segment(Object a, Object b, double radius, double aspect, double padding) throws Exception {
            return segment.newInstance(a, b, radius, aspect, padding);
        }
        int[] interval(Object value, double x, double z, double roughness) throws Exception {
            Object interval = intersect.invoke(value, x, z, roughness);
            if (interval == null) return null;
            Method low = interval.getClass().getDeclaredMethod("low"), high = interval.getClass().getDeclaredMethod("high");
            low.setAccessible(true); high.setAccessible(true);
            return new int[] {(int) low.invoke(interval), (int) high.invoke(interval)};
        }
    }
}
