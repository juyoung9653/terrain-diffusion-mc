package com.github.xandergos.terraindiffusionmc.pipeline;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Bounded decoded snapshots for losslessly packed ecology planes. */
final class EcologyDataCache {
    private static final int BYTES_PER_VALUE = 2 * Short.BYTES + 5 * Float.BYTES;
    private final int maxHot;
    private final LinkedHashMap<EcologyData, EcologyData.Snapshot> hot = new LinkedHashMap<>(16, .75f, true);
    private final Set<EcologyData> known = Collections.newSetFromMap(new IdentityHashMap<>());

    EcologyDataCache(int maxHot) { this.maxHot = Math.max(0, maxHot); }

    void retain(EcologyData data) {
        synchronized (this) {
            EcologyData.Snapshot snapshot = data.currentSnapshot();
            if (snapshot == null) snapshot = data.inflate();
            if (data.packed() == null) data.pack(snapshot, pack(snapshot, data.values()));
            data.install(this, snapshot);
            known.add(data); hot.put(data, snapshot); trim();
        }
    }

    EcologyData.Snapshot acquire(EcologyData data) {
        EcologyData.Snapshot direct = data.currentSnapshot();
        if (direct != null) return direct;
        synchronized (this) {
            // A caller may have read this cache reference just before clear().
            if (!data.belongsTo(this)) return data.restoreDetached();
            EcologyData.Snapshot cached = hot.get(data);
            if (cached == null) {
                cached = data.inflate();
                data.install(this, cached);
                hot.put(data, cached); trim();
            } else {
                data.install(this, cached);
            }
            return cached;
        }
    }

    void touch(EcologyData data, EcologyData.Snapshot snapshot) {
        synchronized (this) {
            if (data.belongsTo(this) && hot.get(data) == snapshot) {
                // get() above refreshed access order; no additional retention is needed.
            }
        }
    }

    void clear() {
        synchronized (this) {
            for (EcologyData data : known) {
                EcologyData.Snapshot snapshot = data.currentSnapshot();
                if (snapshot != null) data.evict(snapshot);
                data.detach(this);
            }
            hot.clear(); known.clear();
        }
    }

    void forget(EcologyData data) {
        if (data == null) return;
        synchronized (this) {
            EcologyData.Snapshot snapshot = hot.remove(data);
            if (snapshot != null) data.evict(snapshot);
            known.remove(data); data.detach(this);
        }
    }

    int hotEntries() { synchronized (this) { return hot.size(); } }

    private void trim() {
        while (hot.size() > maxHot) {
            var iterator = hot.entrySet().iterator();
            var eldest = iterator.next(); iterator.remove();
            eldest.getKey().evict(eldest.getValue());
        }
    }

    static byte[] pack(EcologyData.Snapshot fields, int values) {
        int bytes = Math.multiplyExact(values, BYTES_PER_VALUE);
        ByteBuffer raw = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        put(raw, fields.biomeIds); put(raw, fields.terrainBiomeIds);
        put(raw, fields.cover); put(raw, fields.temperature); put(raw, fields.moisture);
        put(raw, fields.slope); put(raw, fields.snowRetention);
        Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
        try {
            deflater.setInput(raw.array()); deflater.finish();
            byte[] compressed = new byte[bytes + 64]; int length = deflater.deflate(compressed);
            if (deflater.finished() && length < bytes) {
                byte[] result = new byte[length + 1]; result[0] = 1;
                System.arraycopy(compressed, 0, result, 1, length); return result;
            }
            byte[] result = new byte[bytes + 1]; result[0] = 0;
            System.arraycopy(raw.array(), 0, result, 1, bytes); return result;
        } finally { deflater.end(); }
    }

    static EcologyData.Snapshot unpack(byte[] packed, int values) {
        int bytes = Math.multiplyExact(values, BYTES_PER_VALUE); byte[] raw;
        if (packed[0] == 0) {
            if (packed.length != bytes + 1) throw new IllegalStateException("Invalid packed ecology data");
            raw = new byte[bytes]; System.arraycopy(packed, 1, raw, 0, bytes);
        } else if (packed[0] == 1) {
            if (packed.length < 2) throw new IllegalStateException("Invalid packed ecology data");
            raw = new byte[bytes]; Inflater inflater = new Inflater(true);
            try {
                inflater.setInput(packed, 1, packed.length - 1); int length = inflater.inflate(raw);
                if (length != bytes || !inflater.finished()) throw new IllegalStateException("Invalid packed ecology data");
            } catch (java.util.zip.DataFormatException invalid) {
                throw new IllegalStateException("Invalid packed ecology data", invalid);
            } finally { inflater.end(); }
        } else throw new IllegalStateException("Invalid packed ecology data");
        ByteBuffer source = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        short[] biomeIds = getShorts(source, values), terrainBiomeIds = getShorts(source, values);
        return new EcologyData.Snapshot(biomeIds, terrainBiomeIds, getFloats(source, values), getFloats(source, values),
                getFloats(source, values), getFloats(source, values), getFloats(source, values));
    }

    private static void put(ByteBuffer out, short[] values) { for (short value : values) out.putShort(value); }
    private static void put(ByteBuffer out, float[] values) { for (float value : values) out.putInt(Float.floatToRawIntBits(value)); }
    private static short[] getShorts(ByteBuffer in, int length) { short[] values = new short[length]; for (int i = 0; i < length; i++) values[i] = in.getShort(); return values; }
    private static float[] getFloats(ByteBuffer in, int length) { float[] values = new float[length]; for (int i = 0; i < length; i++) values[i] = Float.intBitsToFloat(in.getInt()); return values; }
}
