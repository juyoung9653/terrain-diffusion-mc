package com.github.xandergos.terraindiffusionmc.pipeline;

/** Continuous habitat suitability, not a simulation of groundwater or salinity.
 * Marine admission requires the immutable sea-level terrain mask; inland suitability
 * uses local valley convergence. Decoration must additionally check actual water/support.
 */
public final class WetlandEcology {
    static final int MARINE_REACH_BLOCKS = 192;
    static final float MARINE_MAX_ELEVATION_BLOCKS = 4;
    public record Weights(float reedMarsh, float freshwaterSwamp, float mangrove, float riparian) {
        public float total() { return Math.min(1, reedMarsh + freshwaterSwamp + mangrove + riparian); }
    }

    private WetlandEcology() { }

    public static float inlandPotential(float slope, float valleyDepthMetres) {
        return smooth(.25f, 2.5f, valleyDepthMetres) * (1 - smooth(.035f, .18f, slope));
    }

    public static float wetness(float temperature, float moisture, float valleyPotential) {
        return clamp(valleyPotential) * smooth(.55f, 1.15f, moisture) * smooth(-2, 8, temperature);
    }

    public static float marineInfluence(float elevationMetres, float slope, float temperature,
                                        float moisture, float seaDistanceBlocks, float openWater, float relief) {
        return marineClimate(temperature, moisture) * marineTerrainPotential(elevationMetres,
                Math.max(slope, relief), seaDistanceBlocks, openWater);
    }

    static float marineClimate(float temperature, float moisture) {
        return smooth(14, 22, temperature) * smooth(.55f, 1.05f, moisture);
    }

    /** Reach follows low land from the sea, not a radius that can jump over a coastal ridge. */
    static float marineTerrainPotential(float elevationMetres, float habitatSlope,
                                       float seaDistanceBlocks, float openWater) {
        if (!Float.isFinite(seaDistanceBlocks) || elevationMetres < 0 || openWater <= 0) return 0;
        // Compatibility path without terrain context: keep metre-based local admission.
        return (1 - smooth(2, 12, elevationMetres))
                * (1 - smooth(.025f, .12f, habitatSlope))
                * (1 - smooth(48, 96, seaDistanceBlocks))
                * smooth(.03f, .15f, openWater) * (1 - smooth(.50f, .85f, openWater));
    }

    /** Habitat extent is measured in game blocks, like regional biome ownership.
     * Pipeline metres must be divided by physicalPixelSize before calling this.
     * This describes coastal woodland habitat, not a tidal flooding elevation.
     */
    static float marineLowlandPotential(float elevationBlocks, float habitatSlope, float seaDistanceBlocks) {
        if (!Float.isFinite(elevationBlocks) || !Float.isFinite(seaDistanceBlocks)) return 0;
        return (1 - smooth(2, MARINE_MAX_ELEVATION_BLOCKS, Math.max(0, elevationBlocks)))
                * (1 - smooth(.025f, .12f, habitatSlope))
                * (1 - smooth(128, MARINE_REACH_BLOCKS, seaDistanceBlocks));
    }

    public static Weights weights(EcologyClassifier.Sample sample) {
        float wet = clamp(sample.wetness()), rawMarine = clamp(sample.marineInfluence());
        // A retained shore has no land features. Fade marine decoration in over
        // the same takeover interval as the classifier's ecological canopy.
        float marine = rawMarine * smooth(.15f, .45f, rawMarine);
        float fresh = wet * (1 - marine);
        // Wetness is drainage suitability, not measured flood depth. Turning
        // every saturated basin into reeds left swamp as a narrow contour band.
        // Humid climates can sustain wooded swamp throughout the basin; more
        // seasonal climates retain open marsh. Keep some reeds in wooded cores.
        // Use cached climate, not treeCover (which already includes this blend
        // when features call us) or the discrete biome identity.
        float wooded = .85f * smooth(.95f, 1.45f, sample.moisture());
        float openMarsh = smooth(.55f, .90f, wet) * (1 - wooded);
        float reed = fresh * openMarsh;
        float swamp = fresh * (1 - openMarsh) * smooth(.30f, .65f, wet);
        float riparian = fresh * (1 - smooth(.35f, .70f, wet));
        return new Weights(reed, swamp, marine, riparian);
    }

    public static float treeCover(float original, Weights weights) {
        float sum = weights.total();
        return clamp(original * (1 - sum) + .06f * weights.reedMarsh()
                + .75f * weights.freshwaterSwamp() + .82f * weights.mangrove()
                + Math.max(original, .68f) * weights.riparian());
    }

    public static int biomeId(int original, EcologyClassifier.Sample sample) {
        Weights w = weights(sample);
        if (w.mangrove() >= .45f) return 22;
        // A climate blend must not open a dry gap when neither of the two
        // partitioned weights alone reaches its former admission threshold.
        if (w.reedMarsh() + w.freshwaterSwamp() >= .45f)
            return w.reedMarsh() > w.freshwaterSwamp() ? 20 : 21;
        if (w.freshwaterSwamp() >= .32f) return 21;
        if (w.riparian() >= .18f) return 23;
        return original;
    }

    private static float smooth(float a, float b, float value) { return EcologyClassifier.smooth(a, b, value); }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}
