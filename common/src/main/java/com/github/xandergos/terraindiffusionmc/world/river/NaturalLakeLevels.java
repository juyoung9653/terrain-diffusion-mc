package com.github.xandergos.terraindiffusionmc.world.river;

import java.util.List;

/** Resolve nested native basin masks once, without changing their footprints. */
public final class NaturalLakeLevels {
    private NaturalLakeLevels() { }

    public record Lake(int level, int digDepth, int spacing, int minX, int minZ,
                       int width, int height, long[] mask) { }

    public static int[] resolve(int version, List<Lake> lakes) {
        int size = lakes.size();
        int[] parents = new int[size], levels = new int[size];
        for (int i = 0; i < size; i++) {
            parents[i] = i;
            levels[i] = lakes.get(i).level();
        }
        if (version < 10) return levels;
        for (int i = 0; i < size; i++) for (int j = 0; j < i; j++) {
            if (!nested(lakes.get(i), lakes.get(j))) continue;
            int a = root(parents, i), b = root(parents, j);
            if (a != b) {
                parents[a] = b;
                levels[b] = Math.min(levels[a], levels[b]);
            }
        }
        int[] result = new int[size];
        for (int i = 0; i < size; i++) result[i] = levels[root(parents, i)];
        return result;
    }

    private static int root(int[] parents, int i) {
        while (parents[i] != i) {
            parents[i] = parents[parents[i]];
            i = parents[i];
        }
        return i;
    }

    private static boolean nested(Lake a, Lake b) {
        if (a.digDepth() != 0 || b.digDepth() != 0 || a.spacing() != b.spacing()
                || a.minX() != b.minX() || a.minZ() != b.minZ()
                || a.width() != b.width() || a.height() != b.height()
                || a.mask().length != b.mask().length) return false;
        boolean aInside = true, bInside = true, aOccupied = false, bOccupied = false;
        for (int i = 0; i < a.mask().length; i++) {
            long av = a.mask()[i], bv = b.mask()[i];
            aOccupied |= av != 0;
            bOccupied |= bv != 0;
            aInside &= (av & ~bv) == 0;
            bInside &= (bv & ~av) == 0;
            if (!aInside && !bInside) return false;
        }
        return aOccupied && bOccupied && (aInside || bInside);
    }
}
