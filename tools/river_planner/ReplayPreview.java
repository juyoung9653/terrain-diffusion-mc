import com.github.xandergos.terraindiffusionmc.world.river.DrainagePlanner;
import com.github.xandergos.terraindiffusionmc.world.river.RiverGeometry;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Replays one exported river preview without Minecraft or terrain generation.
 * Usage: ReplayPreview DEM.csv td-river-preview-ground.bin.gz [minCatchmentArea] [segments.csv]
 */
public final class ReplayPreview {
    private static final int MAGIC = 0x54445231; // TDR1
    // A 4096-block preview exports its inclusive edge as 4097 squared cells.
    private static final int MAX_SNAPSHOT_CELLS = 20_000_000;
    private static final long MAX_WET_SAMPLES = 10_000_000L;

    private ReplayPreview() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4) {
            throw new IllegalArgumentException("usage: ReplayPreview DEM.csv ground.bin.gz [minCatchmentArea] [segments.csv]");
        }
        double threshold = args.length >= 3 ? finitePositive(args[2], "minCatchmentArea") : 65536.0;
        Path output = args.length == 4 ? Path.of(args[3]) : Path.of("replay-segments.csv");
        CsvGrid csv = readCsv(Path.of(args[0]));
        FullGround full = readGround(Path.of(args[1]));
        validateCoverage(csv, full);

        DrainagePlanner.Plan plan = DrainagePlanner.plan(csv.originX, csv.originZ, csv.spacing, csv.width, csv.height,
                csv.ground, csv.runoff, new DrainagePlanner.Settings(62, threshold, 1, 1), csv.ocean);
        RiverGeometry.Geometry geometry = RiverGeometry.build(plan, 6, full::groundAt);
        Metrics metrics = inspect(plan, geometry, full);
        writeSegments(output, geometry.segments());
        System.out.printf(Locale.ROOT,
                "{\"segments\":%d,\"confluences\":%d,\"aboveSeaSegments\":%d,\"maxFullWidth\":%.6f,\"maxActualCut\":%d,\"invalidEndpoints\":%d,\"leakingSides\":%d,\"seaLevelContacts\":%d,\"inlandLeakSamples\":%s,\"wetSamples\":%d,\"scanTruncated\":%s,\"rejectedSegments\":%d,\"output\":\"%s\"}%n",
                geometry.segments().size(), metrics.confluences, metrics.aboveSeaSegments, metrics.maxFullWidth,
                metrics.maxActualCut, metrics.invalidEndpoints, metrics.inlandLeakingSides, metrics.seaLevelContacts,
                samplesJson(metrics.inlandLeakSamples), metrics.wetSamples,
                metrics.scanTruncated, geometry.rejectedSegments(), json(output.toString()));
    }

    private static CsvGrid readCsv(Path path) throws IOException {
        List<CsvNode> nodes = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            String expected = "x,z,ground,drainage,renderedCatchment,terminal,channel,runoff,ocean";
            if (!expected.equals(header)) throw new IOException("unexpected DEM CSV header");
            String line; int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) continue;
                String[] part = line.split(",", -1);
                if (part.length != 9) throw new IOException("CSV line " + lineNumber + " has " + part.length + " columns");
                try {
                    int x = Integer.parseInt(part[0]); int z = Integer.parseInt(part[1]);
                    double ground = Double.parseDouble(part[2]);
                    double runoff = Double.parseDouble(part[7]);
                    if (!Double.isFinite(ground) || !Double.isFinite(runoff) || runoff < 0) throw new NumberFormatException();
                    boolean ocean = bool(part[8], lineNumber, "ocean");
                    nodes.add(new CsvNode(x, z, ground, runoff, ocean));
                } catch (NumberFormatException ex) {
                    throw new IOException("invalid numeric value at CSV line " + lineNumber, ex);
                }
            }
        }
        if (nodes.isEmpty()) throw new IOException("DEM CSV has no rows");
        int originX = nodes.get(0).x, originZ = nodes.get(0).z;
        int width = 0;
        while (width < nodes.size() && nodes.get(width).z == originZ) width++;
        if (width == nodes.size()) throw new IOException("DEM CSV needs at least two z rows to infer spacing");
        if (width < 2) throw new IOException("DEM CSV needs at least two x columns to infer spacing");
        int spacing = nodes.get(1).x - originX;
        if (spacing <= 0) throw new IOException("CSV x spacing must be positive");
        if (nodes.size() % width != 0) throw new IOException("CSV rows are not rectangular");
        int height = nodes.size() / width;
        int zSpacing = nodes.get(width).z - originZ;
        if (zSpacing != spacing) throw new IOException("CSV x/z spacing differs");
        double[] ground = new double[nodes.size()], runoff = new double[nodes.size()]; boolean[] ocean = new boolean[nodes.size()];
        for (int z = 0; z < height; z++) for (int x = 0; x < width; x++) {
            int i = x + z * width; CsvNode node = nodes.get(i);
            if (node.x != originX + x * spacing || node.z != originZ + z * spacing) {
                throw new IOException("CSV is not row-major regular grid at row " + (i + 2));
            }
            ground[i] = node.ground; runoff[i] = node.runoff; ocean[i] = node.ocean;
        }
        return new CsvGrid(originX, originZ, spacing, width, height, ground, runoff, ocean);
    }

    private static FullGround readGround(Path path) throws IOException {
        try (InputStream file = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(new GZIPInputStream(file))) {
            if (in.readInt() != MAGIC) throw new IOException("ground snapshot has wrong magic");
            int originX = in.readInt(), originZ = in.readInt(), side = in.readInt();
            long cells = (long) side * side;
            if (side <= 0 || cells > MAX_SNAPSHOT_CELLS) throw new IOException("ground snapshot side is out of bounds: " + side);
            short[] values = new short[(int) cells];
            for (int i = 0; i < values.length; i++) values[i] = in.readShort();
            if (in.read() != -1) throw new IOException("ground snapshot has trailing bytes");
            return new FullGround(originX, originZ, side, values);
        } catch (java.io.EOFException e) {
            throw new IOException("ground snapshot ended before side squared heights", e);
        }
    }

    private static void validateCoverage(CsvGrid csv, FullGround full) throws IOException {
        long maxX = (long) csv.originX + (long) (csv.width - 1) * csv.spacing;
        long maxZ = (long) csv.originZ + (long) (csv.height - 1) * csv.spacing;
        long fullMaxX = (long) full.originX + full.side - 1L, fullMaxZ = (long) full.originZ + full.side - 1L;
        if (csv.originX < full.originX || csv.originZ < full.originZ || maxX > fullMaxX || maxZ > fullMaxZ) {
            throw new IOException("full-ground snapshot does not cover DEM grid");
        }
    }

    private static Metrics inspect(DrainagePlanner.Plan plan, RiverGeometry.Geometry geometry, FullGround full) {
        Map<Integer, Integer> inbound = new HashMap<>();
        Map<Integer, Integer> retained = new HashMap<>();
        for (RiverGeometry.Segment segment : geometry.segments()) retained.put(segment.source(), segment.target());
        int aboveSea = 0, invalid = 0; double maxWidth = 0;
        for (RiverGeometry.Segment segment : geometry.segments()) {
            inbound.merge(segment.target(), 1, Integer::sum);
            if (segment.water1() > 62 || segment.water2() > 62) aboveSea++;
            maxWidth = Math.max(maxWidth, Math.max(segment.width1(), segment.width2()) * 2.0);
            if (!full.contains(segment.x1(), segment.z1()) || !full.contains(segment.x2(), segment.z2())
                    || !retainedChainReachesOcean(plan, segment.source(), retained)) invalid++;
        }
        int confluences = 0;
        for (int count : inbound.values()) if (count >= 2) confluences++;
        int maxCut = 0, inlandLeaks = 0, seaLevelContacts = 0; long samples = 0; boolean truncated = false;
        List<LeakSample> inlandSamples = new ArrayList<>();
        scan:
        for (RiverGeometry.Segment segment : geometry.segments()) {
            int radius = (int) Math.ceil(Math.max(segment.width1(), segment.width2()));
            int minX = Math.min(segment.x1(), segment.x2()) - radius, maxX = Math.max(segment.x1(), segment.x2()) + radius;
            int minZ = Math.min(segment.z1(), segment.z2()) - radius, maxZ = Math.max(segment.z1(), segment.z2()) + radius;
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                if (++samples > MAX_WET_SAMPLES) { truncated = true; break scan; }
                RiverGeometry.Column wet = geometry.column(x, z);
                if (wet.waterY() == Integer.MIN_VALUE) continue;
                maxCut = Math.max(maxCut, wet.originalGroundY() - wet.groundY());
                for (int[] d : CARDINAL) {
                    RiverGeometry.Column side = geometry.column(x + d[0], z + d[1]);
                    if (side.waterY() != Integer.MIN_VALUE || side.groundY() >= wet.waterY()) continue;
                    if (wet.waterY() > 62) {
                        inlandLeaks++;
                        if (inlandSamples.size() < 16) {
                            inlandSamples.add(new LeakSample(x + d[0], z + d[1], side.originalGroundY(), wet.waterY()));
                        }
                    } else {
                        seaLevelContacts++;
                    }
                }
            }
        }
        return new Metrics(confluences, aboveSea, maxWidth, maxCut, invalid, inlandLeaks, seaLevelContacts,
                List.copyOf(inlandSamples), samples, truncated);
    }

    private static boolean retainedChainReachesOcean(DrainagePlanner.Plan plan, int node,
                                                     Map<Integer, Integer> retained) {
        for (int step = 0; step <= plan.width() * plan.height(); step++) {
            Integer next = retained.get(node);
            if (next == null) return plan.downstream(node) < 0 && plan.terminal(node) == DrainagePlanner.Terminal.OCEAN;
            node = next;
        }
        return false;
    }

    private static void writeSegments(Path output, List<RiverGeometry.Segment> segments) throws IOException {
        StringBuilder csv = new StringBuilder("source,target,x1,z1,x2,z2,water1,water2,width1,width2\n");
        for (RiverGeometry.Segment s : segments) csv.append(String.format(Locale.ROOT,
                "%d,%d,%d,%d,%d,%d,%d,%d,%.9f,%.9f%n", s.source(), s.target(), s.x1(), s.z1(), s.x2(), s.z2(),
                s.water1(), s.water2(), s.width1(), s.width2()));
        Files.writeString(output, csv, StandardCharsets.UTF_8);
    }

    private static boolean bool(String value, int line, String field) throws IOException {
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) return false;
        throw new IOException("CSV line " + line + " has invalid " + field);
    }
    private static double finitePositive(String value, String name) {
        try {
            double parsed = Double.parseDouble(value);
            if (Double.isFinite(parsed) && parsed > 0) return parsed;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + " must be finite and positive");
    }
    private static String json(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static String samplesJson(List<LeakSample> samples) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < samples.size(); i++) {
            LeakSample s = samples.get(i);
            if (i > 0) out.append(',');
            out.append(String.format(Locale.ROOT, "{\"x\":%d,\"z\":%d,\"raw\":%d,\"water\":%d}",
                    s.x, s.z, s.raw, s.water));
        }
        return out.append(']').toString();
    }

    private static final int[][] CARDINAL = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private record CsvNode(int x, int z, double ground, double runoff, boolean ocean) { }
    private record CsvGrid(int originX, int originZ, int spacing, int width, int height,
                           double[] ground, double[] runoff, boolean[] ocean) { }
    private record FullGround(int originX, int originZ, int side, short[] values) {
        boolean contains(int x, int z) { return x >= originX && z >= originZ && x - originX < side && z - originZ < side; }
        int groundAt(int x, int z) {
            if (!contains(x, z)) throw new IllegalArgumentException("geometry sampled outside full ground at " + x + "," + z);
            return values[(x - originX) + (z - originZ) * side];
        }
    }
    private record LeakSample(int x, int z, int raw, int water) { }
    private record Metrics(int confluences, int aboveSeaSegments, double maxFullWidth, int maxActualCut,
                           int invalidEndpoints, int inlandLeakingSides, int seaLevelContacts,
                           List<LeakSample> inlandLeakSamples, long wetSamples, boolean scanTruncated) { }
}
