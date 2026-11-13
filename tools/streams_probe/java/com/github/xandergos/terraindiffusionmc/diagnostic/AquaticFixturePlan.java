package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.world.ecology.SubmergedPlantPolicy;

/** Cheap necessary-condition check using the production policy, without Minecraft. */
public final class AquaticFixturePlan {
    public static final int PICKLE_WATER_DEPTH = 5;
    private AquaticFixturePlan() { }

    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        String[] chunks = args[1].split(";");
        String[] first = chunks[0].split(",");
        int firstX = Integer.parseInt(first[0]), firstZ = Integer.parseInt(first[1]);
        // The native fixture searches only the loaded 3x3 around its first chunk.
        // Check requested chunks only: incidental loaded neighbours are not evidence.
        for (String chunk : chunks) {
            String[] pair = chunk.split(",");
            int cx = Integer.parseInt(pair[0]), cz = Integer.parseInt(pair[1]);
            if (Math.abs((long) cx - firstX) > 1 || Math.abs((long) cz - firstZ) > 1) continue;
            for (int dz = 1; dz < 15; dz++) for (int dx = 1; dx < 15; dx++) {
                int x = Math.addExact(Math.multiplyExact(cx, 16), dx);
                int z = Math.addExact(Math.multiplyExact(cz, 16), dz);
                // Full exposure is conservative: the real shoreline field can only
                // increase acceptance for this warm-ocean/coral fixture.
                if (SubmergedPlantPolicy.allowsSeaPickle(true, true, true, false, true,
                        true, false, PICKLE_WATER_DEPTH, 1, seed, x, z)) {
                    System.out.printf("{\"seed\":\"%d\",\"waterDepth\":%d,\"x\":%d,\"z\":%d}%n",
                            seed, PICKLE_WATER_DEPTH, x, z);
                    return;
                }
            }
        }
        System.err.println("No guaranteed sea-pickle site in requested fixture chunks; "
                + "select an adjacent chunk with an accepted warm-ocean coral column.");
        System.exit(2);
    }
}
