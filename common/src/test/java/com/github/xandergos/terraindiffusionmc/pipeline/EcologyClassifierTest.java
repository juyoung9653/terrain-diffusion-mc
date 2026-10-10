package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.HashSet;
import java.util.Set;

public final class EcologyClassifierTest {
    public static void main(String[] args) {
        Set<Integer> reached = new HashSet<>();
        int transitions = 0;
        for (float t = -10; t <= 35; t += 1) {
            EcologyClassifier.Sample prior = null;
            for (int p = 0; p <= 2500; p++) {
                EcologyClassifier.Selection s = EcologyClassifier.select(-513, 257, 300, .1f, false, t, p, 300);
                EcologyClassifier.Sample a = s.sample();
                reached.add(a.biomeId());
                require(s.terrainBiomeId() == 0, "ordinary land must use ecological biomes");
                require(a.treeCover() >= 0 && a.treeCover() <= 1, "cover bounds");
                require(a.temperature() == t, "do not apply altitude lapse twice");
                if (prior != null) {
                    require(Math.abs(a.treeCover() - prior.treeCover()) < .004f, "cover jumps with moisture");
                    if (a.biomeId() != prior.biomeId()) transitions++;
                }
                prior = a;
            }
        }
        reached.add(EcologyClassifier.select(0, 0, 900, .1f, false, 10, 1000, 900).sample().biomeId());
        reached.add(EcologyClassifier.select(0, 0, 2000, 1.1f, false, -5, 250, 2000).sample().biomeId());
        reached.add(EcologyClassifier.select(0, 0, 2000, 1.1f, false, -10, 250, 2000).sample().biomeId());
        require(reached.size() == EcologyClassifier.BIOMES.size(), "unreachable ecosystems: " + reached);
        require(transitions > 50, "exercise real label boundaries");
        var base = EcologyClassifier.select(0, 0, 300, .1f, false, 15, 800, 300).sample();
        var high = EcologyClassifier.select(0, 0, 800, .1f, false, 15, 800, 300).sample();
        require(base.temperature() - high.temperature() > 1 && base.temperature() - high.temperature() < 2, "relative elevation lapse");
        for (int x = -1024; x < 1024; x++) {
            var a = EcologyClassifier.select(x, 0, 300, .1f, false, 15, 800, 300).sample();
            require(a.biomeId() == base.biomeId(), "no coordinate-dithered biome labels");
        }
        require(EcologyClassifier.select(0, 0, 0, .1f, true, 15, 800, 300).terrainBiomeId() != 0, "coast retained");
        require(EcologyClassifier.select(0, 0, -100, .1f, false, 15, 800, 300).sample().biomeId() == 0, "ocean retained");
        checkCoastalWater();
        checkRegionalLandIdentity();
        checkColdEcology();
        checkDryWoodlandBand();
        checkSoilCoveredHills();
        checkSubdivisions();
        System.out.println("EcologyClassifierTest: PASS (" + EcologyClassifier.BIOMES.size() + " ecosystems, " + transitions + " continuous label transitions)");
    }

    private static void checkCoastalWater() {
        for (int x : new int[]{-513, 0, 512})
            for (float temperature : new float[]{18, 26, 32})
                for (float elevation : new float[]{-39.99f, -20, -.01f}) {
                    var water = EcologyClassifier.select(x, -x, elevation, .1f,
                            temperature, 1200, 0, 0, .8f, .1f);
                    require(water.terrainBiomeId() == 0 && water.sample().biomeId() == 0,
                            "warm shallow ecology must retain the vanilla ocean fallback, not a river or land biome");
                }
        var shore = EcologyClassifier.select(0, 0, 0, .1f, 26, 800, 0, 1, .8f, .1f);
        require(shore.terrainBiomeId() == ShoreClassifier.BEACH, "warm coastal land must retain its beach");
        var inland = EcologyClassifier.select(0, 0, 30, .1f, 26, 1200, 30, 100, 0, .1f);
        require(inland.terrainBiomeId() == 0 && inland.sample().biomeId() != 0,
                "warm land away from the water must retain its ecology");
    }

    private static void checkSubdivisions() {
        require(EcologyClassifier.BIOMES.get(10).equals("desert"), "legacy biome indices stay stable");
        require(EcologyShowcaseProfile.sample("temperate_grassland", 0, 0, .1f).moisture() == .50f,
                "existing grassland showcases retain their climate");
        for (String name : EcologyClassifier.BIOMES) {
            var sample = EcologyShowcaseProfile.sample(name, 0, 0, .1f);
            require(EcologyClassifier.BIOMES.get(sample.biomeId() - 1).equals(name), "showcase identity: " + name);
        }
        var low = EcologyClassifier.select(0, 0, 699, .1f, false, 10, 1000, 699).sample();
        var high = EcologyClassifier.select(0, 0, 701, .1f, false, 10, 1000, 701).sample();
        require(low.biomeId() == 1 && high.biomeId() == 14, "regional elevation subdivides forests");
        require(low.treeCover() == high.treeCover(), "new labels do not create a tree-density seam");
    }

    private static void checkSoilCoveredHills() {
        float flat = EcologyClassifier.select(0, 0, 300, 0, false, 15, 1200, 300).sample().treeCover();
        float previous = flat;
        for (int step = 0; step <= 200; step++) {
            float slope = step / 100f;
            float cover = EcologyClassifier.select(0, 0, 300, slope, false, 15, 1200, 300).sample().treeCover();
            require(cover <= previous && previous - cover < .012f, "hillside canopy must taper smoothly");
            if (slope <= .85f) require(cover > .70f * flat, "ordinary soil-covered hills must retain forest potential");
            if (slope >= 1.8f) require(cover == 0, "very steep faces must still exclude trees");
            previous = cover;
        }
    }

    private static void checkDryWoodlandBand() {
        for (float t : new float[]{18, 22, 28, 35}) {
            for (float m : new float[]{.54f, .58f, .62f}) {
                var sample = EcologyClassifier.select(0, 0, 100, .1f, false,
                        t, m * (500 + 20 * t), 100).sample();
                require(sample.biomeId() == 7, "dry woodland must span a substantial moisture band");
            }
            var grass = EcologyClassifier.select(0, 0, 100, .1f, false,
                    t, .45f * (500 + 20 * t), 100).sample();
            require(grass.biomeId() == 8, "dry grassland must remain represented");
        }
    }

    private static void checkRegionalLandIdentity() {
        var lowRipple = EcologyClassifier.select(0, 0, 200, .05f, false, 5, 800, 300);
        var highRipple = EcologyClassifier.select(0, 0, 800, .80f, false, 5, 800, 300);
        require(lowRipple.sample().biomeId() == highRipple.sample().biomeId(),
                "local lapse and slope must not create land-biome islands at a regional threshold");
        require(lowRipple.terrainBiomeId() == 0 && highRipple.terrainBiomeId() == 0,
                "isolated steep land must retain its ecological biome");
        require(lowRipple.sample().treeCover() != highRipple.sample().treeCover(),
                "local lapse and slope must continue to control continuous cover");

        var cold = EcologyClassifier.select(0, 0, 300, .1f, false, 4, 800, 300);
        var temperate = EcologyClassifier.select(0, 0, 300, .1f, false, 6, 800, 300);
        require(cold.sample().biomeId() != temperate.sample().biomeId(), "different broad climates must retain different ecosystems");

    }

    private static void checkColdEcology() {
        require(EcologyClassifier.BIOMES.get(11).equals("snowy_boreal_forest")
                && EcologyClassifier.BIOMES.get(14).equals("steppe"), "existing registry indices remain stable");
        require(EcologyClassifier.select(0, 0, 300, .1f, false, -5, 700, 300).sample().biomeId() == 12,
                "wet cold lowlands must be snowy forest");
        require(EcologyClassifier.select(0, 0, 300, .1f, false, -5, 400, 300).sample().biomeId() == 16,
                "intermediate moisture must be snowy woodland");
        require(EcologyClassifier.select(0, 0, 300, .1f, false, -5, 160, 300).sample().biomeId() == 17,
                "dry cold lowlands must be snowy tundra");
        require(EcologyClassifier.select(0, 0, 2000, 1.1f, false, -5, 700, 2000).sample().biomeId() == 18,
                "cold rugged land must be alpine tundra");
        require(EcologyClassifier.select(0, 0, 2000, 1.1f, false, -10, 700, 2000).sample().biomeId() == 19,
                "severe cold rugged land must be glacial");
        for (int elevation = 0; elevation <= 6000; elevation += 25) {
            var warm = EcologyClassifier.select(0, 0, elevation, .8f, false, 12, 900, 300).sample();
            require(EcologyClassifier.coldState(warm.temperature()) == 0, "warm mountains cannot become glacial by height");
            require(!EcologyClassifier.naturalSnowCover(0, 0, elevation, warm.temperature()), "no height-only snow");
            require(Math.abs(warm.temperature() - 12) <= 2, "local lapse must stay bounded");
        }
        for (float moisture : new float[]{.3f, .8f, 1.5f}) {
            float priorSnow = 1, priorCover = 0;
            for (int step = -1500; step <= 1000; step++) {
                float t = step / 100f;
                var sample = EcologyClassifier.select(-17, 16, 300, .1f, false, t,
                        moisture * (500 + 20 * Math.max(0, t)), 300).sample();
                float snow = EcologyClassifier.naturalSnowCoverage(t, 0);
                require(snow <= priorSnow && priorSnow - snow < .025f, "continuous monotonic snow exposure");
                require(Math.abs(sample.treeCover() - priorCover) < .01f, "no tree wall at a cold label edge");
                boolean snowyLabel = sample.biomeId() == 12 || sample.biomeId() >= 16;
                require(snowyLabel == (EcologyClassifier.coldState(t) >= .5f), "snow identity shares cold state");
                if (snow == 1) require(snowyLabel, "full snow may not blanket a warm identity");
                priorSnow = snow; priorCover = sample.treeCover();
            }
        }
        for (float t : new float[]{-5, -2, -1.5f, -1, 2}) {
            int snow = 0;
            for (int x = -128; x < 128; x++) for (int z = -128; z < 128; z++) {
                boolean covered = EcologyClassifier.naturalSnowCover(x, z, 300, t);
                require(covered == EcologyClassifier.naturalSnowCover(x, z, 4000, t), "snow depends on climate, not height");
                if (covered) snow++;
            }
            float expected = EcologyClassifier.naturalSnowCoverage(t, 0);
            require(Math.abs(snow / 65536f - expected) < .02f, "column snow fraction follows continuous climate");
            System.out.printf(java.util.Locale.ROOT, "Cold climate T=%.1f snow=%.4f expected=%.4f%n", t, snow / 65536f, expected);
        }
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
