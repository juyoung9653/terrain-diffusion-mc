import com.github.xandergos.terraindiffusionmc.world.geology.GeologyModel;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports directly sampled GeologyModel slices.  It deliberately has no game,
 * registry, or image-library dependency so a model revision can be inspected
 * with javac before a Minecraft launch.
 */
public final class GeologyAtlas {
    private static final long[] SEEDS = {91357L, 0L, -71421L};
    private static final int[] SCALES = {1, 2, 6};
    private static final int[] FLOORS = {-400, -736, -1072, -1408, -1744, -1968};
    private static final int[] PROFILE_FLOORS = {FLOORS[0], FLOORS[1], FLOORS[FLOORS.length - 1]};
    private static final double[] FRACTIONS = {.10, .25, .40, .55, .70, .85};
    private static final int SURFACE_BASE = 160;
    private static final int CROSS_RADIUS_BASE = 600;
    private static final int PLAN_PIXELS = 401;

    private GeologyAtlas() { }

    public static void main(String[] args) throws IOException {
        Path output = args.length == 0 ? Path.of("build", "geology-atlas-v2") : Path.of(args[0]);
        Files.createDirectories(output);
        List<Entry> entries = new ArrayList<>();
        // These are the actual production profiles, rather than a Cartesian
        // matrix of incompatible floor/scale combinations.
        for (long seed : SEEDS) for (int index = 0; index < SCALES.length; index++)
            entries.add(export(output, seed, PROFILE_FLOORS[index], SCALES[index]));
        writeManifest(output.resolve("atlas.json"), entries);
        System.out.printf("Geology atlas: %d model samples in %s%n", entries.size(), output);
    }

    private static Entry export(Path output, long seed, int minY, int scale) throws IOException {
        int surfaceY = SURFACE_BASE * scale;
        int radius = CROSS_RADIUS_BASE * scale;
        int width = radius * 2 / scale + 1;
        int height = (surfaceY - minY) / scale + 1;
        String id = "seed_" + seed + "_scale_" + scale + "_min_" + minY;
        GeologyModel model = new GeologyModel(seed, minY, scale);

        Path cross = output.resolve(id + "_cross.bin");
        Counts counts = new Counts();
        try (OutputStream stream = Files.newOutputStream(cross)) {
            for (int row = 0; row < height; row++) {
                int y = surfaceY - row * scale;
                for (int column = 0; column < width; column++) {
                    int x = -radius + column * scale;
                    stream.write(sample(model.column(x, 0, surfaceY), y, counts));
                }
            }
        }

        List<Plane> planes = new ArrayList<>();
        int planStep = scale * 3;
        for (double fraction : FRACTIONS) {
            int requested = minY + (int) Math.round((surfaceY - minY) * fraction);
            int y = Math.max(minY + 9, Math.min(surfaceY - GeologyModel.MIN_ROOF - 1, requested));
            Path mask = output.resolve(id + "_plan_" + y + ".bin");
            byte[] data = new byte[PLAN_PIXELS * PLAN_PIXELS];
            int air = 0, water = 0;
            for (int row = 0; row < PLAN_PIXELS; row++) for (int column = 0; column < PLAN_PIXELS; column++) {
                int x = (column - PLAN_PIXELS / 2) * planStep;
                int z = (row - PLAN_PIXELS / 2) * planStep;
                GeologyModel.Column point = model.column(x, z, surfaceY);
                int cavity = point.cavity(y);
                data[row * PLAN_PIXELS + column] = (byte) (cavity == 0 ? point.material(y) : cavity == 1 ? 30 : 31);
                if (cavity == 1) air++;
                else if (cavity == 2) water++;
            }
            Files.write(mask, data);
            planes.add(new Plane(y, mask.getFileName().toString(), air, water, largestComponent(data)));
        }
        Projection projection = null;
        // A union through the carbonate band exposes directional repetition that
        // a single one-block horizontal slice cannot show. One representative
        // profile keeps this diagnostic bounded.
        if (seed == 91357L && scale == 2) {
            int low = GeologyModel.SEA_LEVEL - 140 * scale, high = GeologyModel.SEA_LEVEL - 28 * scale;
            Path file = output.resolve(id + "_carbonate-cave-projection.bin");
            byte[] data = new byte[PLAN_PIXELS * PLAN_PIXELS];
            for (int row = 0; row < PLAN_PIXELS; row++) for (int column = 0; column < PLAN_PIXELS; column++) {
                int x = (column - PLAN_PIXELS / 2) * planStep, z = (row - PLAN_PIXELS / 2) * planStep;
                GeologyModel.Column point = model.column(x, z, surfaceY);
                for (int y = low; y <= high; y += scale) if (point.cavity(y) != 0) { data[row * PLAN_PIXELS + column] = 30; break; }
            }
            Files.write(file, data);
            projection = new Projection(low, high, file.getFileName().toString());
        }
        return new Entry(id, seed, scale, minY, surfaceY, width, height, cross.getFileName().toString(),
                planStep, counts, planes, projection);
    }

    private static int sample(GeologyModel.Column column, int y, Counts counts) {
        int cavity = column.cavity(y);
        if (cavity == 1) { counts.air++; return 30; }
        if (cavity == 2) { counts.water++; return 31; }
        int material = column.material(y);
        if (GeologyModel.isOre(material)) counts.ores++;
        else counts.rock++;
        return material;
    }

    /** Largest 4-neighbour connected cave region in one sampled plan, in pixels. */
    private static int largestComponent(byte[] values) {
        boolean[] seen = new boolean[values.length];
        int largest = 0;
        for (int start = 0; start < values.length; start++) {
            int value = Byte.toUnsignedInt(values[start]);
            if (seen[start] || (value != 30 && value != 31)) continue;
            int size = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start); seen[start] = true;
            while (!queue.isEmpty()) {
                int current = queue.removeFirst(); size++;
                int x = current % PLAN_PIXELS, z = current / PLAN_PIXELS;
                if (x > 0) enqueue(values, seen, queue, current - 1);
                if (x + 1 < PLAN_PIXELS) enqueue(values, seen, queue, current + 1);
                if (z > 0) enqueue(values, seen, queue, current - PLAN_PIXELS);
                if (z + 1 < PLAN_PIXELS) enqueue(values, seen, queue, current + PLAN_PIXELS);
            }
            largest = Math.max(largest, size);
        }
        return largest;
    }

    private static void enqueue(byte[] values, boolean[] seen, ArrayDeque<Integer> queue, int index) {
        int value = Byte.toUnsignedInt(values[index]);
        if (!seen[index] && (value == 30 || value == 31)) { seen[index] = true; queue.add(index); }
    }

    private static void writeManifest(Path file, List<Entry> entries) throws IOException {
        StringBuilder json = new StringBuilder("{\n  \"version\": 2,\n")
                .append("  \"sampling\": {\"crossSection\": \"z=0; one pixel equals scale blocks on both axes\", ")
                .append("\"plan\": \"square z/x cave masks; 3*scale blocks per pixel\"},\n  \"entries\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            json.append("    {\"id\":\"").append(e.id).append("\",\"seed\":").append(e.seed)
                    .append(",\"scale\":").append(e.scale).append(",\"minY\":").append(e.minY)
                    .append(",\"surfaceY\":").append(e.surfaceY).append(",\"cross\":{\"file\":\"")
                    .append(e.crossFile).append("\",\"width\":").append(e.width).append(",\"height\":").append(e.height)
                    .append("},\"planStep\":").append(e.planStep).append(",\"crossCounts\":{\"rock\":")
                    .append(e.counts.rock).append(",\"ore\":").append(e.counts.ores).append(",\"air\":")
                    .append(e.counts.air).append(",\"water\":").append(e.counts.water).append("},\"planes\":[");
            for (int p = 0; p < e.planes.size(); p++) {
                Plane plane = e.planes.get(p);
                if (p != 0) json.append(',');
                json.append("{\"y\":").append(plane.y).append(",\"file\":\"").append(plane.file)
                        .append("\",\"airPixels\":").append(plane.air).append(",\"waterPixels\":")
                        .append(plane.water).append(",\"largestCaveComponentPixels\":").append(plane.largest).append('}');
            }
            json.append("],\"carbonateCaveProjection\":");
            if (e.projection == null) json.append("null");
            else json.append("{\"minY\":").append(e.projection.low).append(",\"maxY\":")
                    .append(e.projection.high).append(",\"file\":\"").append(e.projection.file).append("\"}");
            json.append('}').append(i + 1 == entries.size() ? '\n' : ",\n");
        }
        json.append("  ]\n}\n");
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static final class Counts { long rock, ores, air, water; }
    private record Plane(int y, String file, int air, int water, int largest) { }
    private record Projection(int low, int high, String file) { }
    private record Entry(String id, long seed, int scale, int minY, int surfaceY, int width, int height,
                         String crossFile, int planStep, Counts counts, List<Plane> planes, Projection projection) { }
}
