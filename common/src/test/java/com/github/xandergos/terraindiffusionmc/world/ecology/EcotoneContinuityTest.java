package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import java.util.HashSet;
import java.util.Set;

/** Exercises actual climate selection and expected plant rates across biome labels. */
public final class EcotoneContinuityTest {
    public static void main(String[] args) {
        Set<Integer> labels = new HashSet<>();
        float maximumJump = 0;
        int crossings = 0;
        for (float temperature : new float[]{-3, 3, 10, 20, 28}) {
            float[] previous = null;
            int previousLabel = 0;
            for (int step = 0; step <= 2000; step++) {
                float moisture = step / 1000f;
                var sample = EcologyClassifier.select(41, -53, 200, .1f, false,
                        temperature, moisture * (500 + 20 * Math.max(0, temperature)), 200).sample();
                labels.add(sample.biomeId());
                float[] rates = rates(sample);
                if (previous != null) {
                    if (previousLabel != sample.biomeId()) crossings++;
                    for (int k = 0; k < rates.length; k++)
                        maximumJump = Math.max(maximumJump, Math.abs(rates[k] - previous[k]));
                }
                previous = rates;
                previousLabel = sample.biomeId();
            }
        }
        require(labels.size() == 11 && !labels.contains(0), "exercise every ecological biome");
        require(crossings >= 10 && maximumJump < .005f, "moisture label transitions must not switch plant populations");
        for (float moisture : new float[]{.2f, .55f, .8f, 1.5f}) {
            float[] previous = null;
            for (int step = -1200; step <= 3500; step++) {
                float temperature = step / 100f;
                var sample = EcologyClassifier.select(41, -53, 200, .1f, false,
                        temperature, moisture * (500 + 20 * Math.max(0, temperature)), 200).sample();
                float[] current = rates(sample);
                if (previous != null) for (int k = 0; k < current.length; k++)
                    require(Math.abs(current[k] - previous[k]) < .005f, "temperature transition must stay continuous");
                previous = current;
            }
        }
        var frozen = FeatureEcologyMath.groundcoverWeights(-12, 1, .5f, 0);
        require(frozen.total() == 0, "frozen ground must not grow understory");
        var desert = FeatureEcologyMath.groundcoverWeights(28, .1f, 0, 0);
        require(desert.grass() == 0 && desert.fern() == 0 && desert.cactus() > 0, "desert plant exclusions");
        var cold = FeatureEcologyMath.groundcoverWeights(2, 1.2f, .8f, .1f);
        require(cold.bamboo() == 0 && cold.cactus() == 0 && cold.fern() > 0, "cold understory exclusions");
        float previousCover = 1;
        for (int elevation = 900; elevation <= 1600; elevation++) {
            var sample = EcologyClassifier.select(41, -53, elevation, .1f, false, -1, 700, elevation).sample();
            require(sample.treeCover() <= previousCover && previousCover - sample.treeCover() < .5f,
                    "cold canopy must taper before alpine boundary");
            previousCover = sample.treeCover();
            if (elevation == 1599) require(previousCover < .0001f, "no forest wall below alpine boundary");
        }
        require(previousCover == 0, "alpine has no ecological canopy");
        require(EcologyClassifier.select(41, -53, 1700, .1f, false, 15, 1000, 1700).sample().treeCover() > .5f,
                "warm mountains must not inherit a universal altitude cutoff");
        for (int scale : new int[]{2, 5}) for (int start : new int[]{-64, 0, 29000000}) {
            for (int x = start; x < start + 64; x++) {
                float a = FeatureEcologyMath.groundcoverChance(15, .8f, .5f, .1f, 9123, x, -31, scale);
                float b = FeatureEcologyMath.groundcoverChance(15, .8f, .5f, .1f, 9123, x + 1, -31, scale);
                require(Math.abs(a - b) < .02f, "density field must cross chunk boundaries continuously");
            }
        }
        System.out.printf("EcotoneContinuityTest: PASS labels=%d crossings=%d max-moisture-jump=%.6f%n",
                labels.size(), crossings, maximumJump);
    }

    private static float[] rates(EcologyClassifier.Sample sample) {
        var weights = FeatureEcologyMath.groundcoverWeights(sample.temperature(), sample.moisture(), sample.treeCover(), sample.slope());
        float chance = FeatureEcologyMath.groundcoverChance(sample.temperature(), sample.moisture(), sample.treeCover(), sample.slope(), 9123, 41, -53, 2);
        require(chance >= 0 && chance <= .55f, "bounded density");
        var kinds = FeatureEcologyMath.GroundcoverKind.values();
        float[] rates = new float[kinds.length];
        for (int k = 0; k < kinds.length; k++) {
            require(weights.weight(kinds[k]) >= 0, "nonnegative selection weights");
            rates[k] = weights.total() == 0 ? 0 : chance * weights.weight(kinds[k]) / weights.total();
        }
        if (weights.total() > 0) for (float roll : new float[]{0, .25f, .5f, .75f, 1})
            require(weights.weight(weights.choose(roll)) > 0, "selection cannot return an excluded plant");
        return rates;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
