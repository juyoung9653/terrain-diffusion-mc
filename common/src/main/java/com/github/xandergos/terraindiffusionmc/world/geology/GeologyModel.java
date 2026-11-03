package com.github.xandergos.terraindiffusionmc.world.geology;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Seeded geology in TD coordinates: x/scale, (y-63)/scale, z/scale. */
public final class GeologyModel {
    public static final int ROCK_STONE = 1, ROCK_SANDSTONE = 2, ROCK_CALCITE = 3,
            ROCK_TUFF = 4, ROCK_GRANITE = 5, ROCK_DIORITE = 6, ROCK_DEEPSLATE = 7;
    public static final int ORE_COAL = 16, ORE_COPPER = 17, ORE_IRON = 18, ORE_GOLD = 19,
            ORE_REDSTONE = 20, ORE_LAPIS = 21, ORE_DIAMOND = 22;
    public static final int MIN_ROOF = 24, SEA_LEVEL = 63;
    private static final int CELL = 112, LEVEL = 92, ORE_CELL = 84;
    private final long seed;
    private final int minY, scale;
    // Per-generation-pass cache. No shared mutable state or neighbor chunk reads.
    private final Map<Long, PassageTile> tiles = new LinkedHashMap<>(8, .75f, true);

    public GeologyModel(long seed, int minY) { this(seed, minY, 1); }

    public GeologyModel(long seed, int minY, int scale) {
        if (minY < -2032 || minY > -64 || (minY & 15) != 0 || scale < 1 || scale > 6)
            throw new IllegalArgumentException("Invalid geology floor/scale: " + minY + "/" + scale);
        this.seed = seed;
        this.minY = minY;
        this.scale = scale;
    }

    public Column column(int x, int z, int surfaceY) { return new Column(x, z, surfaceY); }
    /** Cheap, immutable geological context for planners; inputs are normalized TD coordinates. */
    public RiverContext riverContext(double x, double y, double z) {
        Lithology here = lithology(x, z);
        double dx = lithology(x + 4, z).phase - lithology(x - 4, z).phase;
        double dz = lithology(x, z + 4).phase - lithology(x, z - 4).phase;
        return new RiverContext(here.rock(y), here.fracture(y), Math.atan2(dx, -dz));
    }
    public record RiverContext(int rock, double fracture, double faultBearing) { }
    public static boolean isOre(int material) { return material >= ORE_COAL && material <= ORE_DIAMOND; }
    public static int ore(int material) { return isOre(material) ? material : 0; }

    public final class Column {
        private final int x, z, surfaceY, waterY;
        private final Lithology lithology;
        private final Interval[] caves;
        private final Vein[] veins;

        private Column(int x, int z, int surfaceY) {
            this.x = x; this.z = z; this.surfaceY = surfaceY;
            double gx = x / (double) scale, gz = z / (double) scale;
            lithology = lithology(gx, gz);
            // A smooth regional hydraulic-head approximation, independent of bedrock.
            // No local hill-height input: adjacent columns agree even under steep cliffs.
            waterY = (int) Math.floor(SEA_LEVEL + scale * (-285 + smooth(gx, gz, 811, 700) * 24));
            caves = intervals(x, z, surfaceY);
            veins = veins(gx, gz);
        }

        public int cavity(int y) {
            if (y <= minY + 8 || y >= surfaceY - MIN_ROOF) return 0;
            for (Interval interval : caves) {
                if (y < interval.low) return 0;
                if (y <= interval.high) return y <= waterY ? 2 : 1;
            }
            return 0;
        }

        public int rock(int y) { return lithology.rock((y - SEA_LEVEL) / (double) scale); }

        public int material(int y) {
            int host = rock(y);
            double gy = (y - SEA_LEVEL) / (double) scale;
            for (Vein vein : veins) {
                if (gy >= vein.low && gy <= vein.high && acceptsHost(vein.ore, host)
                        // Block-sized mineral grains, even when a whole vein is enlarged.
                        && unit(x, z, y, vein.ore + 900) < vein.density) return vein.ore;
            }
            return host;
        }
    }

    private Lithology lithology(double x, double z) {
        double phase = x * .009 - z * .006 + smooth(x, z, 76, 310) * 1.3;
        double fold = smooth(x, z, 71, 340) * 42 + smooth(x, z, 72, 100) * 13;
        double facies = smooth(x, z, 73, 220);
        // Buried lenticular intrusions interrupt the stratigraphy in three dimensions.
        double intrusion = Math.max(0, (smooth(x, z, 74, 130) - .16) * 170);
        double intrusionY = -155 + smooth(x, z, 75, 290) * 40;
        return new Lithology(fold, facies, phase, intrusion, intrusionY);
    }

    private record Lithology(double fold, double facies, double phase,
                             double intrusion, double intrusionY) {
        double fracture(double y) {
            double p = phase + y * .006;
            double within = p - Math.floor(p / Math.PI) * Math.PI;
            return Math.max(0, 1 - Math.min(within, Math.PI - within) * 5);
        }

        int rock(double y) {
            // Dipping faults taper downward instead of cutting vertical stripes through every unit.
            int side = (int) Math.floor((phase + y * .006) / Math.PI);
            double throwDepth = Math.max(0, Math.min(1, (y + 340) / 180));
            double bed = y + fold + ((side & 1) == 0 ? 14 : -14) * throwDepth;
            if (bed < -275) return ROCK_DEEPSLATE;
            if (Math.abs(y - intrusionY) < intrusion)
                return facies > 0 ? ROCK_DIORITE : ROCK_GRANITE;
            if (fracture(y) > .94 && bed < -55) return ROCK_GRANITE;
            if (bed < -195) return facies > .12 ? ROCK_TUFF : ROCK_GRANITE;
            if (bed < -140) return ROCK_STONE;
            // Beds dip and fold together; thin insoluble seams remain within carbonate.
            int bedIndex = Math.floorMod((int) Math.floor(bed / 13), 7);
            if (bed < -28) return bedIndex == 0 ? ROCK_STONE : ROCK_CALCITE;
            if (bed < 28) return bedIndex < 2 ? ROCK_STONE : ROCK_SANDSTONE;
            return facies + Math.sin(bed * .04) * .11 > .05 ? ROCK_CALCITE : ROCK_SANDSTONE;
        }
    }

    public static boolean acceptsHost(int ore, int host) {
        return switch (ore) {
            case ORE_COAL -> host == ROCK_STONE || host == ROCK_SANDSTONE || host == ROCK_CALCITE;
            case ORE_COPPER -> host == ROCK_STONE || host == ROCK_CALCITE || host == ROCK_DIORITE;
            case ORE_IRON -> host == ROCK_STONE || host == ROCK_GRANITE || host == ROCK_TUFF || host == ROCK_DIORITE;
            case ORE_GOLD -> host == ROCK_GRANITE || host == ROCK_DIORITE;
            case ORE_REDSTONE -> host == ROCK_GRANITE || host == ROCK_DEEPSLATE;
            case ORE_LAPIS -> host == ROCK_CALCITE || host == ROCK_DIORITE || host == ROCK_DEEPSLATE;
            case ORE_DIAMOND -> host == ROCK_DEEPSLATE;
            default -> false;
        };
    }

    private Interval[] intervals(int x, int z, int surfaceY) {
        List<Interval> raw = new ArrayList<>();
        double gx = x / (double) scale, gz = z / (double) scale;
        double roughness = (smooth(gx, gz, 921, 7) * .8 + smooth(gx, gz, 922, 3) * .35) * scale;
        // A corrugated cap avoids a flat horizontal roof where a conduit nears terrain.
        int roof = surfaceY - MIN_ROOF - (int) ((smooth(gx, gz, 923, 19) + 1) * 4 * scale);
        for (Segment segment : tile(gx, gz, surfaceY).segments) {
            if (x < segment.minX || x > segment.maxX || z < segment.minZ || z > segment.maxZ) continue;
            Interval interval = segment.intersectColumn(x, z, roughness);
            if (interval == null) continue;
            int low = Math.max(minY + 9, interval.low), high = Math.min(roof - 1, interval.high);
            if (low <= high) raw.add(new Interval(low, high));
        }
        raw.sort(Comparator.comparingInt(Interval::low));
        List<Interval> merged = new ArrayList<>();
        for (Interval next : raw) {
            if (!merged.isEmpty() && next.low <= merged.getLast().high + 1) {
                Interval previous = merged.removeLast();
                merged.add(new Interval(previous.low, Math.max(previous.high, next.high)));
            } else merged.add(next);
        }
        return merged.toArray(Interval[]::new);
    }

    private synchronized PassageTile tile(double x, double z, int surfaceY) {
        int cx = (int) Math.floor(x / CELL), cz = (int) Math.floor(z / CELL);
        int low = (int) Math.floor((minY - SEA_LEVEL) / (double) scale / LEVEL) - 1;
        int high = (int) Math.floor((surfaceY - SEA_LEVEL) / (double) scale / LEVEL) + 1;
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        PassageTile cached = tiles.get(key);
        if (cached != null && cached.high >= high) return cached;
        List<Segment> segments = new ArrayList<>();
        for (int gx = cx - 2; gx <= cx + 2; gx++) for (int gz = cz - 2; gz <= cz + 2; gz++) {
            for (int level = low; level <= high; level++) {
                if (!hasNode(gx, gz, level)) continue;
                Node here = node(gx, gz, level);
                Lithology geology = lithology(here.x, here.z);
                boolean carbonate = geology.rock(here.y) == ROCK_CALCITE;
                double probability = carbonate ? .96 : .65 + geology.fracture(here.y) * .3;
                // Scatter/route in every direction. A single preferred outlet makes
                // merging branches rather than the old repeated x/z quadrilateral mesh.
                double angle = smooth(here.x, here.z, 841, 380) * Math.PI * 1.5
                        + unit(0, 0, level, 842) * Math.PI * 2;
                double vx = Math.cos(angle), vz = Math.sin(angle);
                Node first = null, second = null;
                double firstScore = Double.POSITIVE_INFINITY, secondScore = Double.POSITIVE_INFINITY;
                for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
                    if ((ox == 0 && oz == 0) || !hasNode(gx + ox, gz + oz, level)) continue;
                    Node candidate = node(gx + ox, gz + oz, level);
                    double dx = candidate.x - here.x, dz = candidate.z - here.z;
                    double distance = Math.hypot(dx, dz), alignment = (dx*vx + dz*vz) / distance;
                    if (distance > CELL * 1.9 || alignment < -.15) continue;
                    double score = distance + CELL * .85 * (1 - alignment);
                    if (score < firstScore) {
                        second = first; secondScore = firstScore; first = candidate; firstScore = score;
                    } else if (score < secondScore) { second = candidate; secondScore = score; }
                }
                if (first != null && unit(gx, gz, level, 501) < probability)
                    passage(segments, here, first, gx, gz, level, 502, false);
                if (second != null && unit(gx, gz, level, 503) < (carbonate ? .16 : .06))
                    passage(segments, here, second, gx, gz, level, 504, false);
                // Inclined fractures connect different elevations; shafts no longer form a grid.
                if (hasNode(gx, gz, level + 1)
                        && unit(gx, gz, level, 505) < .12 + geology.fracture(here.y) * .55)
                    passage(segments, here, node(gx, gz, level + 1), gx, gz, level, 506, true);
                if (carbonate && unit(gx, gz, level, 507) < .28) {
                    double radius = 10 + unit(gx, gz, level, 508) * 9;
                    segments.add(segment(here, here, radius, .45 + unit(gx, gz, level, 509) * .35));
                }
            }
        }
        // The wider graph halo guarantees crossing curves are found on both sides
        // of a tile edge. Keep only segments whose padded bounds touch this tile.
        double tileMinX = cx * (double) CELL * scale, tileMinZ = cz * (double) CELL * scale;
        double tileMaxX = tileMinX + CELL * scale, tileMaxZ = tileMinZ + CELL * scale;
        segments.removeIf(s -> s.maxX < tileMinX || s.minX > tileMaxX || s.maxZ < tileMinZ || s.minZ > tileMaxZ);
        PassageTile result = new PassageTile(high, List.copyOf(segments));
        tiles.put(key, result);
        if (tiles.size() > 8) tiles.remove(tiles.keySet().iterator().next());
        return result;
    }

    private boolean hasNode(int gx, int gz, int level) { return unit(gx, gz, level, 480) > .16; }

    private Node node(int gx, int gz, int level) {
        double x = gx * (double) CELL + 6 + unit(gx, gz, level, 481) * (CELL - 12);
        double z = gz * (double) CELL + 6 + unit(gx, gz, level, 482) * (CELL - 12);
        double y = level * LEVEL + (unit(gx, gz, level, 483) - .5) * 60
                - lithology(x, z).fold * .45;
        return new Node(x, y, z);
    }

    private void passage(List<Segment> out, Node a, Node b, int gx, int gz, int level, int salt, boolean shaft) {
        double dx = b.x - a.x, dz = b.z - a.z, length = Math.max(1, Math.hypot(dx, dz));
        double bend1 = (unit(gx, gz, level, salt + 20) - .5) * 64;
        double bend2 = (unit(gx, gz, level, salt + 40) - .5) * 64;
        Node c1 = new Node(a.x + dx / 3 - dz / length * bend1,
                a.y + (b.y - a.y) / 3 + (unit(gx, gz, level, salt + 60) - .5) * 24,
                a.z + dz / 3 + dx / length * bend1);
        Node c2 = new Node(a.x + dx * 2 / 3 - dz / length * bend2,
                a.y + (b.y - a.y) * 2 / 3 + (unit(gx, gz, level, salt + 80) - .5) * 24,
                a.z + dz * 2 / 3 + dx / length * bend2);
        Node previous = a;
        double phase = unit(gx, gz, level, salt + 100) * Math.PI * 2;
        for (int step = 1; step <= 8; step++) {
            double t = step / 8.0, u = 1 - t;
            Node next = new Node(u*u*u*a.x + 3*u*u*t*c1.x + 3*u*t*t*c2.x + t*t*t*b.x,
                    u*u*u*a.y + 3*u*u*t*c1.y + 3*u*t*t*c2.y + t*t*t*b.y,
                    u*u*u*a.z + 3*u*u*t*c1.z + 3*u*t*t*c2.z + t*t*t*b.z);
            Lithology geology = lithology((previous.x + next.x) / 2, (previous.z + next.z) / 2);
            int rock = geology.rock((previous.y + next.y) / 2);
            double radius = (rock == ROCK_CALCITE ? 5.6 : rock == ROCK_TUFF ? 4.0 : 2.7)
                    + geology.fracture((previous.y + next.y) / 2) * 2.6 + (1 + Math.sin(t * 7 + phase)) * 1.1;
            out.add(segment(previous, next, shaft ? radius * .72 : radius, shaft ? 1 : .63));
            previous = next;
        }
    }

    private Segment segment(Node a, Node b, double radius, double aspect) {
        return new Segment(world(a), world(b), radius * scale, aspect, 1.2 * scale);
    }

    private Node world(Node n) { return new Node(n.x * scale, SEA_LEVEL + n.y * scale, n.z * scale); }

    private Vein[] veins(double x, double z) {
        List<Vein> result = new ArrayList<>();
        int cx = (int) Math.floor(x / ORE_CELL), cz = (int) Math.floor(z / ORE_CELL);
        for (int gx = cx - 1; gx <= cx + 1; gx++) for (int gz = cz - 1; gz <= cz + 1; gz++) {
            int ore = ORE_COAL + (int) (unit(gx, gz, 0, 661) * 7);
            double angle = unit(gx, gz, 0, 662) * Math.PI * 2;
            double dx = x - (gx * ORE_CELL + 12 + unit(gx, gz, 0, 663) * 60);
            double dz = z - (gz * ORE_CELL + 12 + unit(gx, gz, 0, 664) * 60);
            double along = dx * Math.cos(angle) + dz * Math.sin(angle);
            double across = -dx * Math.sin(angle) + dz * Math.cos(angle);
            double extent = 14 + unit(gx, gz, 0, 665) * 20;
            if (Math.abs(along) > extent) continue;
            // Winding mineralized sheets split into two strands toward their ends.
            double split = Math.max(0, along / extent) * 4;
            double center = Math.sin(along / 7 + angle) * 3;
            double distance = Math.min(Math.abs(across - center - split), Math.abs(across - center + split));
            double width = (3 + unit(gx, gz, 0, 666) * 4) * Math.sqrt(1 - along * along / (extent * extent));
            if (distance >= width) continue;
            double v = unit(gx, gz, 0, 667);
            double y = switch (ore) {
                case ORE_COAL -> -15 + v * 60;
                case ORE_COPPER -> -85 + v * 65;
                case ORE_IRON -> -160 - v * 55;
                case ORE_GOLD -> -190 - v * 55;
                case ORE_REDSTONE -> -250 - v * 55;
                case ORE_LAPIS -> -285 - v * 25;
                default -> -320 + v * 15;
            };
            y += along * .16 + smooth(x, z, 668, 27) * 7;
            double thickness = (1.3 + v * 2.5) * (1 - distance / width);
            result.add(new Vein(y - thickness, y + thickness, ore, .35 + v * .4));
        }
        return result.toArray(Vein[]::new);
    }

    private double unit(long x, long z, long level, long salt) {
        long h = seed + salt * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL
                + z * 0x165667B19E3779F9L + level * 0xD6E8FEB86659FD93L;
        h ^= h >>> 30; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 27; h *= 0x94D049BB133111EBL;
        return ((h ^ (h >>> 31)) >>> 11) * 0x1.0p-53;
    }

    private double smooth(double x, double z, long salt, double wavelength) {
        double px = x / wavelength, pz = z / wavelength;
        int ix = (int) Math.floor(px), iz = (int) Math.floor(pz);
        double fx = px - ix, fz = pz - iz;
        fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
        double a = unit(ix, iz, 0, salt), b = unit(ix + 1, iz, 0, salt);
        double c = unit(ix, iz + 1, 0, salt), d = unit(ix + 1, iz + 1, 0, salt);
        return ((a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz) * 2 - 1;
    }

    private record PassageTile(int high, List<Segment> segments) {}
    private record Node(double x, double y, double z) {}
    private record Interval(int low, int high) {}
    private record Vein(double low, double high, int ore, double density) {}

    /** Elliptic capsule, exact column intersection in a vertically stretched metric. */
    private static final class Segment {
        final Node a, b;
        final double radius, aspect, minX, maxX, minZ, maxZ;
        Segment(Node a, Node b, double radius, double aspect, double padding) {
            this.a = a; this.b = b; this.radius = radius; this.aspect = aspect;
            minX = Math.min(a.x, b.x) - radius - padding; maxX = Math.max(a.x, b.x) + radius + padding;
            minZ = Math.min(a.z, b.z) - radius - padding; maxZ = Math.max(a.z, b.z) + radius + padding;
        }

        Interval intersectColumn(double x, double z, double roughness) {
            double r = Math.max(radius * .6, radius + roughness);
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (Node endpoint : new Node[]{a, b}) {
                double dx = x - endpoint.x, dz = z - endpoint.z;
                double square = r*r - dx*dx - dz*dz;
                if (square >= 0) {
                    double extent = Math.sqrt(square);
                    low = Math.min(low, endpoint.y / aspect - extent);
                    high = Math.max(high, endpoint.y / aspect + extent);
                }
            }
            double dx = b.x - a.x, dy = (b.y - a.y) / aspect, dz = b.z - a.z;
            double horizontal = dx*dx + dz*dz, length = horizontal + dy*dy;
            double qx = x - a.x, qz = z - a.z, h = qx*dx + qz*dz;
            if (horizontal > 1e-9) {
                double aa = horizontal / length, bb = -2*h*dy / length;
                double cc = qx*qx + qz*qz - h*h / length - r*r;
                double discriminant = bb*bb - 4*aa*cc;
                if (discriminant >= 0) {
                    double root = Math.sqrt(discriminant);
                    double lo = (-bb - root) / (2*aa), hi = (-bb + root) / (2*aa);
                    if (Math.abs(dy) > 1e-9) {
                        double t0 = -h/dy, t1 = (length-h)/dy;
                        lo = Math.max(lo, Math.min(t0,t1)); hi = Math.min(hi, Math.max(t0,t1));
                    } else if (h < 0 || h > length) { lo = 1; hi = 0; }
                    if (lo <= hi) { low = Math.min(low,a.y/aspect+lo); high = Math.max(high,a.y/aspect+hi); }
                }
            } else if (qx*qx + qz*qz <= r*r) {
                low = Math.min(low,Math.min(a.y,b.y)/aspect); high = Math.max(high,Math.max(a.y,b.y)/aspect);
            }
            if (low > high) return null;
            int bottom = (int) Math.ceil(low*aspect), top = (int) Math.floor(high*aspect);
            return bottom <= top ? new Interval(bottom,top) : null;
        }
    }
}
