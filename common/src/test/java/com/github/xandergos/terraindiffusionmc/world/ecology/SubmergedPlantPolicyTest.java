package com.github.xandergos.terraindiffusionmc.world.ecology;

/** Standalone regression for local-depth, exposure-aware aquatic placement. */
public final class SubmergedPlantPolicyTest {
    public static void main(String[] args) {
        int sheltered = 0, exposed = 0, shelf = 0, deep = 0, tall = 0;
        int reefPickles = 0, sandPickles = 0, changedSeed = 0;
        double largestStep = 0;
        for (int z = -128; z < 128; z++) for (int x = -128; x < 128; x++) {
            check(!SubmergedPlantPolicy.allows(true, false, true, false, true, 3,
                    true, 1, false, 42, x, z), "dry beach permitted seagrass");
            check(SubmergedPlantPolicy.allows(false, false, true, false, false, 0,
                    true, 1, false, 42, x, z), "non-TD seagrass changed");
            boolean shortGrass = SubmergedPlantPolicy.allows(true, true, true, false,
                    true, 3, true, 0, false, 42, x, z);
            boolean tallGrass = SubmergedPlantPolicy.allows(true, true, true, false,
                    true, 3, true, 0, true, 42, x, z);
            check(!tallGrass || shortGrass, "tall grass escaped its short bed");
            if (shortGrass) sheltered++;
            if (SubmergedPlantPolicy.allows(true, true, true, false, true, 3,
                    true, 1, false, 42, x, z)) exposed++;
            if (SubmergedPlantPolicy.allows(true, true, true, false, true, 10,
                    true, 1, false, 42, x, z)) shelf++;
            if (SubmergedPlantPolicy.allows(true, true, true, false, true, 24,
                    true, 0, false, 42, x, z)) deep++;
            if (tallGrass) tall++;
            if (SubmergedPlantPolicy.allowsSeaPickle(true, true, true, false, true,
                    true, false, 5, .2, 42, x, z)) reefPickles++;
            if (SubmergedPlantPolicy.allowsSeaPickle(true, true, true, false, true,
                    false, true, 3, 1, 42, x, z)) sandPickles++;
            if (shortGrass != SubmergedPlantPolicy.allows(true, true, true, false,
                    true, 3, true, 0, false, 43, x, z)) changedSeed++;
            double current = SubmergedPlantPolicy.probability(true, true, true, false,
                    true, 7, true, .5, false, 42, x, z);
            largestStep = Math.max(largestStep, Math.abs(current
                    - SubmergedPlantPolicy.probability(true, true, true, false,
                    true, 7, true, .5, false, 42, x + 1, z)));
        }
        check(sheltered > exposed * 4, "open shallow sand not suppressed");
        check(shelf > sheltered, "shelf beds did not increase from shallows");
        check(deep > 0 && deep < shelf, "deep taper " + deep + "/" + shelf);
        check(tall > 0 && tall < sheltered, "tall bed concentration " + tall);
        check(reefPickles > sandPickles * 15, "pickles not reef-focused");
        check(changedSeed > 1000, "seed does not alter placements " + changedSeed);
        check(largestStep < .08, "visible horizontal density seam " + largestStep);
        check(SubmergedPlantPolicy.probability(true, true, true, true, true, 3,
                true, 0, false, 4, 9, 10)
                == SubmergedPlantPolicy.probability(true, true, true, true, true, 3,
                true, 1, false, 4, 9, 10), "freshwater reads marine exposure");
        check(SubmergedPlantPolicy.seaPickleProbability(true, true, true, true, true,
                true, false, 5, 0, 4, 9, 10) == 0, "freshwater pickle admitted");
        check(SubmergedPlantPolicy.seaPickleProbability(true, true, true, false, false,
                true, false, 5, 0, 4, 9, 10) == 0, "cold pickle admitted");
        double before = SubmergedPlantPolicy.probability(true, true, true, false,
                true, 11, true, .8, false, 99, 13, -17);
        double after = SubmergedPlantPolicy.probability(true, true, true, false,
                true, 12, true, .8, false, 99, 13, -17);
        check(Math.abs(after - before) < .03, "depth-12 cliff " + before + " -> " + after);
        for (long seed : new long[]{0, -1, 42, 2875842661134670257L}) {
            for (int x = -96; x <= 96; x += 3) for (int depth = 1; depth <= 40; depth++) {
                double shelteredP = SubmergedPlantPolicy.probability(true, true, true, false,
                        true, depth, true, 0, false, seed, x, -2 * x);
                double exposedP = SubmergedPlantPolicy.probability(true, true, true, false,
                        true, depth, true, 1, false, seed, x, -2 * x);
                double tallP = SubmergedPlantPolicy.probability(true, true, true, false,
                        true, depth, true, 1, true, seed, x, -2 * x);
                check(0 <= tallP && tallP <= exposedP && exposedP <= shelteredP && shelteredP <= 1,
                        "invalid exposure or tall ordering at depth " + depth);
                if (depth >= 32) check(shelteredP == 0, "deep marine cutoff not reached");
                double fresh = SubmergedPlantPolicy.probability(true, true, false, true,
                        true, depth, false, 0, false, seed, x, -2 * x);
                check(fresh == SubmergedPlantPolicy.probability(true, true, true, true,
                        true, depth, true, 1, false, seed, x, -2 * x), "freshwater context changed");
                check(SubmergedPlantPolicy.allowsSeaPickle(false, false, false, true, false,
                        false, true, depth, 1, seed, x, -2 * x), "non-TD pickle changed");
            }
        }
        for (int x : new int[]{-29_999_999, -32, -16, -1, 0, 15, 16, 31, 29_999_999}) {
            boolean first = SubmergedPlantPolicy.allows(true, true, true, false, true, 6,
                    true, .4, false, 67, x, -x);
            for (int i = 0; i < 100; i++) {
                SubmergedPlantPolicy.allowsSeaPickle(true, true, true, false, true,
                        true, false, 5, .1, 89, i, -i);
                check(first == SubmergedPlantPolicy.allows(true, true, true, false, true, 6,
                        true, .4, false, 67, x, -x), "order-dependent placement");
            }
        }
        System.out.printf("SubmergedPlantPolicy: sheltered=%d exposed=%d shelf=%d deep=%d tall=%d reefPickles=%d sandPickles=%d step=%.4f%n",
                sheltered, exposed, shelf, deep, tall, reefPickles, sandPickles, largestStep);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
