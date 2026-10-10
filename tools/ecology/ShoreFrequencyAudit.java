import com.github.xandergos.terraindiffusionmc.pipeline.ShoreClassifier;

/** Synthetic shoreline inputs for comparing material decisions; not a measured world coastline. */
public final class ShoreFrequencyAudit {
    private static final int[] Z_TRANSECTS = {-6144, -2048, 2048, 6144};

    private ShoreFrequencyAudit() { }

    public static void main(String[] args) {
        System.out.println("climate,relief,openWater,beach,gravel,snowy,stony,none,gravelShareOfDryBeaches");
        for (Climate climate : Climate.values()) {
            for (float relief : new float[]{.05f, .16f, .24f, .36f}) {
                for (float open : new float[]{.32f, .55f, .85f}) {
                    int beach = 0, gravel = 0, snowy = 0, stony = 0, none = 0;
                    for (int z : Z_TRANSECTS) for (int x = -8192; x <= 8192; x += 64) {
                        short type = ShoreClassifier.select(x, z, 10, .05f,
                                climate.temperature, climate.moisture, 3, open, relief);
                        if (type == ShoreClassifier.BEACH) beach++;
                        else if (type == ShoreClassifier.SNOWY_BEACH) snowy++;
                        else if (type == ShoreClassifier.STONY_SHORE) stony++;
                        else if (type == 0) none++;
                        else gravel++;
                    }
                    int dryBeaches = beach + gravel;
                    System.out.printf(java.util.Locale.ROOT, "%s,%.2f,%.2f,%d,%d,%d,%d,%d,%.1f%%%n",
                            climate.name(), relief, open, beach, gravel, snowy, stony, none,
                            dryBeaches == 0 ? 0 : 100.0 * gravel / dryBeaches);
                }
            }
        }
    }

    private enum Climate {
        TROPICAL_WET(27, 1.2f), TEMPERATE(15, .7f), COOL(8, .7f),
        ARID(26, .2f), SNOWY(-5, .7f);

        final float temperature, moisture;
        Climate(float temperature, float moisture) {
            this.temperature = temperature;
            this.moisture = moisture;
        }
    }
}
