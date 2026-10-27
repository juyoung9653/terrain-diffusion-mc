package com.github.xandergos.terraindiffusionmc.pipeline;

/** Standalone parity and ownership checks for cold ecology tiles. */
public final class EcologyDataCacheTest {
    public static void main(String[] args) {
        EcologyData first = tile(64, 3), second = tile(64, 7), third = tile(64, 11);
        var expected = first.sample(19, 37);
        int snowBits = Float.floatToRawIntBits(first.snowRetention(19, 37));
        EcologyDataCache cache = new EcologyDataCache(1);
        first.retainCold(cache);
        check(first.packed() != null, "cold tile retains packed bytes");
        second.retainCold(cache);
        check(first.currentSnapshot() == null, "LRU drops raw first tile");
        check(first.sample(19, 37).equals(expected), "cold sample is bit-exact");
        check(Float.floatToRawIntBits(first.snowRetention(19, 37)) == snowBits, "cold retention is bit-exact");
        third.retainCold(cache);
        check(cache.hotEntries() == 1, "decoded ecology cache stays bounded");
        EcologyData crop = first.crop(9, 13, 17, 21);
        check(crop.sample(10, 12).equals(first.sample(19, 25)), "cold crop parity");
        cache.clear();
        check(first.sample(19, 37).equals(expected), "external tile remains readable after owner clear");
        EcologyData.Snapshot detached = first.currentSnapshot();
        for (int i = 0; i < 128; i++) check(first.sample(i & 63, (i * 7) & 63) != null, "detached read");
        check(first.currentSnapshot() == detached, "detached reads retain one local decoded snapshot");
        zeroHotAndConcurrentEviction();
        benchmarkTile();
        System.out.println("PASS cold ecology: lossless round-trip, bounded decoded LRU, crop, clear, zero-hot, concurrent eviction");
    }

    private static void zeroHotAndConcurrentEviction() {
        EcologyData zero = tile(64, 21); EcologyDataCache none = new EcologyDataCache(0);
        zero.retainCold(none); check(zero.currentSnapshot() == null, "zero-hot retention drops raw snapshot");
        check(zero.sample(3, 5).biomeId() == zero.biomeId(3, 5), "zero-hot read restores data");
        EcologyData shared = tile(64, 27); EcologyDataCache cache = new EcologyDataCache(1);
        shared.retainCold(cache);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread reader = new Thread(() -> {
            try {
                for (int i = 0; i < 20_000; i++) {
                    var sample = shared.sample(i & 63, (i * 13) & 63);
                    if (sample.biomeId() < 0) throw new AssertionError("corrupt concurrent ecology sample");
                }
            } catch (Throwable thrown) { failure.set(thrown); }
        });
        reader.start();
        for (int i = 0; i < 80; i++) tile(64, 40 + i).retainCold(cache);
        try { reader.join(); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        check(!reader.isAlive(), "concurrent reader completes during eviction");
        if (failure.get() != null) throw new AssertionError("concurrent ecology read", failure.get());
    }

    private static void benchmarkTile() {
        EcologyData raw = tile(256, 31), cold = tile(256, 31), filler = tile(256, 32);
        final int rawBytes = 256 * 256 * (2 * Short.BYTES + 5 * Float.BYTES), samples = 100_000;
        long begin = System.nanoTime(); EcologyDataCache cache = new EcologyDataCache(1); cold.retainCold(cache);
        long packNanos = System.nanoTime() - begin; filler.retainCold(cache);
        check(cold.currentSnapshot() == null, "benchmark tile is cold");
        long rawBegin = System.nanoTime(); long sink = sampleMany(raw, samples); long rawNanos = System.nanoTime() - rawBegin;
        long coldBegin = System.nanoTime(); sink += sampleMany(cold, samples); long coldNanos = System.nanoTime() - coldBegin;
        check(sink != Long.MIN_VALUE, "benchmark sink");
        int packedBytes = cold.packed().length;
        System.out.printf(java.util.Locale.ROOT,
                "BENCH cold ecology tile raw=%d packed=%d ratio=%.3f pack=%.2fms raw=%.1fns/sample hot=%.1fns/sample%n",
                rawBytes, packedBytes, (double) packedBytes / rawBytes, packNanos / 1_000_000.0,
                (double) rawNanos / samples, (double) coldNanos / samples);
    }

    private static long sampleMany(EcologyData data, int count) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            int x = (i * 17) & 255, z = (i * 29) & 255;
            var sample = data.sample(x, z); sum += sample.biomeId() + Float.floatToRawIntBits(sample.moisture());
        }
        return sum;
    }

    private static EcologyData tile(int side, int salt) {
        int n = side * side;
        short[] biome = new short[n], terrain = new short[n];
        float[] cover = new float[n], temperature = new float[n], moisture = new float[n], slope = new float[n], snow = new float[n];
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            int i = z * side + x;
            biome[i] = (short) ((x / 8 + z / 8 + salt) % 19); terrain[i] = (short) ((x + z + salt) % 29);
            cover[i] = ((x + salt) % 11) / 10f; temperature[i] = 8.25f + z * .03125f;
            moisture[i] = ((z + salt) % 17) / 16f; slope[i] = (x * 3 + z * 5 + salt) * .001f;
            snow[i] = (x == 19 && z == 37) ? Float.intBitsToFloat(0x7fc01234) : (x + z) * .01f;
        }
        return new EcologyData(side, side, biome, terrain, cover, temperature, moisture, slope, snow);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
