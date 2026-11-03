package com.github.xandergos.terraindiffusionmc.world.geology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic spring-fed cave catchments. Coordinates are sea-level anchored TD units. */
public final class UndergroundRiverModel {
    public static final int CELL = 320;
    private final long seed;
    private final int scale;
    private final GeologyModel geology;
    private final Map<Long, List<Reach>> basins = new LinkedHashMap<>(8, .75f, true);

    public UndergroundRiverModel(long seed, int scale) {
        if (scale < 1 || scale > 6) throw new IllegalArgumentException("scale must be 1..6");
        this.seed = seed;
        this.scale = scale;
        this.geology = new GeologyModel(seed, -400, 1);
    }

    public record Point(double x, double y, double z) { }
    /** Head elevations decrease along every reach. Lakes are terminal local reservoirs. */
    public record Reach(Point from, Point to, double widthFrom, double widthTo, boolean lake, double discharge) { }
    /** Inclusive solid bed and dry roof; water occupies bed+1..water in a wet column. */
    public record Column(int bed, int water, int roof, int liningBottom,
                         double flowX, double flowZ, boolean wet, boolean lake) {
        public boolean fits(int minY, int surfaceY) {
            return liningBottom > minY + 8 && roof < surfaceY - GeologyModel.MIN_ROOF;
        }
    }

    public Column column(int blockX, int blockZ) {
        double x = blockX / (double) scale, z = blockZ / (double) scale;
        int cx = (int) Math.floor(x / CELL), cz = (int) Math.floor(z / CELL);
        Candidate best = null;
        double sealBottom = Double.POSITIVE_INFINITY;
        // Paths remain inside their own cell. A halo also covers their corridor/bank radii.
        for (int gx = cx - 1; gx <= cx + 1; gx++) for (int gz = cz - 1; gz <= cz + 1; gz++) {
            for (Reach reach : reaches(gx, gz)) {
                Candidate next = sample(reach, x, z);
                if (next == null) continue;
                sealBottom = Math.min(sealBottom, next.head - 8);
                // Water takes precedence over a neighbouring dry bank at confluences.
                // Within overlapping wet channels the lower head is the downstream receiver.
                if (best == null || (next.wet && !best.wet)
                        || next.wet == best.wet && ((next.wet ? next.head < best.head - 1e-9 : next.head > best.head + 1e-9)
                        || Math.abs(next.head - best.head) < 1e-9 && next.bed < best.bed)) best = next;
            }
        }
        if (best == null) return null;
        int water = (int) Math.floor(63 + best.head * scale);
        int bed = (int) Math.floor(63 + best.bed * scale);
        int roof = (int) Math.floor(63 + best.roof * scale);
        return new Column(bed, water, roof, Math.min(bed - 2, (int) Math.floor(63 + sealBottom * scale)),
                best.flowX, best.flowZ, best.wet && bed < water, best.lake);
    }

    private Candidate sample(Reach reach, double x, double z) {
        double dx = reach.to.x - reach.from.x, dz = reach.to.z - reach.from.z;
        double length2 = dx * dx + dz * dz;
        double t = length2 < 1e-9 ? 0 : Math.clamp(((x - reach.from.x) * dx + (z - reach.from.z) * dz) / length2, 0, 1);
        double px = reach.from.x + dx * t, pz = reach.from.z + dz * t;
        double distance = Math.hypot(x - px, z - pz);
        double width = reach.widthFrom + (reach.widthTo - reach.widthFrom) * t;
        double side = dx * (z - pz) - dz * (x - px);
        width *= 1 + .12 * Math.sin(px * .021 + pz * .017) * Math.tanh(side / Math.max(1, Math.sqrt(length2) * width));
        double bank = reach.lake ? 4 : 3.5;
        if (distance > width + bank) return null;
        double head = reach.from.y + (reach.to.y - reach.from.y) * t;
        boolean wet = distance < width;
        double fraction = distance / width;
        double depth = reach.lake ? 3 + width * .13 : 1.0 + Math.sqrt(reach.discharge) * .48;
        double bed = wet ? head - Math.max(.6, depth * (1 - fraction * fraction))
                : head + 1 + (distance - width) * .35;
        double roof = head + (reach.lake ? 13 : 8 + width * .6)
                - distance / (width + bank) * 2 + Math.sin(x * .045 + z * .031) * 1.3;
        double length = Math.sqrt(length2);
        double slope = (reach.from.y - reach.to.y) / Math.max(1, length);
        // A discharge proxy, not a pressure solver: narrow/steep runs are faster than broad pools.
        double speed = reach.lake || length < 1e-9 ? 0
                : Math.clamp(.2 + reach.discharge / (width * depth) * .5 + Math.sqrt(Math.max(0, slope)) * .38, .15, .95);
        double vx = dx / Math.max(1, length) + (px - x) / width * .6;
        double vz = dz / Math.max(1, length) + (pz - z) / width * .6;
        double magnitude = Math.max(1e-9, Math.hypot(vx, vz));
        return new Candidate(head, bed, roof, vx / magnitude * speed,
                vz / magnitude * speed, wet, reach.lake);
    }

    /** Immutable geometry, also used by diagnostics to select a representative fresh fixture. */
    public List<Reach> reaches(int cellX, int cellZ) {
        long key = ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
        List<Reach> cached = basins.get(key);
        if (cached != null) return cached;
        List<Reach> result = new ArrayList<>();
        double cx = cellX * (double) CELL + CELL / 2.0;
        double cz = cellZ * (double) CELL + CELL / 2.0;
        var context = geology.riverContext(cx, -100, cz);
        boolean carbonate = context.rock() == GeologyModel.ROCK_CALCITE;
        double permeability = carbonate ? 1 : context.rock() == GeologyModel.ROCK_SANDSTONE ? .72 : .4;
        if (unit(cellX, cellZ, 1) < .45 + permeability * .22 + context.fracture() * .18) {
            double angle = context.faultBearing() + (unit(cellX, cellZ, 2) - .5) * 1.4;
            if (unit(cellX, cellZ, 6) < .5) angle += Math.PI;
            double joinY = -155 - unit(cellX, cellZ, 3) * 22;
            double lakeY = joinY - 32 - unit(cellX, cellZ, 4) * 20;
            Point join = point(cx, cz, angle, 0, joinY, 0);
            Point lake = point(cx, cz, angle, (unit(cellX, cellZ, 5) - .5) * 30, lakeY, 94);
            int branches = 1 + (int) (unit(cellX, cellZ, 7) * (carbonate ? 4 : 3));
            double discharge = 0;
            for (int branch = 0; branch < branches; branch++) {
                double offset = branches == 1 ? 0 : (branch / (double) (branches - 1) - .5) * 122
                        + (unit(cellX, cellZ, 10 + branch) - .5) * 12;
                Point spring = point(cx, cz, angle, offset,
                        joinY + 35 + unit(cellX, cellZ, 20 + branch) * 27,
                        -70 - unit(cellX, cellZ, 30 + branch) * 25);
                double q = (.7 + unit(cellX, cellZ, 40 + branch) * 2.3) * (.65 + permeability * .35);
                discharge += q;
                double width = 1.25 + Math.sqrt(q) * .8;
                curve(result, spring, join, width * .88, width,
                        (unit(cellX, cellZ, 50 + branch) - .5) * 22, q);
            }
            double trunkWidth = 1.3 + Math.sqrt(discharge) * .95;
            curve(result, join, lake, trunkWidth, trunkWidth * 1.12,
                    (unit(cellX, cellZ, 60) - .5) * 38, discharge);
            // A closed local reservoir represents storage and seepage, not a river to world bedrock.
            boolean broadLake = carbonate && unit(cellX, cellZ, 62) < .4;
            double radius = broadLake ? 10 + unit(cellX, cellZ, 61) * 5 : 4.5 + Math.sqrt(discharge);
            result.add(new Reach(lake, lake, radius, radius, true, discharge));
            // Overlapping dissolution pockets break the universal circular reservoir silhouette.
            for (int lobe = 0; lobe < (broadLake ? 3 : 1); lobe++) {
                double a = unit(cellX, cellZ, 70 + lobe) * Math.PI * 2;
                Point pocket = new Point(lake.x + Math.cos(a) * radius * .65, lake.y,
                        lake.z + Math.sin(a) * radius * .65);
                result.add(new Reach(pocket, pocket, radius * .62, radius * .62, true, discharge));
            }
        }
        List<Reach> immutable = List.copyOf(result);
        basins.put(key, immutable);
        if (basins.size() > 16) basins.remove(basins.keySet().iterator().next());
        return immutable;
    }

    private static Point point(double cx, double cz, double angle, double x, double y, double z) {
        return new Point(cx + x * Math.cos(angle) - z * Math.sin(angle), y,
                cz + x * Math.sin(angle) + z * Math.cos(angle));
    }

    private static void curve(List<Reach> result, Point a, Point b, double startWidth, double endWidth, double bend, double discharge) {
        double dx = b.x - a.x, dz = b.z - a.z, length = Math.hypot(dx, dz);
        Point previous = a;
        double previousWidth = startWidth;
        for (int step = 1; step <= 16; step++) {
            double t = step / 16.0;
            double meander = Math.sin(t * Math.PI * 2) * Math.sin(t * Math.PI) * bend;
            // Alternating short level pools and descending runs; native water can spill
            // over each step while the planned source-filled channel remains bounded.
            double descent = Math.floor(step / 2.0) / 8.0;
            Point next = new Point(a.x + dx * t - dz / length * meander,
                    a.y + (b.y - a.y) * descent, a.z + dz * t + dx / length * meander);
            double width = startWidth + (endWidth - startWidth) * t;
            result.add(new Reach(previous, next, previousWidth, width, false, discharge));
            previous = next;
            previousWidth = width;
        }
    }

    private double unit(long x, long z, long salt) {
        long h = seed + salt * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L;
        h ^= h >>> 30; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 27; h *= 0x94D049BB133111EBL;
        return ((h ^ (h >>> 31)) >>> 11) * 0x1.0p-53;
    }

    private record Candidate(double head, double bed, double roof, double flowX, double flowZ,
                             boolean wet, boolean lake) { }
}
