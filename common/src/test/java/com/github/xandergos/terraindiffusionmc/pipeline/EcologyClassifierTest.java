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
        require(reached.size() == EcologyClassifier.BIOMES.size(), "unreachable ecosystems: " + reached);
        require(transitions > 50, "exercise real label boundaries");
        var base = EcologyClassifier.select(0, 0, 300, .1f, false, 15, 800, 300).sample();
        var high = EcologyClassifier.select(0, 0, 800, .1f, false, 15, 800, 300).sample();
        require(Math.abs(base.temperature() - high.temperature() - 3) < .0001f, "relative elevation lapse");
        for (int x = -1024; x < 1024; x++) {
            var a = EcologyClassifier.select(x, 0, 300, .1f, false, 15, 800, 300).sample();
            require(a.biomeId() == base.biomeId(), "no coordinate-dithered biome labels");
        }
        require(EcologyClassifier.select(0, 0, 0, .1f, true, 15, 800, 300).terrainBiomeId() != 0, "coast retained");
        require(EcologyClassifier.select(0, 0, -100, .1f, false, 15, 800, 300).sample().biomeId() == 0, "ocean retained");
        checkRegionalLandIdentity();
        System.out.println("EcologyClassifierTest: PASS (11 ecosystems, " + transitions + " continuous label transitions)");
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

        var isolatedHighpoint = EcologyClassifier.select(0, 0, 1900, .1f, false, -2, 800, 300);
        require(isolatedHighpoint.terrainBiomeId() == 0, "isolated highpoint must not become alpine");
        var broadHighland = EcologyClassifier.select(0, 0, 300, .1f, false, -2, 800, 1900);
        require("terralith:alpine_highlands".equals(TerralithBiomeClassifier.biomeName(broadHighland.terrainBiomeId())),
                "cold broad highlands must retain alpine terrain");
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
