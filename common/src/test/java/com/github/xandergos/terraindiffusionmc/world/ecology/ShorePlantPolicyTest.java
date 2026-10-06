package com.github.xandergos.terraindiffusionmc.world.ecology;

/** Standalone deterministic worldgen policy regression; no Minecraft bootstrap needed. */
public final class ShorePlantPolicyTest {
    public static void main(String[] args) {
        int fresh = 0, ordinary = 0, sandy = 0, seedChanges = 0;
        double min = 1, max = 0, biggestStep = 0;
        for (int z = -128; z < 128; z++) {
            for (int x = -128; x < 128; x++) {
                check(!ShorePlantPolicy.allows(true, false, true, 42, x, z), "marine reed escaped");
                boolean f = ShorePlantPolicy.allows(false, true, false, 42, x, z);
                if (f) fresh++;
                if (ShorePlantPolicy.allows(false, false, false, 42, x, z)) ordinary++;
                if (ShorePlantPolicy.allows(false, false, true, 42, x, z)) sandy++;
                if (f != ShorePlantPolicy.allows(false, true, false, 43, x, z)) seedChanges++;
                check(f == ShorePlantPolicy.allows(true, true, false, 42, x, z), "freshwater tag precedence");
                double p = ShorePlantPolicy.probability(false, true, false, 42, x, z);
                min = Math.min(min, p); max = Math.max(max, p);
                biggestStep = Math.max(biggestStep, Math.abs(p - ShorePlantPolicy.probability(false, true, false, 42, x + 1, z)));
                check(p >= .25 && p <= .8, "density bounds");
            }
        }
        check(fresh > 22000 && fresh < 45000, "freshwater density " + fresh);
        check(ordinary > 4000 && ordinary < 16000 && ordinary < fresh, "wet bank thinning " + ordinary);
        check(sandy > 1000 && sandy < ordinary / 2, "sandy bank thinning " + sandy);
        check(seedChanges > 15000, "seed decorrelation " + seedChanges);
        check(min < .3 && max > .75, "nonuniform patches");
        check(biggestStep < .17, "coordinate seams " + biggestStep);
        for (int x : new int[]{-29_999_999, -32, -16, -1, 0, 15, 16, 31, 29_999_999}) {
            boolean first = ShorePlantPolicy.allows(false, true, false, 67, x, -x);
            for (int i = 0; i < 100; i++) {
                ShorePlantPolicy.allows(false, true, false, 89, i, -i);
                check(first == ShorePlantPolicy.allows(false, true, false, 67, x, -x), "order dependence");
            }
        }
        System.out.printf("ShorePlantPolicy: 65536 positions; freshwater=%d wet-bank=%d sand-bank=%d max-step=%.4f%n",
                fresh, ordinary, sandy, biggestStep);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
