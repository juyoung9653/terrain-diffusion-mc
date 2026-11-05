package com.github.xandergos.terraindiffusionmc.world.geology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small, local cave streams. Geometry uses TD coordinates; section widths use blocks. */
public final class UndergroundRiverModel {
    public static final int CELL = 320;
    private final long seed;
    private final int scale;
    private final int version;
    private final GeologyModel geology;
    private final LegacyUndergroundRiverModel legacy;
    private final Map<Long, List<Reach>> basins = new LinkedHashMap<>(16, .75f, true);

    public UndergroundRiverModel(long seed, int scale) { this(seed, scale, 2); }
    public UndergroundRiverModel(long seed, int scale, int version) {
        if (scale < 1 || scale > 6 || (version != 1 && version != 2 && version != 3))
            throw new IllegalArgumentException("Invalid underground river scale/version");
        this.seed = seed; this.scale = scale; this.version = version;
        // V3 shares its rock provinces and fracture field with the cave pass.
        // V1/V2 must retain their original model identity for saved worlds.
        this.geology = version == 2 ? new GeologyModel(seed, -400, 1)
                : version == 3 ? new GeologyModel(seed, -400, 1, 2) : null;
        this.legacy = version == 1 ? new LegacyUndergroundRiverModel(seed, scale) : null;
    }

    public record Point(double x, double y, double z) { }
    public record Reach(Point from, Point to, double widthFrom, double widthTo,
                        boolean lake, double discharge) { }
    public record Column(int bed, int water, int roof, int liningBottom,
                         double flowX, double flowZ, boolean wet, boolean lake) {
        public boolean fits(int minY, int surfaceY) {
            return liningBottom > minY + 8 && roof < surfaceY - GeologyModel.MIN_ROOF;
        }
    }

    public Column column(int blockX, int blockZ) {
        if (version == 1) return legacyColumn(blockX, blockZ);
        Candidate current = rawCandidate(blockX, blockZ);
        Column raw = toColumn(current);
        if (raw == null) return null;
        if (!raw.wet) return sealBank(blockX, blockZ, raw);
        if (raw.lake || Math.hypot(raw.flowX, raw.flowZ) < 1e-9) return raw;

        double speed = Math.hypot(raw.flowX, raw.flowZ);
        int forwardX = (int) Math.round(raw.flowX / speed);
        int forwardZ = (int) Math.round(raw.flowZ / speed);
        Candidate forwardCandidate = rawCandidate(blockX + forwardX, blockZ + forwardZ);
        Column forward = toColumn(forwardCandidate);
        if (isDownstreamWet(forward, forwardCandidate, raw, current, false)) return raw;

        // A one-block simulation step has to remain in the water raster.  This
        // only handles the rare rounding/corner case after the continuous flow
        // calculation above; it does not scan neighbors for ordinary columns.
        int bestX = 0, bestZ = 0;
        double bestScore = -Double.MAX_VALUE;
        for (int stepX = -1; stepX <= 1; stepX++) for (int stepZ = -1; stepZ <= 1; stepZ++) {
            if (stepX == 0 && stepZ == 0) continue;
            Candidate nextCandidate = rawCandidate(blockX + stepX, blockZ + stepZ);
            Column next = toColumn(nextCandidate);
            if (!isDownstreamWet(next, nextCandidate, raw, current, true)) continue;
            double length = Math.hypot(stepX, stepZ);
            double score = (stepX * raw.flowX + stepZ * raw.flowZ) / (length * speed)
                    + (raw.water - next.water) * .08;
            if (score > bestScore) { bestScore = score; bestX = stepX; bestZ = stepZ; }
        }
        if (bestScore == -Double.MAX_VALUE) return new Column(raw.bed, raw.water, raw.roof,
                raw.liningBottom, 0, 0, true, false);
        double length = Math.hypot(bestX, bestZ);
        return new Column(raw.bed, raw.water, raw.roof, raw.liningBottom,
                bestX / length * speed, bestZ / length * speed, true, false);
    }

    private Column sealBank(int x, int z, Column bank) {
        // A bank may sit flush with its water surface instead of always being a
        // raised parallel walkway. At a descending step, retain enough existing
        // rock to contain the higher adjacent column. This samples the pure model
        // only; it never requests neighboring chunks or recursively calls column().
        int bed = bank.bed;
        for (int side = 0; side < 4; side++) {
            int dx = side == 0 ? -1 : side == 1 ? 1 : 0;
            int dz = side == 2 ? -1 : side == 3 ? 1 : 0;
            Column neighbor = toColumn(rawCandidate(x + dx, z + dz));
            if (neighbor != null && neighbor.wet) bed = Math.max(bed, neighbor.water);
        }
        if (bed >= bank.roof) return null;
        return new Column(bed, bank.water, bank.roof, bed - 1, 0, 0, false, bank.lake);
    }

    private static boolean isDownstreamWet(Column next, Candidate nextCandidate,
                                           Column current, Candidate currentCandidate, boolean strictHead) {
        return next != null && next.wet && next.water <= current.water
                && (strictHead ? nextCandidate.head < currentCandidate.head - 1e-5
                               : nextCandidate.head <= currentCandidate.head + 1e-9);
    }

    private Candidate rawCandidate(int blockX, int blockZ) {
        double x = blockX / (double) scale, z = blockZ / (double) scale;
        int cx = floor(x / CELL), cz = floor(z / CELL);
        Candidate best = null;
        for (int gx = cx - 1; gx <= cx + 1; gx++) for (int gz = cz - 1; gz <= cz + 1; gz++) {
            for (Reach reach : reaches(gx, gz)) {
                Candidate candidate = sample(reach, x, z);
                if (candidate == null || (best != null && !prefer(candidate, best))) continue;
                best = candidate;
            }
        }
        return best;
    }

    private Column toColumn(Candidate best) {
        if (best == null) return null;
        int water = floor(63 + best.head * scale);
        int bed = floor(water - best.depth);
        int roof = floor(water + best.height);
        if (roof <= bed) return null;
        return new Column(bed, water, roof, bed - 1, best.flowX, best.flowZ, best.wet, best.lake);
    }

    private boolean prefer(Candidate next, Candidate previous) {
        if (next.wet != previous.wet) return next.wet;
        if (Math.abs(next.head - previous.head) < 1e-9 && next.lake != previous.lake) return next.lake;
        return next.wet ? next.head < previous.head : next.head > previous.head;
    }

    private Candidate sample(Reach reach, double x, double z) {
        double dx = reach.to.x - reach.from.x, dz = reach.to.z - reach.from.z;
        double len2 = dx * dx + dz * dz;
        double t = len2 < 1e-9 ? 0 : clamp(((x - reach.from.x) * dx + (z - reach.from.z) * dz) / len2, 0, 1);
        double px = reach.from.x + dx * t, pz = reach.from.z + dz * t;
        double physicalDistance = Math.hypot(x - px, z - pz) * scale;
        double section = 1 + .10 * (scale - 1);
        double baseRadius = (reach.widthFrom + (reach.widthTo - reach.widthFrom) * t) * section;
        // Reject distant segments before evaluating the bounded local section field.
        if (physicalDistance > baseRadius * 1.45 + 3.5) return null;
        double length = Math.sqrt(len2);
        double directionX = length < 1e-9 ? 0 : dx / length;
        double directionZ = length < 1e-9 ? 0 : dz / length;
        double stationX = px * scale, stationZ = pz * scale;
        // Broad, continuous rock resistance: adjacent reaches share endpoint values.
        // No repeated sine pattern, per-block jitter, or chunk-local random state.
        double widthField = smoothField(stationX, stationZ, 17, 701);
        double bankField = smoothField(stationX, stationZ, 9, 702);
        double roofField = smoothField(x * scale, z * scale, 7, 703);
        double bedField = smoothField(x * scale, z * scale, 5, 704);
        double signedDistance = ((x - px) * -directionZ + (z - pz) * directionX) * scale;
        double bankBias = Math.tanh(2.8 * bankField);
        double lateralShift = 1.25 * bankBias;
        px -= directionZ * lateralShift / scale;
        pz += directionX * lateralShift / scale;
        physicalDistance = Math.hypot(x - px, z - pz) * scale;
        signedDistance -= lateralShift;
        double side = Math.tanh(signedDistance);
        double asymmetry = .26 * bankBias * side;
        double radius = Math.max(.95, Math.min(3.1 * section,
                baseRadius * (1 + .22 * widthField + asymmetry)));
        // Leave exposed, irregular rock shelves beside the wet cut. Closing the
        // roof immediately at the water edge produced a narrow rectangular duct.
        // This extra room is physical blocks, never multiplied by the TD scale.
        // A cut bank opposes a small depositional shelf. Using the same margin
        // on both sides made two parallel stone walkways after rasterization.
        double outer = radius + clamp(1.25 + 1.15 * bankBias * side + .45 * bedField, .35, 2.6);
        if (physicalDistance >= outer) return null;
        double arch = Math.sqrt(Math.max(0, 1 - physicalDistance * physicalDistance / (outer * outer)));
        boolean wet = physicalDistance < radius;
        double head = reach.from.y + (reach.to.y - reach.from.y) * t;
        double depth = wet ? Math.max(.65, (1.2 + Math.min(1.1, Math.sqrt(reach.discharge) * .65))
                * arch + .45 * bedField) : 0;
        // The arch closes at its shoulders instead of leaving the former two-block
        // vertical wall everywhere. Independent roof relief avoids a flat tunnel lid.
        double height = (3.2 + Math.min(1.3, Math.sqrt(reach.discharge) * .7)
                + 1.7 * roofField + .4 * smoothField(x * scale, z * scale, 3.5, 705)) * arch;
        if (wet) height = Math.max(1.15, height);
        if (!wet) {
            double shoulderBed = Math.max(0, .35 + .65 * (physicalDistance - radius) + .8 * bedField);
            if (height < Math.floor(shoulderBed) + 1) return null;
            depth = -shoulderBed;
        }
        // x/z are terrain-domain units while the section radius is physical
        // blocks.  Near a water edge, turn decisively toward its centerline so
        // a rounded one-block current step cannot immediately leave the stream.
        // The gain remains below one, preserving a downstream tangent component.
        double radial = physicalDistance / Math.max(1, radius);
        double centerGain = .12 + .87 * radial * radial;
        double centeredX = directionX + (px - x) * scale / Math.max(1, radius) * centerGain;
        double centeredZ = directionZ + (pz - z) * scale / Math.max(1, radius) * centerGain;
        double magnitude = Math.max(1e-9, Math.hypot(centeredX, centeredZ));
        double speed = wet && !reach.lake ? clamp(.12 + Math.sqrt(reach.discharge) * .18, .12, .72) : 0;
        return new Candidate(head, depth, height, centeredX / magnitude * speed,
                centeredZ / magnitude * speed, wet, reach.lake);
    }

    public List<Reach> reaches(int cellX, int cellZ) {
        if (version == 1) return legacyReaches(cellX, cellZ);
        long key = ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
        List<Reach> cached = basins.get(key);
        if (cached != null) return cached;
        List<Reach> made = version == 3 ? makeHydroGeologicBasin(cellX, cellZ) : makeBasin(cellX, cellZ);
        basins.put(key, made);
        if (basins.size() > 16) basins.remove(basins.keySet().iterator().next());
        return made;
    }

    private List<Reach> makeBasin(int cellX, int cellZ) {
        double centerX = cellX * (double) CELL + CELL / 2.0 + (unit(cellX, cellZ, 1) - .5) * 80;
        double centerZ = cellZ * (double) CELL + CELL / 2.0 + (unit(cellX, cellZ, 2) - .5) * 80;
        var context = geology.riverContext(centerX, -145, centerZ);
        boolean carbonate = context.rock() == GeologyModel.ROCK_CALCITE;
        double occurrence = carbonate ? .62 : .38;
        occurrence += context.fracture() * .12;
        if (unit(cellX, cellZ, 3) >= occurrence) return List.of();

        double angle = context.faultBearing() + (unit(cellX, cellZ, 4) - .5) * 1.25;
        double length = 45 + unit(cellX, cellZ, 5) * 105;
        int nodeCount = 3 + (int) (unit(cellX, cellZ, 6) * 3);
        double topY = -105 - unit(cellX, cellZ, 7) * 95;
        double fall = length * (.035 + unit(cellX, cellZ, 8) * .065);
        Point[] collector = new Point[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            double t = i / (double) (nodeCount - 1);
            if (i > 0 && i < nodeCount - 1) t = clamp(t + (unit(cellX, cellZ, 20 + i) - .5) * .18, .08, .92);
            double lateral = (unit(cellX, cellZ, 30 + i) - .5) * 38 * Math.sin(t * Math.PI);
            collector[i] = point(centerX, centerZ, angle, length * (t - .5), lateral, topY - fall * t);
        }
        List<Reach> result = new ArrayList<>();
        int tributaries = (int) (unit(cellX, cellZ, 9) * 4);
        double[] added = new double[nodeCount];
        for (int branch = 0; branch < tributaries; branch++) {
            // Leave a downstream collector after every join. A branch terminating
            // directly in the endpoint pool made a broad, blunt bathtub-shaped cap.
            int join = 1 + (int) (unit(cellX, cellZ, 40 + branch) * (nodeCount - 2));
            double q = .1 + Math.pow(unit(cellX, cellZ, 50 + branch), 2) * 2.8;
            double distance = 25 + unit(cellX, cellZ, 80 + branch) * 65;
            double gain = distance * (.04 + unit(cellX, cellZ, 60 + branch) * .10);
            double sourceY = clamp(collector[join].y + gain, -200, -105);
            // Sources lie upstream and to one side; the old downstream placement
            // made tributaries reverse direction at their confluence.
            double side = unit(cellX, cellZ, 70 + branch) < .5 ? -1 : 1;
            double sourceAngle = angle + Math.PI + side * (.5 + unit(cellX, cellZ, 75 + branch) * .8);
            Point source = new Point(collector[join].x + Math.cos(sourceAngle) * distance,
                    sourceY, collector[join].z + Math.sin(sourceAngle) * distance);
            curve(result, source, collector[join], radius(q), radius(q), q, cellX, cellZ, 100 + branch);
            added[join] += q;
        }
        // A small spring is the collector's headwater; tributary discharge is added once,
        // at its chosen intermediate node, and carried unchanged downstream.
        double flow = .1 + added[0];
        double entryRadius = radius(flow);
        for (int i = 0; i < nodeCount - 1; i++) {
            curve(result, collector[i], collector[i + 1], entryRadius, radius(flow),
                    Math.max(.1, flow), cellX, cellZ, 140 + i);
            entryRadius = radius(flow);
            flow += added[i + 1];
        }
        Point terminal = collector[nodeCount - 1];
        double poolRadius = radius(Math.max(.1, flow)) * (carbonate && unit(cellX, cellZ, 160) < .08 ? 1.7 : 1.05);
        result.add(new Reach(terminal, terminal, poolRadius, poolRadius, true, Math.max(.1, flow)));
        return List.copyOf(result);
    }

    /**
     * V3 keeps the compact, per-cell collector topology of V2, but chooses where
     * springs form from the same rock and fracture context that shapes caves.
     * This makes soluble/faulted provinces more likely to drain without making a
     * high-permeability province one uniformly wide channel.
     */
    private List<Reach> makeHydroGeologicBasin(int cellX, int cellZ) {
        double centerX = cellX * (double) CELL + CELL / 2.0 + (unit(cellX, cellZ, 1) - .5) * 80;
        double centerZ = cellZ * (double) CELL + CELL / 2.0 + (unit(cellX, cellZ, 2) - .5) * 80;
        var center = geology.riverContext(centerX, -145, centerZ);
        var northWest = geology.riverContext(centerX - 72, -145, centerZ - 72);
        var southEast = geology.riverContext(centerX + 72, -145, centerZ + 72);
        double fracture = (center.fracture() + northWest.fracture() + southEast.fracture()) / 3.0;
        int soluble = (center.rock() == GeologyModel.ROCK_CALCITE ? 1 : 0)
                + (northWest.rock() == GeologyModel.ROCK_CALCITE ? 1 : 0)
                + (southEast.rock() == GeologyModel.ROCK_CALCITE ? 1 : 0);
        // Low permeability cells can still have a sparse spring, while karst and
        // faulted areas receive a higher chance and one additional tributary.
        double occurrence = .12 + soluble * .13 + fracture * .29;
        if (unit(cellX, cellZ, 3) >= occurrence) return List.of();

        double angle = center.faultBearing() + (unit(cellX, cellZ, 4) - .5) * .82;
        double length = 52 + unit(cellX, cellZ, 5) * (72 + fracture * 42);
        int nodeCount = 3 + (int) (unit(cellX, cellZ, 6) * (2 + (soluble > 0 ? 1 : 0)));
        double topY = -104 - unit(cellX, cellZ, 7) * (72 + soluble * 18);
        double fall = length * (.038 + unit(cellX, cellZ, 8) * (.042 + fracture * .038));
        Point[] collector = new Point[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            double t = i / (double) (nodeCount - 1);
            if (i > 0 && i < nodeCount - 1)
                t = clamp(t + (unit(cellX, cellZ, 200 + i) - .5) * .14, .10, .90);
            double lateral = (unit(cellX, cellZ, 30 + i) - .5) * (24 + fracture * 20)
                    * Math.sin(t * Math.PI);
            collector[i] = point(centerX, centerZ, angle, length * (t - .5), lateral, topY - fall * t);
        }

        List<Reach> result = new ArrayList<>();
        int tributaries = (int) (unit(cellX, cellZ, 9) * (1 + soluble + (fracture > .55 ? 1 : 0)));
        double[] added = new double[nodeCount];
        for (int branch = 0; branch < tributaries; branch++) {
            int join = 1 + (int) (unit(cellX, cellZ, 40 + branch) * (nodeCount - 2));
            double q = .08 + Math.pow(unit(cellX, cellZ, 50 + branch), 2) * (1.45 + .35 * soluble);
            double distance = 24 + unit(cellX, cellZ, 80 + branch) * (42 + fracture * 34);
            double gain = distance * (.042 + unit(cellX, cellZ, 60 + branch) * .070);
            double sourceY = clamp(collector[join].y + gain, -210, -96);
            double side = unit(cellX, cellZ, 70 + branch) < .5 ? -1 : 1;
            double sourceAngle = angle + Math.PI + side * (.58 + unit(cellX, cellZ, 75 + branch) * .58);
            Point source = new Point(collector[join].x + Math.cos(sourceAngle) * distance, sourceY,
                    collector[join].z + Math.sin(sourceAngle) * distance);
            curve(result, source, collector[join], radius(q), radius(q), q, cellX, cellZ, 100 + branch);
            added[join] += q;
        }
        double flow = .08 + added[0];
        double entryRadius = radius(flow);
        for (int i = 0; i < nodeCount - 1; i++) {
            curve(result, collector[i], collector[i + 1], entryRadius, radius(flow),
                    Math.max(.08, flow), cellX, cellZ, 140 + i);
            entryRadius = radius(flow);
            flow += added[i + 1];
        }
        Point terminal = collector[nodeCount - 1];
        // A small terminal pool is allowed in karst only. It is still a local
        // feature, never a filled aquifer spanning the complete cave layer.
        double poolRadius = radius(Math.max(.08, flow))
                * (soluble >= 2 && unit(cellX, cellZ, 160) < .13 ? 1.45 : 1.0);
        result.add(new Reach(terminal, terminal, poolRadius, poolRadius, true, Math.max(.08, flow)));
        return List.copyOf(result);
    }

    private double radius(double q) { return Math.min(3.8, .85 + .85 * Math.sqrt(Math.max(0, q))); }

    private double smoothField(double x, double z, double wavelength, long salt) {
        x /= wavelength; z /= wavelength;
        int ix = floor(x), iz = floor(z);
        double fx = x - ix, fz = z - iz;
        fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
        double a = unit(ix, iz, salt), b = unit(ix + 1, iz, salt);
        double c = unit(ix, iz + 1, salt), d = unit(ix + 1, iz + 1, salt);
        return 2 * ((a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz) - 1;
    }

    private void curve(List<Reach> out, Point from, Point to, double firstRadius, double lastRadius,
                       double q, int cellX, int cellZ, int salt) {
        double dx = to.x - from.x, dz = to.z - from.z, length = Math.max(1, Math.hypot(dx, dz));
        double nx = -dz / length, nz = dx / length;
        double bendA = selectOffset(from, to, nx, nz, cellX, cellZ, salt);
        double bendB = selectOffset(from, to, nx, nz, cellX, cellZ, salt + 20);
        Point a = new Point(from.x + dx / 3 + nx * bendA, from.y, from.z + dz / 3 + nz * bendA);
        Point b = new Point(from.x + dx * 2 / 3 + nx * bendB, to.y, from.z + dz * 2 / 3 + nz * bendB);
        int parts = Math.max(4, Math.min(24, (int) Math.ceil(length / 8)));
        double skew = (unit(cellX, cellZ, salt + 90) - .5) * .8;
        Point previous = from;
        double previousWidth = firstRadius;
        for (int i = 1; i <= parts; i++) {
            double t = i / (double) parts, u = 1 - t;
            double headT = t + skew * t * (1 - t) * (2 * t - 1);
            Point next = new Point(u*u*u*from.x + 3*u*u*t*a.x + 3*u*t*t*b.x + t*t*t*to.x,
                    from.y + (to.y - from.y) * headT,
                    u*u*u*from.z + 3*u*u*t*a.z + 3*u*t*t*b.z + t*t*t*to.z);
            double nextWidth = firstRadius + (lastRadius - firstRadius) * t;
            out.add(new Reach(previous, next, previousWidth, nextWidth, false, q));
            previous = next;
            previousWidth = nextWidth;
        }
    }

    private double selectOffset(Point a, Point b, double nx, double nz, int cx, int cz, int salt) {
        double best = 0, score = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 3; i++) {
            double offset = (unit(cx, cz, salt + i) - .5) * 28;
            double x = (a.x + b.x) * .5 + nx * offset, z = (a.z + b.z) * .5 + nz * offset;
            var context = geology.riverContext(x, (a.y + b.y) * .5, z);
            double resistance = context.rock() == GeologyModel.ROCK_CALCITE ? .25 : 1 - context.fracture();
            double candidate = resistance + Math.abs(offset) / 70;
            if (candidate < score) { score = candidate; best = offset; }
        }
        return best;
    }

    private Column legacyColumn(int x, int z) {
        var c = legacy.column(x, z); return c == null ? null : new Column(c.bed(), c.water(), c.roof(), c.liningBottom(), c.flowX(), c.flowZ(), c.wet(), c.lake());
    }
    private List<Reach> legacyReaches(int x, int z) {
        List<Reach> result = new ArrayList<>();
        for (var r : legacy.reaches(x, z)) result.add(new Reach(new Point(r.from().x(), r.from().y(), r.from().z()), new Point(r.to().x(), r.to().y(), r.to().z()), r.widthFrom(), r.widthTo(), r.lake(), r.discharge()));
        return List.copyOf(result);
    }
    private static Point point(double cx, double cz, double angle, double along, double lateral, double y) { return new Point(cx + along * Math.cos(angle) - lateral * Math.sin(angle), y, cz + along * Math.sin(angle) + lateral * Math.cos(angle)); }
    private double unit(long x, long z, long salt) { long h=seed+salt*0x9E3779B97F4A7C15L+x*0xC2B2AE3D27D4EB4FL+z*0x165667B19E3779F9L;h^=h>>>30;h*=0xBF58476D1CE4E5B9L;h^=h>>>27;h*=0x94D049BB133111EBL;return ((h^(h>>>31))>>>11)*0x1.0p-53; }
    private static int floor(double value) { return (int) Math.floor(value); }
    private static double clamp(double value, double low, double high) { return Math.max(low, Math.min(high, value)); }
    private record Candidate(double head, double depth, double height, double flowX, double flowZ, boolean wet, boolean lake) { }
}
