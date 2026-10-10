import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankMargin;

public final class NaturalRiverBankMarginTest {
    public static void main(String[] args) {
        // Same edge distance remains riparian farther from a wide river than a tributary.
        double narrow = NaturalRiverBankMargin.width(1, -841, -1020);
        double wide = NaturalRiverBankMargin.width(12, -841, -1020);
        require(wide > narrow * 1.8, "river width did not broaden the margin");
        require(NaturalRiverBankMargin.moistureDistance(15, 12, -841, -1020)
                < NaturalRiverBankMargin.moistureDistance(4, 1, -841, -1020), "moisture fade ignores width");
        // Invalid/native absent plans must not invent a margin or moisture.
        for (double width : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            require(NaturalRiverBankMargin.width(width, 0, 0) == 0, "invalid width creates apron");
            require(Float.isInfinite(NaturalRiverBankMargin.moistureDistance(2, width, 0, 0)),
                    "invalid plan creates riparian moisture");
        }
        require(Float.isInfinite(NaturalRiverBankMargin.moistureDistance(Double.NaN, 1, 0, 0)),
                "invalid distance creates moisture");
        double min = 99, max = 0;
        for (int x = -96; x <= 96; x++) {
            double previous = NaturalRiverBankMargin.width(8, x, -97);
            for (int z = -96; z <= 96; z++) {
                double margin = NaturalRiverBankMargin.width(8, x, z);
                min = Math.min(min, margin); max = Math.max(max, margin);
                require(margin == NaturalRiverBankMargin.width(8, x, z), "non-deterministic margin");
                require(Math.abs(margin - previous) < .2, "material apron has a block seam");
                require(margin > 0 && margin <= 6, "unbounded apron scan");
                require(Math.abs(NaturalRiverBankMargin.moistureDistance(8 + margin, 8, x, z) - 4) < .0001,
                        "width normalization does not reach ecology fade boundary");
                previous = margin;
            }
        }
        require(max - min > .5, "margin is a uniform strip");
        int narrowSediment = 0, wideSediment = 0;
        for (int x = -96; x <= 96; x++) for (int z = -96; z <= 96; z++) {
            double thin = NaturalRiverBankMargin.sedimentWidth(1, x, z);
            double broad = NaturalRiverBankMargin.sedimentWidth(12, x, z);
            require(thin < .67 && broad <= 3.75 && broad > thin * 4,
                    "tributary sediment apron is too broad");
            require(Math.abs(thin - NaturalRiverBankMargin.sedimentWidth(1, x - 1, z)) < .04,
                    "sediment width jumps at a block/chunk boundary");
            for (double edge = .25; edge <= 6; edge += .25) {
                if (NaturalRiverBankMargin.drySediment(1 + edge, 1, 75, 74, x, z)) narrowSediment++;
                if (NaturalRiverBankMargin.drySediment(12 + edge, 12, 75, 74, x, z)) wideSediment++;
            }
            require(!NaturalRiverBankMargin.drySediment(1.25, 1, 90, 74, x, z),
                    "dry hillside acquired river sediment");
            require(!NaturalRiverBankMargin.drySediment(1.25, Double.NaN, 74, 74, x, z),
                    "invalid plan acquired river sediment");
            require(!NaturalRiverBankMargin.drySediment(Double.NaN, 1, 74, 74, x, z),
                    "invalid distance acquired river sediment");
            require(!NaturalRiverBankMargin.drySediment(1.25, 1, Integer.MIN_VALUE, Integer.MAX_VALUE, x, z),
                    "overflow acquired river sediment");
        }
        require(wideSediment > narrowSediment * 4, "dry sediment coverage ignores river width");
        // Flat and stepped banks still block every lateral cell up to the highest source Y.
        for (int water = -60; water <= 300; water++) {
            int crest = NaturalRiverBankMargin.containmentCrest(7, water);
            require(crest >= water, "full source can overtop containing block");
            require(crest < water + 1, "redundant dry parapet remains");
            for (int version = 4; version <= 6; version++)
                require(NaturalRiverBankMargin.containmentCrest(version, water) == water + 1,
                        "frozen legacy geometry changed");
        }
        System.out.println("NaturalRiverBankMarginTest: passed width, invalid-plan, seam, and containment checks; "
                + "dry sediment samples narrow=" + narrowSediment + " wide=" + wideSediment);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
