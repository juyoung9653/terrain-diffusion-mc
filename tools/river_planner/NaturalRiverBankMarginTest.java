import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankMargin;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankFillPolicy;

public final class NaturalRiverBankMarginTest {
    public static void main(String[] args) {
        bankGapAdmission();
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

    private static void bankGapAdmission() {
        require(NaturalRiverBankFillPolicy.allows(12, 2, 0, 5),
                "frozen V12 bank behavior changed");
        require(NaturalRiverBankFillPolicy.allows(13, 1, 1, 2),
                "shallow bank-attached gap rejected");
        require(!NaturalRiverBankFillPolicy.allows(13, 2, 2, 1),
                "two-sided channel gap could build a dam");
        require(!NaturalRiverBankFillPolicy.allows(13, 1, 0, 1),
                "isolated bank fill could build a pillar");
        require(!NaturalRiverBankFillPolicy.allows(13, 1, 1, 3),
                "deep bank fill could build a dirt wall");

        require(NaturalRiverBankFillPolicy.allowsNative(12, 6, true, 0),
                "frozen legacy native bank behavior changed");
        require(!NaturalRiverBankFillPolicy.allowsNative(13, 6, false, 1),
                "six-high native wall was admitted");
        require(NaturalRiverBankFillPolicy.allowsNative(13, 1, true, 1),
                "attached one-block native gap with lip rejected");
        require(!NaturalRiverBankFillPolicy.allowsNative(13, 2, true, 1),
                "native lip escaped the two-block budget");
        require(NaturalRiverBankFillPolicy.allowsNative(13, 2, false, 1),
                "attached two-block native gap rejected");
        require(!NaturalRiverBankFillPolicy.allowsNative(13, 1, false, 0),
                "isolated native post was admitted");

        // At 844,-910 the native carver saw water Y=80 and a crest at 81, so
        // its optional lip would land at Y=82. That elevated, detached lip is
        // the narrow case this guard rejects without changing channel fills.
        require(NaturalRiverBankFillPolicy.allowsNativeLip(12, false, 82, 80),
                "legacy native lip behavior changed");
        require(NaturalRiverBankFillPolicy.allowsNativeLip(13, false, 81, 80),
                "lip at the water-height boundary rejected");
        require(NaturalRiverBankFillPolicy.allowsNativeLip(13, true, 82, 80),
                "in-channel lip rejected");
        require(!NaturalRiverBankFillPolicy.allowsNativeLip(13, false, 82, 80),
                "isolated elevated lip was admitted");
        require(NaturalRiverBankFillPolicy.allowsNativeLip(13, false,
                Integer.MAX_VALUE, Integer.MAX_VALUE), "maximum height overflow rejected lip");
        require(!NaturalRiverBankFillPolicy.allowsNativeLip(13, false,
                Integer.MIN_VALUE + 2, Integer.MIN_VALUE), "minimum height overflow admitted lip");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
