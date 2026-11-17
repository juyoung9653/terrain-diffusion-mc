package com.github.xandergos.terraindiffusionmc.world.ecology;

import java.util.ArrayList;
import java.util.List;

/** Pure, fail-closed plan for a shallow water-table depression. */
final class WetlandPoolPlanner {
    record Cell(int x, int z, int topY) { }

    interface Surface {
        int topY(int x, int z);
        boolean eligible(int x, int z);
        boolean naturalTop(int x, int y, int z);
        boolean naturalBed(int x, int y, int z);
        boolean clear(int x, int y, int z);
        boolean fluid(int x, int y, int z);
    }

    private WetlandPoolPlanner() { }

    static List<Cell> plan(int chunkX, int chunkZ, int centerX, int centerZ, int radius,
                           long shape, Surface surface) {
        List<int[]> footprint = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int distance = dx * dx + dz * dz;
                int bit = (dx + radius) * (2 * radius + 1) + dz + radius;
                if (distance <= radius * radius
                        && (distance < radius * radius - 1 || ((shape >>> bit) & 1L) != 0)) {
                    footprint.add(new int[] {centerX + dx, centerZ + dz});
                }
            }
        }
        if (footprint.size() < 20) return List.of();

        int waterY = Integer.MAX_VALUE;
        for (int[] point : footprint) {
            if (!inside(chunkX, chunkZ, point[0], point[1])) return List.of();
            waterY = Math.min(waterY, surface.topY(point[0], point[1]));
        }

        List<Cell> pool = new ArrayList<>(footprint.size());
        for (int[] point : footprint) {
            int top = surface.topY(point[0], point[1]);
            if (top < waterY || top > waterY + 1 || !surface.eligible(point[0], point[1])
                    || surface.fluid(point[0], top, point[1])
                    || !surface.naturalTop(point[0], top, point[1])
                    || !surface.naturalTop(point[0], waterY, point[1])
                    || !surface.naturalBed(point[0], waterY - 1, point[1])
                    || !surface.clear(point[0], top + 1, point[1])
                    || !surface.clear(point[0], top + 2, point[1])) return List.of();
            pool.add(new Cell(point[0], point[1], top));
        }

        for (Cell cell : pool) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx == 0 && dz == 0) || contains(pool, cell.x + dx, cell.z + dz)) continue;
                    int x = cell.x + dx, z = cell.z + dz;
                    if (!inside(chunkX, chunkZ, x, z)) return List.of();
                    int top = surface.topY(x, z);
                    if (top < waterY || top > waterY + 1 || surface.fluid(x, top, z)
                            || surface.fluid(x, waterY, z) || !surface.naturalTop(x, top, z)
                            || !surface.naturalTop(x, waterY, z) || !surface.clear(x, top + 1, z))
                        return List.of();
                }
            }
        }
        return List.copyOf(pool);
    }

    private static boolean inside(int chunkX, int chunkZ, int x, int z) {
        return x >= chunkX && x < chunkX + 16 && z >= chunkZ && z < chunkZ + 16;
    }

    private static boolean contains(List<Cell> cells, int x, int z) {
        for (Cell cell : cells) if (cell.x == x && cell.z == z) return true;
        return false;
    }
}
