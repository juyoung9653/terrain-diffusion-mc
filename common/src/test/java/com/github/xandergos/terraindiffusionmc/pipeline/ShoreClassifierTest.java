package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.HashSet;
import java.util.Set;

public final class ShoreClassifierTest {
    public static void main(String[] args) {
        require(select(20, .05f, 20, .7f, 3, .5f, .05f) == ShoreClassifier.BEACH, "flat warm sand");
        require(select(20, .05f, -5, .7f, 3, .5f, .05f) == ShoreClassifier.SNOWY_BEACH, "cold sand");
        require(select(20, .4f, 20, .7f, 3, .5f, .2f) == ShoreClassifier.STONY_SHORE, "rocky coast");
        short gravel = (short) (TerralithBiomeClassifier.biomeNames()
                .indexOf("terralith:gravel_beach") + 1);
        require(gravel > 0, "gravel biome is in the palette");
        require(select(20, .1f, 10, .7f, 3, .5f, .16f) == ShoreClassifier.BEACH,
                "rolling shore without coarse substrate stays sandy");
        require(select(20, .05f, 8, .7f, 3, .55f, .05f) == ShoreClassifier.BEACH,
                "cool flat shore is not automatically gravel");
        require(select(20, .05f, -5, .7f, 3, .55f, .24f) == ShoreClassifier.SNOWY_BEACH,
                "snow takes precedence over coarse substrate");
        require(select(20, .4f, -5, .7f, 3, .55f, .4f) == ShoreClassifier.STONY_SHORE,
                "rock takes precedence over snow");
        require(select(20, .05f, 20, 1.4f, 3, .3125f, .05f) == 0, "sheltered wet vegetated bank");
        require(select(20, .05f, 20, .7f, 3, 0, .05f) == 0, "small pond has no beach rim");
        require(select(20, .05f, 20, .7f, 24, .5f, .05f) == 0, "no universal 24-block strip");
        require(select(80, .05f, 20, .7f, 1, .5f, .05f) == 0, "no beach high on inland hillside");
        require(select(-1, .05f, 20, .7f, 0, .5f, .05f) == 0, "ocean handled separately");
        var coast = EcologyClassifier.select(0, 0, 20, .1f, 10, 490, 20, 3, .5f, .16f);
        require(coast.terrainBiomeId() == ShoreClassifier.select(0, 0, 20, .1f,
                coast.sample().temperature(), coast.sample().moisture(), 3, .5f, .16f),
                "ecology passes its local shore measurements to the classifier");
        require(EcologyClassifier.select(0, 0, 20, .1f, 10, 490, 20, 3, 0, .16f)
                .terrainBiomeId() == 0, "inland water does not override ecological land");
        int transitions = 0;
        Set<Short> types = new HashSet<>();
        Set<Integer> widths = new HashSet<>();
        short previous = 0;
        for (int x = -8192; x <= 8192; x++) {
            short type = ShoreClassifier.select(x, -257, 10, .05f, 15, .7f, 1, .5f, .24f);
            types.add(type);
            if (x > -8192 && previous != type) transitions++;
            previous = type;
            int width = 0;
            for (int distance = 1; distance <= 24; distance++)
                if (ShoreClassifier.select(x, -257, 10, .05f, 15, .7f, distance, .5f, .24f) != 0) width++;
            widths.add(width);
            require(width > 0 && width < 20, "bounded narrow beach apron");
        }
        require(types.contains(ShoreClassifier.BEACH) && types.contains(gravel), "broad sand/gravel regions");
        require(transitions > 0 && transitions < 100, "no per-column material speckle: " + transitions);
        require(widths.size() >= 3, "variable shore width: " + widths);
        System.out.println("ShoreClassifierTest: PASS (16385-column transect, " + transitions
                + " substrate transitions, widths=" + widths + ")");
    }

    private static short select(float elevation, float slope, float temperature, float moisture,
                                float distance, float open, float relief) {
        return ShoreClassifier.select(0, 0, elevation, slope, temperature, moisture, distance, open, relief);
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
