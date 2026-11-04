import com.github.xandergos.terraindiffusionmc.world.geology.UndergroundRiverModel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-model diagnostic for compact cave-stream sections.
 *
 * <p>This samples the public river model only.  It is intentionally not a
 * terrain, block-carving, visual, or hydrology acceptance test: Minecraft's
 * native cave walls can only be judged after generating and viewing a world.
 * The CSV records the observable section dimensions at several positions along
 * each non-lake reach so changes cannot hide a return to uniform rectangular
 * tunnels behind a single camera angle.</p>
 */
public final class UndergroundRiverSectionAudit {
    private static final long FIXTURE_SEED = 6187795302574383407L;
    private static final long PLAYER_SEED = 6761977799736804665L;
    private static final double[] SAMPLES = {.22, .50, .78};

    private record Key(double x, double y, double z) {
        static Key of(UndergroundRiverModel.Point point) { return new Key(point.x(), point.y(), point.z()); }
    }

    private record Row(long seed, int scale, int cellX, int cellZ, int reach, double q, double t,
                       int x, int z, int width, int left, int right, int bedDepth, int headroom,
                       int roof, int water, int bed, boolean lake) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: UndergroundRiverSectionAudit <output-directory>");
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        List<Row> rows = new ArrayList<>();
        sampleFixture(rows, FIXTURE_SEED, 2, -2, 1, -1, 2);
        sampleFixture(rows, FIXTURE_SEED, 6, -2, 1, -1, 2);
        // The player's observed scale-two seed/cell is sampled separately rather
        // than being presented as a broad statistical sample.
        sampleFixture(rows, PLAYER_SEED, 2, -3, -3, 3, 3);
        rows.sort(Comparator.comparingLong(Row::seed).thenComparingInt(Row::scale)
                .thenComparingInt(Row::cellX).thenComparingInt(Row::cellZ).thenComparingInt(Row::reach)
                .thenComparingDouble(Row::t));
        writeSections(output.resolve("section-samples.csv"), rows);
        writeSummary(output.resolve("section-summary.txt"), rows);
        writeConfluenceCameras(output.resolve("confluence-cameras.csv"));
    }

    private static void sampleFixture(List<Row> out, long seed, int scale, int minX, int maxX, int minZ, int maxZ) {
        UndergroundRiverModel model = new UndergroundRiverModel(seed, scale, 2);
        for (int cellX = minX; cellX <= maxX; cellX++) for (int cellZ = minZ; cellZ <= maxZ; cellZ++) {
            List<UndergroundRiverModel.Reach> reaches = model.reaches(cellX, cellZ);
            for (int index = 0; index < reaches.size(); index++) {
                UndergroundRiverModel.Reach reach = reaches.get(index);
                if (reach.lake()) continue;
                for (double t : SAMPLES) {
                    double tdX = lerp(reach.from().x(), reach.to().x(), t);
                    double tdZ = lerp(reach.from().z(), reach.to().z(), t);
                    int x = (int) Math.round(tdX * scale), z = (int) Math.round(tdZ * scale);
                    UndergroundRiverModel.Column c = model.column(x, z);
                    if (c == null || !c.wet()) continue;
                    double dx = reach.to().x() - reach.from().x(), dz = reach.to().z() - reach.from().z();
                    double length = Math.max(1e-9, Math.hypot(dx, dz));
                    double nx = -dz / length, nz = dx / length;
                    // Rounding a shallow transverse vector can revisit one
                    // raster cell.  Count each physical cell once across both
                    // banks; this is an audit measurement, not a second path
                    // through a nearby bend.
                    var visited = new java.util.HashSet<Long>();
                    visited.add(key(x, z));
                    int left = sideWidth(model, x, z, nx, nz, -1, visited);
                    int right = sideWidth(model, x, z, nx, nz, 1, visited);
                    out.add(new Row(seed, scale, cellX, cellZ, index, reach.discharge(), t, x, z,
                            left + right + 1, left, right, c.water() - c.bed(), c.roof() - c.water(),
                            c.roof(), c.water(), c.bed(), c.lake()));
                }
            }
        }
    }

    private static int sideWidth(UndergroundRiverModel model, int x, int z, double nx, double nz, int sign,
                                 java.util.Set<Long> visited) {
        int count = 0;
        for (int offset = 1; offset <= 24; offset++) {
            int sampleX = (int) Math.round(x + nx * offset * sign);
            int sampleZ = (int) Math.round(z + nz * offset * sign);
            UndergroundRiverModel.Column c = model.column(sampleX, sampleZ);
            if (c == null || !c.wet()) break;
            if (!visited.add(key(sampleX, sampleZ))) continue;
            count++;
        }
        return count;
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private static void writeSections(Path path, List<Row> rows) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(path)) {
            out.write("seed,scale,cell_x,cell_z,reach,discharge,t,x,z,wet_width,left_wet,right_wet,side_asymmetry,bed_depth,headroom,bed,water,roof\n");
            for (Row r : rows) out.write(String.format(Locale.ROOT,
                    "%d,%d,%d,%d,%d,%.6f,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d\n",
                    r.seed, r.scale, r.cellX, r.cellZ, r.reach, r.q, r.t, r.x, r.z, r.width,
                    r.left, r.right, r.right - r.left, r.bedDepth, r.headroom, r.bed, r.water, r.roof));
        }
    }

    private static void writeSummary(Path path, List<Row> rows) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(path)) {
            out.write("Pure-model section audit; it does not prove native cave appearance.\n");
            for (int scale : new int[]{2, 6}) {
                List<Row> selected = rows.stream().filter(r -> r.seed == FIXTURE_SEED && r.scale == scale).toList();
                if (selected.isEmpty()) continue;
                IntSummary width = IntSummary.of(selected.stream().mapToInt(Row::width).toArray());
                IntSummary height = IntSummary.of(selected.stream().mapToInt(Row::headroom).toArray());
                IntSummary asymmetry = IntSummary.of(selected.stream().mapToInt(r -> Math.abs(r.right - r.left)).toArray());
                out.write("fixture scale=" + scale + " samples=" + selected.size()
                        + " wetWidth=" + width + " headroom=" + height + " absSideAsymmetry=" + asymmetry + "\n");
            }
            List<Row> player = rows.stream().filter(r -> r.seed == PLAYER_SEED).toList();
            out.write("player-seed cell(-3,3) scale=2 samples=" + player.size() + " (diagnostic only)\n");
        }
    }

    /** Derives cameras from actual public Reach endpoint topology, not guessed coordinates. */
    private static void writeConfluenceCameras(Path path) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(path)) {
            out.write("seed,scale,cell_x,cell_z,join_td_x,join_td_y,join_td_z,incoming,outgoing,camera_x,camera_y,camera_z,yaw,look_x,look_y,look_z\n");
            for (long seed : new long[]{FIXTURE_SEED, PLAYER_SEED}) for (int scale : new int[]{2, 6}) {
                UndergroundRiverModel model = new UndergroundRiverModel(seed, scale, 2);
                for (int cx = -5; cx <= 4; cx++) for (int cz = -4; cz <= 4; cz++) {
                    List<UndergroundRiverModel.Reach> reaches = model.reaches(cx, cz);
                    Map<Key, List<UndergroundRiverModel.Reach>> incoming = new HashMap<>(), outgoing = new HashMap<>();
                    for (UndergroundRiverModel.Reach r : reaches) if (!r.lake()) {
                        incoming.computeIfAbsent(Key.of(r.to()), ignored -> new ArrayList<>()).add(r);
                        outgoing.computeIfAbsent(Key.of(r.from()), ignored -> new ArrayList<>()).add(r);
                    }
                    for (Map.Entry<Key, List<UndergroundRiverModel.Reach>> entry : incoming.entrySet()) {
                        List<UndergroundRiverModel.Reach> ins = entry.getValue(), outs = outgoing.get(entry.getKey());
                        if (ins.size() < 2 || outs == null || outs.isEmpty()) continue;
                        UndergroundRiverModel.Reach downstream = outs.getFirst();
                        Key join = entry.getKey();
                        double dx = downstream.to().x() - join.x, dz = downstream.to().z() - join.z;
                        double length = Math.max(1e-9, Math.hypot(dx, dz));
                        // Look upstream from a modest physical distance downstream.
                        double distanceTd = 9.0 / scale;
                        double camTdX = join.x + dx / length * distanceTd, camTdZ = join.z + dz / length * distanceTd;
                        int lookX = (int) Math.round(join.x * scale), lookZ = (int) Math.round(join.z * scale);
                        UndergroundRiverModel.Column column = model.column(lookX, lookZ);
                        if (column == null || !column.wet()) continue;
                        double cameraX = camTdX * scale + .5, cameraZ = camTdZ * scale + .5;
                        double yaw = Math.toDegrees(Math.atan2(cameraX - (lookX + .5), -(cameraZ - (lookZ + .5))));
                        out.write(String.format(Locale.ROOT,
                                "%d,%d,%d,%d,%.3f,%.3f,%.3f,%d,%d,%.2f,%.2f,%.2f,%.2f,%d,%d,%d\n",
                                seed, scale, cx, cz, join.x, join.y, join.z, ins.size(), outs.size(), cameraX,
                                column.water() + 1.6, cameraZ, yaw, lookX, column.water(), lookZ));
                    }
                }
            }
        }
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private record IntSummary(int min, int max, double mean) {
        static IntSummary of(int[] values) {
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE; long total = 0;
            for (int value : values) { min = Math.min(min, value); max = Math.max(max, value); total += value; }
            return new IntSummary(min, max, total / (double) values.length);
        }
        @Override public String toString() { return String.format(Locale.ROOT, "[%d..%d; mean=%.2f]", min, max, mean); }
    }
}
