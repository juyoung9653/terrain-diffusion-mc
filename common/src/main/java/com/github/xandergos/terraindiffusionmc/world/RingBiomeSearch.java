package com.github.xandergos.terraindiffusionmc.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Tile-wise preferred-biome relocation. Does not change the actual biome generator. */
public final class RingBiomeSearch {
    private RingBiomeSearch() { }

    public record Tile(int blockX, int blockZ, int minQuartX, int minQuartZ,
                       int maxQuartX, int maxQuartZ) { }
    public record Result<B>(int blockX, int blockZ, B biome, int loadedTiles, int testedSamples) { }
    @FunctionalInterface
    public interface Sampler<B> { B sample(int blockX, int blockZ); }

    /** Same square, four-block sample grid as the vanilla ring search, including negative coordinates. */
    public static List<Tile> tiles(int x, int z, int radius, int tileSize) {
        if (radius < 0 || tileSize <= 0 || (tileSize & (tileSize - 1)) != 0)
            throw new IllegalArgumentException("Expected nonnegative radius and power-of-two tile size");
        int qx = Math.floorDiv(x, 4), qz = Math.floorDiv(z, 4), qr = radius / 4;
        int minX = qx - qr, maxX = qx + qr, minZ = qz - qr, maxZ = qz + qr;
        List<Tile> result = new ArrayList<>();
        // Step in quart cells so tile sizes smaller than four do not create empty tiles.
        for (int row = minZ; row <= maxZ;) {
            int bz = Math.floorDiv(row * 4, tileSize) * tileSize;
            int lastZ = Math.min(maxZ, Math.floorDiv(bz + tileSize - 1, 4));
            for (int col = minX; col <= maxX;) {
                int bx = Math.floorDiv(col * 4, tileSize) * tileSize;
                int lastX = Math.min(maxX, Math.floorDiv(bx + tileSize - 1, 4));
                result.add(new Tile(bx, bz, col, row, lastX, lastZ));
                col = lastX + 1;
            }
            row = lastZ + 1;
        }
        return result;
    }

    /**
     * Coarse scores only order work: no tile is discarded by an approximate prediction.
     * Load each tile at most once, inspect every sample in it, and reservoir-sample its
     * exact matches. Stop at the first matching tile; exhaust the search before fallback.
     */
    public static <B> Result<B> find(List<Tile> input, ToIntFunction<Tile> score,
                                    Function<Tile, Sampler<B>> load, Predicate<B> preferred,
                                    IntUnaryOperator nextInt) {
        // Resolve scores once, before sorting or exact work.
        record Ranked(Tile tile, int score, double priority) { }
        List<Ranked> ranked = new ArrayList<>();
        for (Tile tile : input) {
            int samples = (tile.maxQuartX() - tile.minQuartX() + 1) * (tile.maxQuartZ() - tile.minQuartZ() + 1);
            double u = (nextInt.applyAsInt(1 << 24) + 1.0) / ((1 << 24) + 1.0);
            // Area-weighted random order avoids giving tiny tile-edge slivers the
            // same chance as a large search portion when coarse scores tie.
            ranked.add(new Ranked(tile, score.applyAsInt(tile), -Math.log(u) / samples));
        }
        ranked.sort(Comparator.comparingInt(Ranked::score).reversed().thenComparingDouble(Ranked::priority));
        int loaded = 0, tested = 0;
        for (Ranked entry : ranked) {
            Tile tile = entry.tile();
            Sampler<B> sampler = load.apply(tile);
            loaded++;
            B selected = null;
            int selectedX = 0, selectedZ = 0, matches = 0;
            for (int qz = tile.minQuartZ(); qz <= tile.maxQuartZ(); qz++) {
                for (int qx = tile.minQuartX(); qx <= tile.maxQuartX(); qx++) {
                    int bx = qx * 4, bz = qz * 4;
                    B biome = sampler.sample(bx, bz);
                    tested++;
                    if (preferred.test(biome) && (matches++ == 0 || nextInt.applyAsInt(matches) == 0)) {
                        selected = biome;
                        selectedX = bx;
                        selectedZ = bz;
                    }
                }
            }
            if (selected != null) return new Result<>(selectedX, selectedZ, selected, loaded, tested);
        }
        return new Result<>(0, 0, null, loaded, tested);
    }
}
