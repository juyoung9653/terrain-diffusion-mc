package com.github.xandergos.terraindiffusionmc.compat.streams;

import java.lang.reflect.Method;

/** V9 lake safety sampled in world coordinates; never consults or loads Minecraft chunks. */
final class StreamsBankLakeMask {
    private static final int HALO = 8;
    // The dry-bank hook calls nearColumn per block; only the method is reusable.
    private static final ClassValue<Method> LAKE_AT = new ClassValue<>() {
        @Override protected Method computeValue(Class<?> type) {
            try {
                return type.getMethod("lakeAt", int.class, int.class);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unsupported Streams lake lookup", failure);
            }
        }
    };
    private final int minX, minZ;
    private final boolean[][] lakes = new boolean[32][32];
    private boolean anyLake;
    private int[][] wettingLevels;

    StreamsBankLakeMask(Object engine, int minX, int minZ) throws ReflectiveOperationException {
        this.minX = minX - HALO;
        this.minZ = minZ - HALO;
        Object config = engine.getClass().getMethod("config").invoke(engine);
        int spacing = ((Number) config.getClass().getMethod("gridSpacing").invoke(config)).intValue();
        // Broad phase is only an optimization. Native cells extend half a spacing
        // beyond their centre bounds, so expand by that plus our read-only halo.
        if (!(Boolean) engine.getClass().getMethod("lakeNearChunk", int.class, int.class, int.class)
                .invoke(engine, minX, minZ, HALO + (spacing + 1) / 2)) return;
        Method lakeAt = LAKE_AT.get(engine.getClass());
        for (int x = 0; x < lakes.length; x++) for (int z = 0; z < lakes[x].length; z++) {
            lakes[x][z] = lakeAt.invoke(engine, this.minX + x, this.minZ + z) != null;
            anyLake |= lakes[x][z];
        }
    }

    boolean hasLake() { return anyLake; }

    void prepareWetting(Object engine) throws ReflectiveOperationException {
        if (wettingLevels != null) return;
        wettingLevels = new int[32][32];
        Method wetting = engine.getClass().getMethod("lakeWetting", int.class, int.class, int.class);
        Method level = null;
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            Object lake = wetting.invoke(engine, minX + x, minZ + z, 2);
            if (lake == null) wettingLevels[x][z] = Integer.MIN_VALUE;
            else {
                if (level == null) level = lake.getClass().getMethod("level");
                wettingLevels[x][z] = ((Number) level.invoke(lake)).intValue();
            }
        }
    }

    int wettingLevel(int x, int z) { return wettingLevels[x - minX][z - minZ]; }

    record Shore(int water, double distance) { }

    Shore shore(int x, int z) {
        if (wettingLevel(x, z) != Integer.MIN_VALUE) return null;
        int safeWater = Integer.MIN_VALUE;
        double distance = Double.POSITIVE_INFINITY;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            int squared = dx * dx + dz * dz;
            if (squared > 9) continue;
            int water = wettingLevel(x + dx, z + dz);
            if (water != Integer.MIN_VALUE) {
                safeWater = Math.max(safeWater, water);
                distance = Math.min(distance, Math.sqrt(squared));
            }
        }
        return safeWater == Integer.MIN_VALUE ? null : new Shore(safeWater, distance);
    }

    boolean excludes(int x, int z, int radius) {
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (dx * dx + dz * dz <= radius * radius && lakes[x - minX + dx][z - minZ + dz]) return true;
        }
        return false;
    }

    /** Small on-demand buffer for the native dry-write hook, which has no chunk-scoped pass. */
    static boolean nearColumn(Object engine, int x, int z, int radius) throws ReflectiveOperationException {
        Method lakeAt = LAKE_AT.get(engine.getClass());
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (dx * dx + dz * dz <= radius * radius && lakeAt.invoke(engine, x + dx, z + dz) != null)
                return true;
        }
        return false;
    }
}
