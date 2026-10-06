package com.github.xandergos.terraindiffusionmc.world.ecology;

/** Selects optional Greeneries species within an already chosen groundcover slot. */
public final class OptionalGroundcoverPolicy {
    private OptionalGroundcoverPolicy() { }

    /** Returns a verified Greeneries 1.0.3 block path, or null to retain vanilla grass. */
    public static String grass(float temperature, float moisture, float treeCover,
                               float speciesRoll, float shapeRoll) {
        float blue = 1.2F * (1 - smooth(0, 8, temperature));
        float red = smooth(14, 26, temperature) * (1 - smooth(.8F, 1.4F, moisture));
        float bent = smooth(2, 12, temperature) * (1 - smooth(.8F, 1.3F, moisture))
                * (1 - .6F * FeatureEcologyMath.clamp01(treeCover));
        float ordinary = .35F;
        float choice = FeatureEcologyMath.clamp01(speciesRoll) * (blue + red + bent + ordinary);
        if (choice < blue) return shape(shapeRoll, "very_short_blue_grass", "short_blue_grass", "bushy_blue_grass");
        choice -= blue;
        if (choice < red) return shape(shapeRoll, "very_short_red_fescue", "short_red_fescue", "bushy_red_fescue");
        choice -= red;
        if (choice < bent) return shape(shapeRoll, "very_short_common_bent", "short_common_bent", "bushy_common_bent");
        choice -= bent;
        if (choice < ordinary * .5F) return shape(shapeRoll, "very_short_grass", "bushy_grass", "medium_grass");
        return null;
    }

    /** Royal fern is confined to moist, sheltered sites; dry ferns remain vanilla. */
    public static String fern(float temperature, float moisture, float treeCover,
                              float speciesRoll, float shapeRoll) {
        float chance = .55F * smooth(.65F, 1.2F, moisture)
                * smooth(.2F, .7F, treeCover) * smooth(-3, 8, temperature);
        if (speciesRoll >= chance) return null;
        return shapeRoll < .55F ? "short_royal_fern" : "medium_royal_fern";
    }

    private static String shape(float roll, String shortPath, String middlePath, String bushyPath) {
        return roll < .25F ? shortPath : roll < .8F ? middlePath : bushyPath;
    }

    private static float smooth(float low, float high, float value) {
        float t = FeatureEcologyMath.clamp01((value - low) / (high - low));
        return t * t * (3 - 2 * t);
    }
}
