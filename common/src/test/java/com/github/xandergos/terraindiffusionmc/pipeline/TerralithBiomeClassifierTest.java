package com.github.xandergos.terraindiffusionmc.pipeline;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Standalone selector regression checks; run its main with FastNoiseLite and the resources on the classpath. */
public final class TerralithBiomeClassifierTest {
    public static void main(String[] args) throws Exception {
        checkJarInventory(args.length == 0 ? defaultJar() : new File(args[0]));
        System.out.println("inventory: 95 exact registry IDs");
        checkClassifierCompatibility();
        System.out.println("compatibility: baseline and overload agree");
        checkCoastalWater();
        System.out.println("coastal water: ocean fallback without synthetic river biomes");
        checkReachability();
        System.out.println("reachability: 83 surface + 11 cave IDs; warm_river reserved");
        checkSeamsAndCaves();
        checkDeepCaves();
        System.out.println("coherence/guards: deterministic seams, low neighbor change, cave depth guards");
        System.out.println("TerralithBiomeClassifierTest: PASS");
    }

    private static void checkJarInventory(File jar) throws Exception {
        Set<String> expected = new HashSet<String>();
        try (ZipFile zip = new ZipFile(jar)) {
            for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                String n = e.nextElement().getName();
                if (n.matches("data/terralith/worldgen/biome(/cave)?/[^/]+\\.json")) expected.add("terralith:" + n.substring("data/terralith/worldgen/biome/".length(), n.length() - 5));
            }
        }
        require(expected.size() == 95, "source jar must contain 95 Terralith biomes");
        require(new HashSet<String>(TerralithBiomeClassifier.biomeNames()).size() == 95, "selector IDs must be unique");
        require(expected.equals(new HashSet<String>(TerralithBiomeClassifier.biomeNames())), "selector inventory differs from source jar");
    }

    private static void checkDeepCaves() {
        Set<String> found = new HashSet<>();
        for (int x = -512; x <= 512; x += 37) for (int y : new int[]{-1190, -600, -300, -61}) {
            require(TerralithBiomeClassifier.selectCave(x, y, x * 3, 90, (short) 44, false) == 0,
                    "legacy worlds must retain deep biome cutoff");
            short id = TerralithBiomeClassifier.selectCave(x, y, x * 3, 90, (short) 44, true);
            String name = TerralithBiomeClassifier.biomeName(id);
            require(Set.of("terralith:cave/andesite_caves", "terralith:cave/diorite_caves",
                    "terralith:cave/granite_caves", "terralith:cave/tuff_caves").contains(name),
                    "deep profile must select a mineral cave biome: " + name);
            found.add(name);
        }
        require(found.size() == 4, "deep mineral biomes must all be reachable");
        for (int scale = 1; scale <= 6; scale++) for (int x : new int[]{-129, -1, 0, 97}) {
            short reference = TerralithBiomeClassifier.selectCave(x, -120, x * 3, 140, (short) 44, true, 1);
            short enlarged = TerralithBiomeClassifier.selectCave(x * scale, 63 + (-120 - 63) * scale,
                    x * 3 * scale, 63 + (140 - 63) * scale, (short) 44, true, scale);
            require(reference == enlarged, "deep cave biome regions must follow TD scale about sea level");
            require(TerralithBiomeClassifier.selectCave(x, -120, x, 140, (short) 44, false, scale) == 0,
                    "legacy cave cutoff must not change with new scale argument");
        }
        for (int y = -60; y < 100; y += 7)
            require(TerralithBiomeClassifier.selectCave(-1, y, 192, 100, (short) 44, true)
                    == TerralithBiomeClassifier.selectCave(-1, y, 192, 100, (short) 44),
                    "upper cave selection must be unchanged");
        System.out.println("deep caves: legacy cutoff preserved, four mineral biomes reachable");
    }

    private static void checkClassifierCompatibility() {
        int h = 3, w = 3; float[] elev = { 300, 300, 300, 300, 1500, 300, 300, 300, 300 };
        float[] padded = new float[(h + 2) * (w + 2)]; java.util.Arrays.fill(padded, 300f);
        float[] climate = new float[4 * h * w]; for (int i = 0; i < h * w; i++) { climate[i] = 18; climate[2 * h * w + i] = 700; }
        short[] baseline = BiomeClassifier.classify(elev, climate, -3, 7, padded, h, w, 16);
        short[] alternative = new short[h * w];
        short[] overloaded = BiomeClassifier.classify(elev, climate, -3, 7, padded, h, w, 16, alternative);
        require(java.util.Arrays.equals(baseline, overloaded), "overload changed vanilla classifier output");
    }

    private static void checkReachability() {
        Set<Short> found = new HashSet<Short>();
        float[][] climates = {{-10,100},{2,100},{2,700},{10,500},{18,700},{20,1300},{26,250},{28,700},{28,1200}};
        float[] elevations = {-100, -20, 50, 150, 600, 1000, 1700}; float[] slopes = {0.1f, 0.3f, 0.6f};
        for (int x = -250000; x <= 250000; x += 3509) for (int z = -250000; z <= 250000; z += 3571)
            for (float[] c : climates) for (float e : elevations) for (float slope : slopes) {
                short base = e < 0 ? (short) 44 : (short) 1;
                short id = TerralithBiomeClassifier.selectSurface(x, z, base, e, slope, c[0], c[1], 0);
                if (id != 0) found.add(id);
            }
        for (short id = 1; id <= 84; id++) {
            if ("terralith:warm_river".equals(TerralithBiomeClassifier.biomeName(id)))
                require(!found.contains(id), "terrain classifier must not synthesize river biomes");
            else require(found.contains(id), "surface ID not reachable: " + id);
        }
        for (int x = -6000; x <= 6000; x += 29) for (int y = -60; y < 80; y += 13) {
            short id = TerralithBiomeClassifier.selectCave(x, y, x * 3, 100, (short) 44); if (id != 0) found.add(id);
        }
        for (short id = 85; id <= 95; id++) require(found.contains(id), "cave ID not reachable: " + id);
    }

    private static void checkCoastalWater() {
        short legacyRiver = (short) (TerralithBiomeClassifier.biomeNames().indexOf("terralith:warm_river") + 1);
        require(legacyRiver == 75, "retain the saved warm_river palette ID");
        require(TerralithBiomeClassifier.selectCave(0, 20, 0, 100, legacyRiver) == 0,
                "legacy coastal river IDs retain their underwater cave guard");
        for (int x : new int[]{-8193, -1, 0, 8192})
            for (float temp : new float[]{-5, 17.99f, 18, 26})
                for (float elevation : new float[]{-40.01f, -40, -39.99f, -20, -.01f, 0})
                    for (float slope : new float[]{0, .15f, .1501f}) {
                        short expected = elevation <= -40 && temp >= 18 ? (short) 19 : 0;
                        for (boolean coastal : new boolean[]{false, true}) {
                            short actual = TerralithBiomeClassifier.selectSurface(x, -x, (short) 41,
                                    elevation, slope, 700, .6f, temp, 800, 0, coastal);
                            require(actual == expected, "coastal water must use ocean selection or vanilla fallback");
                            short local = TerralithBiomeClassifier.selectSurface(x, -x, (short) 41,
                                    elevation, slope, temp, 800, 0, coastal);
                            require(local == expected, "local selector must preserve ocean fallback");
                        }
                        short legacy = TerralithBiomeClassifier.selectSurface(x, -x, (short) 41,
                                elevation, slope, temp, 800, 0);
                        require(legacy == expected, "legacy selector must preserve ocean fallback");
                        if (elevation < 0) {
                            short submerged = TerralithBiomeClassifier.selectSurface(x, -x, (short) 1,
                                    elevation, slope, temp, 800, 0, false);
                            require(submerged == expected, "submerged terrain must stay marine even with a land hint");
                        }
                    }
    }

    private static void checkSeamsAndCaves() {
        int h = 2, w = 4; float[] elev = new float[h * w], padded = new float[(h + 2) * (w + 2)], climate = new float[4 * h * w];
        java.util.Arrays.fill(elev, 700); java.util.Arrays.fill(padded, 700); for (int i = 0; i < h * w; i++) { climate[i] = 16; climate[2 * h * w + i] = 700; }
        short[] fullAlt = new short[h * w]; BiomeClassifier.classify(elev, climate, -10, -20, padded, h, w, 16, fullAlt);
        for (int tile = 0; tile < 2; tile++) {
            float[] te = {700,700,700,700}, tp = new float[16], tc = new float[16]; java.util.Arrays.fill(tp, 700);
            for (int r = 0; r < 2; r++) for (int c = 0; c < 2; c++) { int dst=r*2+c, src=r*4+tile*2+c; tc[dst]=climate[src]; tc[8+dst]=climate[16+src]; tc[4+dst]=climate[8+src]; tc[12+dst]=climate[24+src]; }
            short[] part = new short[4]; BiomeClassifier.classify(te, tc, -10, -20 + tile * 2, tp, 2, 2, 16, part);
            for (int r = 0; r < 2; r++) for (int c = 0; c < 2; c++) require(part[r*2+c] == fullAlt[r*4+tile*2+c], "tile seam mismatch");
        }
        short a = TerralithBiomeClassifier.selectSurface(-17, 91, (short) 1, 700, .2f, 16, 700, 0);
        short b = TerralithBiomeClassifier.selectSurface(-17, 91, (short) 1, 700, .2f, 16, 700, 0);
        require(a == b, "same global coordinate must select the same ID");
        int changes = 0; short previous = TerralithBiomeClassifier.selectSurface(-4096, 91, (short) 1, 700, .2f, 16, 700, 0);
        for (int x = -4095; x <= 4096; x++) { short id = TerralithBiomeClassifier.selectSurface(x, 91, (short) 1, 700, .2f, 16, 700, 0); if (id != previous) changes++; previous = id; }
        System.out.println("neighbor changes: " + changes + "/8192 physical-meter samples");
        require(changes < 100, "regional field changes too rapidly: " + changes);
        require(TerralithBiomeClassifier.selectCave(0, 90, 0, 100, (short) 1) == 0, "shallow cave must stay surface biome");
        require(TerralithBiomeClassifier.selectCave(0, 20, 0, 100, (short) 19) == 0, "Terralith ocean bed must not get cave override");
        require(TerralithBiomeClassifier.selectCave(0, 20, 0, 100, (short) 44) != 0, "land ID 44 must not be treated as a vanilla ocean ID");
        require(TerralithBiomeClassifier.selectCave(0, -61, 0, 100, (short) 44) == 0, "bedrock guard must apply below -60");
        for (int x = -10000; x < 10000; x += 137) {
            short ocean = TerralithBiomeClassifier.selectSurface(x, 40, (short) 44, -100, .1f, -5, 600, 0);
            require(ocean == 0, "cold deep ocean must not select a land or warm-water biome");
            short warm = TerralithBiomeClassifier.selectSurface(x, 40, (short) 41, -100, .1f, 26, 600, 0);
            require("terralith:deep_warm_ocean".equals(TerralithBiomeClassifier.biomeName(warm)), "warm deep ocean must stay aquatic");
        }
        Set<Short> vertical = new HashSet<Short>();
        for (int y = -60; y <= 70; y += 7) vertical.add(TerralithBiomeClassifier.selectCave(0, y, 0, 100, (short) 44));
        require(vertical.size() > 1, "cave selection needs vertical variation");
    }
    private static File defaultJar() { return new File(System.getenv("APPDATA"), "PrismLauncher/instances/1.21.1 neoforge rpg/minecraft/mods/Terralith_1.21.1_v2.6.2_Neoforge.jar"); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
