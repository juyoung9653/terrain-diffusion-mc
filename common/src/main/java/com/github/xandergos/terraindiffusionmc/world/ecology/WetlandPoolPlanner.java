package com.github.xandergos.terraindiffusionmc.world.ecology;

import java.util.ArrayList;
import java.util.List;

/** Pure, fail-closed plan for a shallow water-table depression. */
final class WetlandPoolPlanner {
    private static final int MIN_AREA = 4;
    private static final int MAX_AREA = 80;

    record Cell(int x, int z, int topY) {
        int waterY() { return topY + 1; }
    }

    interface Surface {
        int topY(int x, int z);
        boolean eligible(int x, int z);
        boolean naturalTop(int x, int y, int z);
        boolean naturalBed(int x, int y, int z);
        boolean clear(int x, int y, int z);
        boolean fluid(int x, int y, int z);
    }

    private WetlandPoolPlanner() { }

    static List<Cell> plan(int chunkX, int chunkZ, int centerX, int centerZ, Surface surface) {
        if (!inside(chunkX, chunkZ, centerX, centerZ)) return List.of();
        int waterY = surface.topY(centerX, centerZ) + 1;
        List<Cell> pool = new ArrayList<>();
        List<int[]> frontier = new ArrayList<>();
        frontier.add(new int[] {centerX, centerZ});
        for (int cursor = 0; cursor < frontier.size(); cursor++) {
            int[] point = frontier.get(cursor);
            int x = point[0], z = point[1];
            if (!inside(chunkX, chunkZ, x, z)) return List.of();
            int top = surface.topY(x, z);
            if (top >= waterY) continue;
            // A fill is one block deep over its existing bed. A lower connected
            // column would need an artificial stepped/deep excavation.
            if (top != waterY - 1 || !basinCell(surface, x, top, z)) return List.of();
            pool.add(new Cell(x, z, top));
            if (pool.size() > MAX_AREA || x == chunkX || x == chunkX + 15
                    || z == chunkZ || z == chunkZ + 15) return List.of();
            for (int[] direction : CARDINAL) {
                int nextX = x + direction[0], nextZ = z + direction[1];
                if (!containsPoint(frontier, nextX, nextZ)) frontier.add(new int[] {nextX, nextZ});
            }
        }
        if (pool.size() < MIN_AREA) return List.of();

        // Eight-neighbour banks reject a diagonal low hole too: a water source
        // must be surrounded by raised, dry natural ground on every side.
        for (Cell cell : pool) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if ((dx == 0 && dz == 0) || contains(pool, cell.x + dx, cell.z + dz)) continue;
            int x = cell.x + dx, z = cell.z + dz;
            if (!inside(chunkX, chunkZ, x, z)) return List.of();
            int top = surface.topY(x, z);
            if (top < waterY || surface.fluid(x, top, z) || surface.fluid(x, waterY, z)
                    || !surface.naturalTop(x, top, z) || !surface.naturalBed(x, waterY, z)
                    || !surface.clear(x, top + 1, z)) return List.of();
        }
        return List.copyOf(pool);
    }

    private static final int[][] CARDINAL = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private static boolean basinCell(Surface surface, int x, int top, int z) {
        return surface.eligible(x, z) && !surface.fluid(x, top, z)
                && !surface.fluid(x, top + 1, z) && surface.naturalTop(x, top, z)
                && surface.naturalBed(x, top, z) && surface.clear(x, top + 1, z)
                && surface.clear(x, top + 2, z);
    }

    private static boolean inside(int chunkX, int chunkZ, int x, int z) {
        return x >= chunkX && x < chunkX + 16 && z >= chunkZ && z < chunkZ + 16;
    }

    private static boolean contains(List<Cell> cells, int x, int z) {
        for (Cell cell : cells) if (cell.x == x && cell.z == z) return true;
        return false;
    }

    private static boolean containsPoint(List<int[]> points, int x, int z) {
        for (int[] point : points) if (point[0] == x && point[1] == z) return true;
        return false;
    }
}
