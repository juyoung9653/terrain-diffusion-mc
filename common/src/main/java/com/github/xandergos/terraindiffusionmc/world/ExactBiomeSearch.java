package com.github.xandergos.terraindiffusionmc.world;

import java.util.function.Predicate;

/** One request's exact biome reads. Retains at most one tile, including a null fallback tile. */
final class ExactBiomeSearch<T, B> {
    @FunctionalInterface
    interface TileLoader<T> {
        T load(int blockStartX, int blockStartZ, int size);
    }

    @FunctionalInterface
    interface BiomeReader<T, B> {
        B read(int blockX, int blockY, int blockZ, int blockStartX, int blockStartZ, T tile);
    }

    record Match<B>(int blockY, B biome) { }

    private final int tileSize;
    private final int tileShift;
    private final TileLoader<T> loader;
    private final BiomeReader<T, B> reader;
    private final boolean verticallyInvariant;
    private boolean loaded;
    private int startX;
    private int startZ;
    private T tile;

    ExactBiomeSearch(int tileSize, TileLoader<T> loader, BiomeReader<T, B> reader,
                     boolean verticallyInvariant) {
        if (tileSize <= 0 || Integer.bitCount(tileSize) != 1)
            throw new IllegalArgumentException("Tile size must be a positive power of two");
        this.tileSize = tileSize;
        this.tileShift = Integer.numberOfTrailingZeros(tileSize);
        this.loader = loader;
        this.reader = reader;
        this.verticallyInvariant = verticallyInvariant;
    }

    Match<B> findColumn(int blockX, int blockZ, int[] heights, Predicate<B> accepted) {
        // Match getNoiseBiome's block -> quart -> block rounding, including negatives.
        int x = (blockX >> 2) << 2;
        int z = (blockZ >> 2) << 2;
        int count = verticallyInvariant ? Math.min(1, heights.length) : heights.length;
        for (int i = 0; i < count; i++) {
            B biome = read(x, (heights[i] >> 2) << 2, z);
            if (accepted.test(biome)) return new Match<>(heights[i], biome);
        }
        return null;
    }

    private B read(int x, int y, int z) {
        int nextX = (x >> tileShift) << tileShift;
        int nextZ = (z >> tileShift) << tileShift;
        if (!loaded || nextX != startX || nextZ != startZ) {
            T nextTile = loader.load(nextX, nextZ, tileSize);
            startX = nextX;
            startZ = nextZ;
            tile = nextTile;
            loaded = true;
        }
        return reader.read(x, y, z, startX, startZ, tile);
    }
}
