package com.github.xandergos.terraindiffusionmc.pipeline;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Data-driven mapping for Terralith's 2.6.2 biome registry. */
public final class TerralithBiomeClassifier {
    private static final List<Entry> ENTRIES = load();
    private static final List<String> NAMES = names();
    private static final Map<String, List<Entry>> GROUPS = groups();
    private static final List<Entry> CAVE_ENTRIES = GROUPS.get("cave");

    private TerralithBiomeClassifier() { }

    public static List<String> biomeNames() { return NAMES; }
    public static String biomeName(short id) {
        return id > 0 && id <= NAMES.size() ? NAMES.get(id - 1) : null;
    }

    /** Selects a surface alternative. horizontal coordinates and elevation are physical terrain meters, not block coordinates. */
    public static short selectSurface(int blockX, int blockZ, short baseBiome, float elevationM,
                                      float slope, float temp, float precip, float pCv) {
        return selectSurface(blockX, blockZ, baseBiome, elevationM, slope, temp, precip, pCv, true);
    }

    /**
     * Selects from a coherent macro-terrain sample.  Callers that know the
     * smooth ocean mask must pass it explicitly: a low flat inland basin is
     * not a coast merely because it is below the old 120 m threshold.
     */
    public static short selectSurface(int blockX, int blockZ, short baseBiome, float elevationM,
                                      float slope, float temp, float precip, float pCv, boolean coastal) {
        return selectSurface(blockX, blockZ, baseBiome, elevationM, slope, elevationM, slope,
                temp, precip, pCv, coastal);
    }

    /**
     * Uses local smooth terrain for water and coast decisions, while broad
     * regional terrain controls terrestrial group and candidate eligibility.
     */
    public static short selectSurface(int blockX, int blockZ, short baseBiome,
                                      float localElevation, float localSlope,
                                      float macroElevation, float macroSlope,
                                      float temp, float precip, float pCv, boolean coastal) {
        if (Float.isNaN(temp)) return 0;
        if (isVanillaOcean(baseBiome) || localElevation < 0) {
            String water = localElevation >= -40 && temp >= 18 && localSlope <= .15f ? "estuary" : "ocean";
            return pick(water, temp, localElevation, localSlope, blockX, blockZ);
        }
        if (coastal && localElevation <= 120 && localSlope < .45f)
            return pick("coast", temp, localElevation, localSlope, blockX, blockZ);
        float landElevation = Math.max(0f, macroElevation);
        String group = group(blockX, blockZ, baseBiome, landElevation, macroSlope, temp, precip, pCv, false);
        return pick(group, temp, landElevation, macroSlope, blockX, blockZ);
    }

    /** Returns a cave alternative only well below a non-ocean surface. */
    public static short selectCave(int blockX, int blockY, int blockZ, int surfaceY, short surfaceBiome) {
        int depth = surfaceY - blockY;
        if (blockY < -60 || depth < 16 || isOcean(surfaceBiome) || (surfaceBiome == 0 && surfaceY <= 63)) return 0;
        return pickCave(blockX, blockY, blockZ);
    }

    private static String group(int blockX, int blockZ, short base, float elevation, float slope, float temp, float precip, float pCv, boolean coastal) {
        if (isVanillaOcean(base) || elevation < 0) {
            // Shallow warm water is an estuary proxy; no river channels are carved here.
            return elevation >= -40 && temp >= 18 && slope <= .15f ? "estuary" : "ocean";
        }
        if (coastal && elevation <= 120 && slope < .45f) return "coast";
        if (slope >= .48f) return temp < 3 ? "cold_cliff" : "cliff";
        // Rare fantasy volcanic regions are a coordinate-coherent overlay, never per-block random.
        if (elevation > 400 && cellNoise(blockX, blockZ, 32768, 81421) > .88f) return "volcanic";
        if (elevation >= 1200) {
            if (temp < 5) return "cold_high";
            return temp >= 16 ? "warm_high" : "temperate_high";
        }
        if (temp < 4) return precip > 500 ? "cold_forest" : "cold";
        if (temp >= 24) {
            if (precip >= 800) return elevation > 450 ? "jungle_high" : "jungle";
            if (precip < 350) return elevation > 350 ? "desert_high" : "desert";
            return elevation > 300 ? "savanna_high" : "savanna";
        }
        if (precip > 1050 && elevation < 180 && slope < .15f) return "wet";
        return "temperate";
    }

    static boolean isOcean(short base) {
        int index = base - 1;
        return index >= 0 && index < ENTRIES.size() && ("ocean".equals(ENTRIES.get(index).group) || "estuary".equals(ENTRIES.get(index).group));
    }
    private static boolean isVanillaOcean(short base) { return base == 41 || base == 44 || base == 46 || base == 48; }

    private static short pick(String group, float temp, float elevation, float slope, int blockX, int blockZ) {
        List<Entry> candidates = GROUPS.get(group);
        if (candidates == null) return 0;
        int total = 0;
        for (Entry entry : candidates) if (entry.matches(temp, elevation, slope)) total += entry.weight;
        if (total == 0) return 0;
        int choice = Math.floorMod(surfaceRegionHash(blockX, blockZ), total);
        for (Entry entry : candidates) {
            if (entry.matches(temp, elevation, slope)) {
                if ((choice -= entry.weight) < 0) return entry.id;
            }
        }
        return 0;
    }

    private static short pickCave(int blockX, int blockY, int blockZ) {
        int total = 0;
        for (Entry entry : CAVE_ENTRIES) if (blockY >= entry.e0 && blockY <= entry.e1) total += entry.weight;
        int choice = Math.floorMod(caveRegionHash(blockX, blockY, blockZ), total);
        for (Entry entry : CAVE_ENTRIES) if (blockY >= entry.e0 && blockY <= entry.e1 && (choice -= entry.weight) < 0) return entry.id;
        return 0;
    }

    private static int cellHash(int x, int y, int z, int size, int seed) {
        int cx = Math.floorDiv(x, size), cy = Math.floorDiv(y, size), cz = Math.floorDiv(z, size);
        int h = seed; h = h * 0x9e3779b9 + cx; h = Integer.rotateLeft(h, 13);
        h = h * 0x85ebca6b + cy; h = Integer.rotateLeft(h, 11);
        h = h * 0xc2b2ae35 + cz;
        h ^= h >>> 16; h *= 0x85ebca6b; h ^= h >>> 13; h *= 0xc2b2ae35;
        return h ^ (h >>> 16);
    }
    private static float cellNoise(int x, int z, int size, int seed) {
        return (cellHash(x, 0, z, size, seed) & 0x7fffffff) / (float) Integer.MAX_VALUE;
    }
    /** A jittered Voronoi field: regional choices stay constant within broad, naturally warped cells. */
    private static int surfaceRegionHash(int x, int z) {
        final int size = 8192, cx = Math.floorDiv(x, size), cz = Math.floorDiv(z, size);
        long best = Long.MAX_VALUE; int selected = 0;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int gx = cx + dx, gz = cz + dz, h = cellHash(gx * size, 0, gz * size, size, 39217);
            int sx = gx * size + size / 2 + Math.floorMod(h, size / 2) - size / 4;
            int sz = gz * size + size / 2 + Math.floorMod(Integer.rotateLeft(h, 15), size / 2) - size / 4;
            long ddx = (long) x - sx, ddz = (long) z - sz, d = ddx * ddx + ddz * ddz;
            if (d < best) { best = d; selected = h; }
        }
        return selected;
    }

    /** Jittered 3D regions avoid axis-aligned stacks of underground biome slabs. */
    private static int caveRegionHash(int x, int y, int z) {
        final int size = 96, cx = Math.floorDiv(x, size), cy = Math.floorDiv(y, size), cz = Math.floorDiv(z, size);
        long best = Long.MAX_VALUE; int selected = 0;
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int gx = cx + dx, gy = cy + dy, gz = cz + dz;
            int h = cellHash(gx * size, gy * size, gz * size, size, 71131);
            int sx = gx * size + size / 4 + Math.floorMod(h, size / 2);
            int sy = gy * size + size / 4 + Math.floorMod(Integer.rotateLeft(h, 10), size / 2);
            int sz = gz * size + size / 4 + Math.floorMod(Integer.rotateLeft(h, 20), size / 2);
            long xx = (long) x - sx, yy = (long) y - sy, zz = (long) z - sz;
            long distance = xx * xx + yy * yy + zz * zz;
            if (distance < best) { best = distance; selected = h; }
        }
        return selected;
    }

    private static List<Entry> load() {
        ArrayList<Entry> result = new ArrayList<Entry>();
        try {
            InputStream stream = TerralithBiomeClassifier.class.getClassLoader().getResourceAsStream("terralith-biomes.tsv");
            if (stream == null) throw new IllegalStateException("Missing terralith-biomes.tsv");
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] v = line.split("\t");
                if (v.length != 10) throw new IllegalStateException("Bad Terralith entry: " + line);
                result.add(new Entry((short) (result.size() + 1), v[0], "cave".equals(v[1]), v[2],
                    Float.parseFloat(v[3]), Float.parseFloat(v[4]), Float.parseFloat(v[5]), Float.parseFloat(v[6]),
                    Float.parseFloat(v[7]), Float.parseFloat(v[8]), Integer.parseInt(v[9])));
            }
            reader.close();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
        if (result.size() != 95) throw new ExceptionInInitializerError("Expected 95 Terralith biomes, got " + result.size());
        return Collections.unmodifiableList(result);
    }
    private static List<String> names() {
        ArrayList<String> result = new ArrayList<String>(); for (Entry entry : ENTRIES) result.add("terralith:" + entry.name);
        return Collections.unmodifiableList(result);
    }
    private static Map<String, List<Entry>> groups() {
        Map<String, List<Entry>> result = new LinkedHashMap<String, List<Entry>>();
        for (Entry entry : ENTRIES) {
            List<Entry> group = result.get(entry.group);
            if (group == null) { group = new ArrayList<Entry>(); result.put(entry.group, group); }
            group.add(entry);
        }
        for (Map.Entry<String, List<Entry>> group : result.entrySet()) group.setValue(Collections.unmodifiableList(group.getValue()));
        return Collections.unmodifiableMap(result);
    }
    private static final class Entry {
        final short id; final String name, group; final boolean cave; final float t0, t1, e0, e1, s0, s1; final int weight;
        Entry(short id, String name, boolean cave, String group, float t0, float t1, float e0, float e1, float s0, float s1, int weight) {
            this.id=id; this.name=name; this.cave=cave; this.group=group; this.t0=t0; this.t1=t1; this.e0=e0; this.e1=e1; this.s0=s0; this.s1=s1; this.weight=weight;
        }
        boolean matches(float temp, float elevation, float slope) { return temp >= t0 && temp <= t1 && elevation >= e0 && elevation <= e1 && slope >= s0 && slope <= s1; }
    }
}
