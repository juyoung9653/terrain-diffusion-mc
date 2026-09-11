package com.github.xandergos.terraindiffusionmc.world.river;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Plans drainage over one finite sampled terrain window.  Boundary cells are
 * outlets to terrain outside that window; they are deliberately not treated
 * as ocean.  D8 routing permits diagonal crossings at corners, so callers
 * needing strict raster barriers should post-process those crossings.
 */
public final class DrainagePlanner {
    private static final int NONE = -1;
    private static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] DZ = {-1, -1, -1, 0, 0, 1, 1, 1};

    private DrainagePlanner() {
    }

    public enum Terminal { OCEAN, EXTERNAL, CLOSED, NONE }

    public record Settings(double seaLevel, double minCatchmentArea,
                           double runoffPerCell, double maxFillDepth) {
        public Settings {
            requireFinite("seaLevel", seaLevel);
            requirePositive("minCatchmentArea", minCatchmentArea);
            requirePositive("runoffPerCell", runoffPerCell);
            requireNonNegative("maxFillDepth", maxFillDepth);
        }

        private static void requireFinite(String name, double value) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
        }
        private static void requirePositive(String name, double value) {
            if (!Double.isFinite(value) || value <= 0.0) {
                throw new IllegalArgumentException(name + " must be finite and positive");
            }
        }
        private static void requireNonNegative(String name, double value) {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException(name + " must be finite and non-negative");
            }
        }
    }

    public record Diagnostics(int oceanRoots, int externalRoots, int closedRoots,
                              int approvedCells, int channelCells) {
    }

    public static final class Plan {
        private final int originX, originZ, spacing, width, height;
        private final double[] ground, drainageLevel, accumulation, renderedAccumulation;
        private final int[] downstream;
        private final Terminal[] terminal;
        private final boolean[] channel;
        private final Diagnostics diagnostics;

        private Plan(int originX, int originZ, int spacing, int width, int height,
                     double[] ground, double[] drainageLevel, int[] downstream,
                     double[] accumulation, double[] renderedAccumulation, Terminal[] terminal, boolean[] channel,
                     Diagnostics diagnostics) {
            this.originX = originX;
            this.originZ = originZ;
            this.spacing = spacing;
            this.width = width;
            this.height = height;
            this.ground = ground.clone();
            this.drainageLevel = drainageLevel.clone();
            this.downstream = downstream.clone();
            this.accumulation = accumulation.clone();
            this.renderedAccumulation = renderedAccumulation.clone();
            this.terminal = terminal.clone();
            this.channel = channel.clone();
            this.diagnostics = diagnostics;
        }

        public int originX() { return originX; }
        public int originZ() { return originZ; }
        public int spacing() { return spacing; }
        public int width() { return width; }
        public int height() { return height; }
        public double ground(int index) { return ground[checked(index)]; }
        public double drainageLevel(int index) { return drainageLevel[checked(index)]; }
        public int downstream(int index) { return downstream[checked(index)]; }
        public double accumulation(int index) { return accumulation[checked(index)]; }
        /** Catchment area suitable for rendering: only approved ocean-connected routes contribute. */
        public double renderedAccumulation(int index) { return renderedAccumulation[checked(index)]; }
        public Terminal terminal(int index) { return terminal[checked(index)]; }
        public boolean channel(int index) { return channel[checked(index)]; }
        public Diagnostics diagnostics() { return diagnostics; }

        private int checked(int index) {
            if (index < 0 || index >= ground.length) throw new IndexOutOfBoundsException(index);
            return index;
        }
    }

    /**
     * Builds an immutable drainage plan. Ground and runoff are row-major
     * ({@code x + z * width}); runoff is per sampled cell before its area is
     * scaled by {@code spacing * spacing}.  This method never edits either input.
     */
    public static Plan plan(int originX, int originZ, int spacing, int width, int height,
                            double[] ground, double[] runoff, Settings settings) {
        return plan(originX, originZ, spacing, width, height, ground, runoff, settings, null);
    }

    /**
     * Builds a plan with explicit ocean eligibility.  A true mask entry is an
     * actual-ocean root only when its ground is at or below sea level; false
     * entries let below-sea inland terrain drain through the finite window.
     * Passing {@code null} preserves the height-only synthetic-terrain mode.
     */
    public static Plan plan(int originX, int originZ, int spacing, int width, int height,
                            double[] ground, double[] runoff, Settings settings, boolean[] oceanMask) {
        if (spacing <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("spacing, width, and height must be positive");
        }
        int count;
        try { count = Math.multiplyExact(width, height); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("grid is too large", e); }
        if (ground == null || ground.length != count) throw new IllegalArgumentException("ground length must equal width * height");
        if (runoff != null && runoff.length != count) throw new IllegalArgumentException("runoff length must equal width * height");
        if (oceanMask != null && oceanMask.length != count) throw new IllegalArgumentException("oceanMask length must equal width * height");
        if (settings == null) throw new NullPointerException("settings");
        double[] raw = ground.clone();
        double[] cellRunoff = runoff == null ? null : runoff.clone();
        boolean[] actualOcean = oceanMask == null ? null : oceanMask.clone();
        for (int i = 0; i < count; i++) {
            if (!Double.isFinite(raw[i])) throw new IllegalArgumentException("ground must be finite");
            if (cellRunoff != null && (!Double.isFinite(cellRunoff[i]) || cellRunoff[i] < 0.0)) {
                throw new IllegalArgumentException("runoff must be finite and non-negative");
            }
        }

        double[] level = new double[count];
        int[] down = new int[count];
        Arrays.fill(down, NONE);
        boolean[] visited = new boolean[count];
        int[] order = new int[count];
        PriorityQueue<Entry> queue = new PriorityQueue<>((a, b) -> {
            int byLevel = Double.compare(a.level, b.level);
            return byLevel != 0 ? byLevel : Integer.compare(a.index, b.index);
        });

        // Actual ocean is a hydrologic root even away from the preview edge.
        for (int i = 0; i < count; i++) {
            if (raw[i] <= settings.seaLevel() && (actualOcean == null || actualOcean[i])) {
                seed(i, raw[i], visited, level, queue);
            }
        }
        // Non-ocean preview edges drain out of the finite domain.
        for (int z = 0; z < height; z++) for (int x = 0; x < width; x++) {
            if ((x == 0 || z == 0 || x == width - 1 || z == height - 1)) {
                int i = x + z * width;
                if (!visited[i]) seed(i, raw[i], visited, level, queue);
            }
        }

        int ranked = 0;
        while (!queue.isEmpty()) {
            Entry current = queue.remove();
            int i = current.index;
            order[ranked++] = i;
            int x = i % width, z = i / width;
            for (int d = 0; d < DX.length; d++) {
                int nx = x + DX[d], nz = z + DZ[d];
                if (nx < 0 || nz < 0 || nx >= width || nz >= height) continue;
                int next = nx + nz * width;
                if (visited[next]) continue;
                visited[next] = true;
                level[next] = Math.max(raw[next], current.level);
                down[next] = i;
                queue.add(new Entry(next, level[next]));
            }
        }

        Terminal[] terminal = new Terminal[count];
        boolean[] approved = new boolean[count];
        int oceanRoots = 0, externalRoots = 0, closedRoots = 0, approvedCells = 0;
        for (int position = 0; position < count; position++) {
            int i = order[position];
            if (down[i] == NONE) {
                terminal[i] = raw[i] <= settings.seaLevel() && (actualOcean == null || actualOcean[i])
                        ? Terminal.OCEAN : Terminal.EXTERNAL;
                if (terminal[i] == Terminal.OCEAN) oceanRoots++; else externalRoots++;
            } else {
                terminal[i] = terminal[down[i]];
            }
            approved[i] = level[i] - raw[i] <= settings.maxFillDepth()
                    && (down[i] == NONE || approved[down[i]]);
            if (approved[i]) approvedCells++;
        }

        double areaScale = (double) spacing * spacing;
        double[] accumulation = new double[count];
        for (int i = 0; i < count; i++) accumulation[i] = (cellRunoff == null ? settings.runoffPerCell() : cellRunoff[i]) * areaScale;
        for (int position = count - 1; position >= 0; position--) {
            int i = order[position];
            if (down[i] != NONE) accumulation[down[i]] += accumulation[i];
        }
        // Keep this separate from physical accumulation: invalid filled basins
        // must not make a downstream rendered channel wider.
        double[] renderedAccumulation = new double[count];
        for (int i = 0; i < count; i++) {
            if (approved[i] && terminal[i] == Terminal.OCEAN) {
                renderedAccumulation[i] = (cellRunoff == null ? settings.runoffPerCell() : cellRunoff[i]) * areaScale;
            }
        }
        for (int position = count - 1; position >= 0; position--) {
            int i = order[position];
            if (down[i] != NONE) renderedAccumulation[down[i]] += renderedAccumulation[i];
        }
        boolean[] channel = new boolean[count];
        int channelCells = 0;
        for (int i = 0; i < count; i++) {
            // Roots are actual ocean and never rendered as land rivers.  A
            // non-root coastal land cell may be at or below sea level and is
            // still a valid connector when its explicit biome mask says land.
            channel[i] = down[i] != NONE && approved[i]
                    && terminal[i] == Terminal.OCEAN && renderedAccumulation[i] >= settings.minCatchmentArea();
            if (channel[i]) channelCells++;
        }
        return new Plan(originX, originZ, spacing, width, height, raw, level, down, accumulation, renderedAccumulation,
                terminal, channel, new Diagnostics(oceanRoots, externalRoots, closedRoots, approvedCells, channelCells));
    }

    private static void seed(int index, double value, boolean[] visited, double[] level, PriorityQueue<Entry> queue) {
        visited[index] = true;
        level[index] = value;
        queue.add(new Entry(index, value));
    }

    private record Entry(int index, double level) { }
}
