package com.github.xandergos.terraindiffusionmc.pipeline;

/** Behavioral bounds for continuous wetland suitability, independent of feature placement. */
public final class WetlandEcologyTest {
    public static void main(String[] args) {
        checkContinuousWeights();
        checkAdmissions();
        checkEcologicalExclusions();
        checkCoastalCanopyTransition();
        checkWoodedBasinCore();
        System.out.println("WetlandEcologyTest: PASS");
    }

    private static void checkContinuousWeights() {
        float priorCover = -1;
        for (int step = 0; step <= 1_000; step++) {
            float wet = step / 1_000f;
            var weights = WetlandEcology.weights(sample(wet, 0));
            require(weights.total() >= 0 && weights.total() <= 1, "wetland suitability stays bounded");
            float cover = WetlandEcology.treeCover(.56f, weights);
            if (step > 0) require(Math.abs(cover - priorCover) < .02f,
                    "small wetness change made a canopy seam");
            if (step > 0) {
                var before = WetlandEcology.weights(sample(wet - .001f, .22f));
                var after = WetlandEcology.weights(sample(wet, .22f));
                require(distance(before, after) < .02f, "small wetness change made a vegetation-weight seam");
            }
            priorCover = cover;
        }
        for (int step = 1; step <= 1_000; step++) {
            float marine = step / 1_000f;
            var before = WetlandEcology.weights(sample(.80f, marine - .001f));
            var after = WetlandEcology.weights(sample(.80f, marine));
            require(distance(before, after) < .02f, "small marine change made a vegetation-weight seam");
        }
        float dryCover = WetlandEcology.treeCover(.56f, WetlandEcology.weights(sample(0, 0)));
        float wetCover = WetlandEcology.treeCover(.56f, WetlandEcology.weights(sample(.92f, 0)));
        require(wetCover < dryCover, "seasonal marsh must visibly open the canopy");
    }

    private static void checkAdmissions() {
        require(id(20, 1000, .95f, Float.POSITIVE_INFINITY, 0, .04f, 120) == 20,
                "seasonal warm inland basin retains reed marsh");
        require(id(20, 1300, .55f, Float.POSITIVE_INFINITY, 0, .04f, 120) == 21,
                "seasonally flooded warm basin reaches freshwater swamp");
        require(id(20, 1300, .35f, Float.POSITIVE_INFINITY, 0, .04f, 120) == 23,
                "shallow convergent drainage reaches riparian forest");
        require(id(26, 1300, 0, 6, .25f, .01f, .5f) == 22,
                "warm protected sea-connected lowland reaches mangrove swamp");
    }

    private static void checkWoodedBasinCore() {
        // A humid basin must stay wooded from its shoulder through its wettest
        // core, rather than leaving swamp on one thin wetness contour.
        for (int step = 550; step <= 1000; step++) {
            float wet = step / 1000f;
            var humid = new EcologyClassifier.Sample(1, .9f, 20, 1.6f, .04f, wet, 0);
            var weights = WetlandEcology.weights(humid);
            require(WetlandEcology.biomeId(1, humid) == 21,
                    "humid basin lost swamp at wetness=" + wet);
            float cover = WetlandEcology.treeCover(humid.treeCover(), weights);
            require(cover >= .64f, "humid saturated core lost its wooded canopy");
            var featureSample = new EcologyClassifier.Sample(21, cover, 20, 1.6f, .04f, wet, 0);
            require(weights.equals(WetlandEcology.weights(featureSample)),
                    "classifier and decoration must use identical habitat weights");
        }
        var previous = WetlandEcology.weights(new EcologyClassifier.Sample(1, .7f, 20, .8f, .04f, 1, 0));
        for (int step = 801; step <= 1700; step++) {
            var current = WetlandEcology.weights(new EcologyClassifier.Sample(1, .7f, 20,
                    step / 1000f, .04f, 1, 0));
            require(distance(previous, current) < .01f, "marsh/swamp climate blend made a seam");
            require(Math.abs(current.reedMarsh() + current.freshwaterSwamp() - 1) < .00001f,
                    "saturated inland habitat weights must partition the basin");
            previous = current;
        }
        require(id(20, 1500, 1, Float.POSITIVE_INFINITY, 0, .04f, 120) == 21,
                "saturated humid basin reaches freshwater swamp, not reed-only core");
        for (int wetStep = 750; wetStep <= 1000; wetStep += 5)
            for (int moistureStep = 950; moistureStep <= 1500; moistureStep++) {
                var transition = new EcologyClassifier.Sample(1, .7f, 20, moistureStep / 1000f,
                        .04f, wetStep / 1000f, 0);
                int selected = WetlandEcology.biomeId(1, transition);
                require(selected == 20 || selected == 21,
                        "mixing two saturated wetland habitats must not create a dry biome gap");
            }
    }

    private static void checkEcologicalExclusions() {
        require(id(-5, 1400, 1, Float.POSITIVE_INFINITY, 0, .04f, 120) < 20,
                "cold basin cannot become a wetland biome");
        require(id(20, 350, 1, Float.POSITIVE_INFINITY, 0, .04f, 120) < 20,
                "arid basin cannot become a wetland biome");
        float steepPotential = WetlandEcology.inlandPotential(.30f, 12);
        require(steepPotential == 0 && id(20, 1300, steepPotential, Float.POSITIVE_INFINITY, 0, .30f, 120) < 20,
                "steep terrain cannot be admitted as an inland wetland");
        require(id(26, 1300, 0, 6, .75f, .01f, .5f) != 22,
                "open sea cannot become mangrove");
        require(id(26, 1300, 0, Float.POSITIVE_INFINITY, 0, .01f, 120) != 22,
                "mangrove requires sea contact");
        require(id(20, 1300, 0, Float.POSITIVE_INFINITY, 0, .04f, 10) < 20,
                "a low elevation alone cannot create a swamp");
    }

    /** A shore keeps its terrain ID below the marine takeover threshold, without a canopy cliff. */
    private static void checkCoastalCanopyTransition() {
        EcologyClassifier.Selection prior = null;
        boolean crossed = false;
        for (int step = 0; step <= 900; step++) {
            float temperature = 16 + step / 100f;
            EcologyClassifier.Selection current = EcologyClassifier.select(0, 0, .5f, .01f,
                    temperature, 1_000, .5f, 1, .25f, .01f, .90f);
            if (prior != null && prior.terrainBiomeId() != 0 && current.terrainBiomeId() == 0
                    && prior.sample().marineInfluence() < .15f
                    && current.sample().marineInfluence() >= .15f) {
                float priorEffectiveCover = prior.terrainBiomeId() == 0 ? prior.sample().treeCover() : 0;
                float currentEffectiveCover = current.terrainBiomeId() == 0 ? current.sample().treeCover() : 0;
                require(Math.abs(currentEffectiveCover - priorEffectiveCover) < .03f,
                        "marine takeover cannot create a beach-to-forest canopy cliff");
                var priorWeights = prior.terrainBiomeId() == 0 ? WetlandEcology.weights(prior.sample())
                        : new WetlandEcology.Weights(0, 0, 0, 0);
                var currentWeights = current.terrainBiomeId() == 0 ? WetlandEcology.weights(current.sample())
                        : new WetlandEcology.Weights(0, 0, 0, 0);
                require(distance(priorWeights, currentWeights) < .03f,
                        "marine takeover cannot create a wetland-decoration seam");
                crossed = true;
                break;
            }
            prior = current;
        }
        require(crossed, "real coastal sample crosses marine takeover while the shore classifier remains active");
    }

    private static int id(float temperature, float rainfall, float valley, float waterDistance,
                          float openWater, float slope, float elevation) {
        return EcologyClassifier.select(0, 0, elevation, slope, temperature, rainfall, elevation,
                waterDistance, openWater, slope, valley).sample().biomeId();
    }

    private static EcologyClassifier.Sample sample(float wetness, float marine) {
        return new EcologyClassifier.Sample(1, .56f, 20, 1.2f, .04f, wetness, marine);
    }
    private static float distance(WetlandEcology.Weights a, WetlandEcology.Weights b) {
        return Math.abs(a.reedMarsh() - b.reedMarsh()) + Math.abs(a.freshwaterSwamp() - b.freshwaterSwamp())
                + Math.abs(a.mangrove() - b.mangrove()) + Math.abs(a.riparian() - b.riparian());
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
