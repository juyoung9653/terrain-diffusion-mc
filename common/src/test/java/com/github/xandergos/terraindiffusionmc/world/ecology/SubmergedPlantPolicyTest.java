package com.github.xandergos.terraindiffusionmc.world.ecology;

/** Standalone seagrass worldgen density regression, with no Minecraft bootstrap. */
public final class SubmergedPlantPolicyTest {
    public static void main(String[] args) {
        int marine = 0, fresh = 0, deep = 0, stone = 0, changedSeed = 0;
        double low = 1, high = 0, biggestStep = 0;
        for (int z = -128; z < 128; z++) {
            for (int x = -128; x < 128; x++) {
                check(!SubmergedPlantPolicy.allows(true, false, true, false, true, 0, 42, x, z),
                        "exposed beach permitted seagrass");
                check(SubmergedPlantPolicy.allows(false, false, true, false, false, 0, 42, x, z),
                        "non-TD placement changed");
                boolean m = SubmergedPlantPolicy.allows(true, true, true, false, true, 3, 42, x, z);
                if (m) marine++;
                if (SubmergedPlantPolicy.allows(true, true, false, true, true, 3, 42, x, z)) fresh++;
                if (SubmergedPlantPolicy.allows(true, true, true, false, true, 8, 42, x, z)) deep++;
                if (SubmergedPlantPolicy.allows(true, true, true, false, false, 3, 42, x, z)) stone++;
                if (m != SubmergedPlantPolicy.allows(true, true, true, false, true, 3, 43, x, z)) changedSeed++;
                double p = SubmergedPlantPolicy.probability(true, true, true, false, true, 3, 42, x, z);
                low = Math.min(low, p);
                high = Math.max(high, p);
                biggestStep = Math.max(biggestStep, Math.abs(p - SubmergedPlantPolicy.probability(
                        true, true, true, false, true, 3, 42, x + 1, z)));
            }
        }
        check(marine > 1500 && marine < 16000, "marine cover " + marine);
        check(fresh > marine && fresh < 24000, "freshwater cover " + fresh);
        check(deep > 0 && deep < marine, "deep water thinning " + deep);
        check(stone > 0 && stone < marine, "substrate thinning " + stone);
        check(changedSeed > 1500, "seed does not alter placements " + changedSeed);
        check(low < .05 && high > .2, "patch range " + low + ".." + high);
        check(biggestStep < .15, "visible density seam " + biggestStep);
        for (int x : new int[]{-29_999_999, -32, -16, -1, 0, 15, 16, 31, 29_999_999}) {
            boolean first = SubmergedPlantPolicy.allows(true, true, true, false, true, 3, 67, x, -x);
            for (int i = 0; i < 100; i++) {
                SubmergedPlantPolicy.allows(true, true, false, true, true, 2, 89, i, -i);
                check(first == SubmergedPlantPolicy.allows(true, true, true, false, true, 3, 67, x, -x),
                        "order dependent placement");
            }
        }
        System.out.printf("SubmergedPlantPolicy: marine=%d freshwater=%d deep=%d stone=%d step=%.4f%n",
                marine, fresh, deep, stone, biggestStep);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
