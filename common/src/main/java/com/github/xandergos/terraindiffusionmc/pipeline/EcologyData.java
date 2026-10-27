package com.github.xandergos.terraindiffusionmc.pipeline;

/** Immutable, tile-owned ecology fields alongside the unchanged TD heightmap. */
public final class EcologyData {
    public final int width, height;
    private final int values;
    /** A local snapshot stays valid while a caller reads it, even after LRU eviction. */
    private volatile Snapshot hot;
    private volatile byte[] packed;
    private volatile EcologyDataCache owner;
    private int sampledTouches;

    public EcologyData(int width, int height, short[] biomeIds, short[] terrainBiomeIds,
                       float[] cover, float[] temperature, float[] moisture, float[] slope, float[] snowRetention) {
        this.width = width; this.height = height; this.values = Math.multiplyExact(width, height);
        this.hot = new Snapshot(biomeIds, terrainBiomeIds, cover, temperature, moisture, slope, snowRetention);
    }
    public int biomeId(int x, int z) { return snapshot().biomeIds[z * width + x]; }
    public short terrainBiomeId(int x, int z) { return snapshot().terrainBiomeIds[z * width + x]; }
    public float snowRetention(int x, int z) { return snapshot().snowRetention[z * width + x]; }
    public EcologyClassifier.Sample sample(int x, int z) {
        Snapshot fields = snapshot(); int i = z * width + x;
        return new EcologyClassifier.Sample(fields.biomeIds[i], fields.cover[i], fields.temperature[i],
                fields.moisture[i], fields.slope[i]);
    }
    EcologyData crop(int x, int z, int w, int h) {
        Snapshot source = snapshot(); int size = w * h;
        short[] ids = new short[size], terrain = new short[size];
        float[] trees = new float[w * h], temp = new float[w * h], moist = new float[w * h], slopes = new float[w * h];
        float[] retention = new float[w * h];
        for (int r = 0; r < h; r++) {
            int from = (z + r) * width + x, to = r * w;
            System.arraycopy(source.biomeIds, from, ids, to, w);
            System.arraycopy(source.terrainBiomeIds, from, terrain, to, w);
            System.arraycopy(source.cover, from, trees, to, w);
            System.arraycopy(source.temperature, from, temp, to, w);
            System.arraycopy(source.moisture, from, moist, to, w);
            System.arraycopy(source.slope, from, slopes, to, w);
            System.arraycopy(source.snowRetention, from, retention, to, w);
        }
        return new EcologyData(w, h, ids, terrain, trees, temp, moist, slopes, retention);
    }

    void retainCold(EcologyDataCache cache) { cache.retain(this); }
    void detach(EcologyDataCache cache) { if (owner == cache) owner = null; }
    boolean belongsTo(EcologyDataCache cache) { return owner == cache; }
    Snapshot currentSnapshot() { return hot; }
    byte[] packed() { return packed; }
    int values() { return values; }
    synchronized void pack(Snapshot snapshot, byte[] compressed) { if (hot == snapshot) packed = compressed; }
    void evict(Snapshot snapshot) { if (hot == snapshot) hot = null; }
    synchronized Snapshot inflate() {
        Snapshot current = hot;
        if (current != null) return current;
        if (packed == null) throw new IllegalStateException("Ecology data has no retained representation");
        return EcologyDataCache.unpack(packed, values);
    }
    synchronized Snapshot restoreDetached() {
        Snapshot current = hot;
        if (current != null) return current;
        if (owner != null) return null;
        hot = EcologyDataCache.unpack(packed, values);
        return hot;
    }
    void install(EcologyDataCache cache, Snapshot snapshot) { owner = cache; hot = snapshot; }

    private Snapshot snapshot() {
        Snapshot current = hot;
        if (current != null) {
            EcologyDataCache cache = owner;
            if (cache != null && (++sampledTouches & 63) == 0) cache.touch(this, current);
            return current;
        }
        EcologyDataCache cache = owner;
        if (cache != null) return cache.acquire(this);
        return restoreDetached();
    }

    static final class Snapshot {
        final short[] biomeIds, terrainBiomeIds;
        final float[] cover, temperature, moisture, slope, snowRetention;
        Snapshot(short[] biomeIds, short[] terrainBiomeIds, float[] cover, float[] temperature,
                 float[] moisture, float[] slope, float[] snowRetention) {
            this.biomeIds = biomeIds; this.terrainBiomeIds = terrainBiomeIds;
            this.cover = cover; this.temperature = temperature; this.moisture = moisture;
            this.slope = slope; this.snowRetention = snowRetention;
        }
    }
}
