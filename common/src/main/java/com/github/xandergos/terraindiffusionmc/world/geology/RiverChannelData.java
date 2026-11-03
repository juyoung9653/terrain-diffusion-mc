package com.github.xandergos.terraindiffusionmc.world.geology;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The generated river columns that actually survived carving in one chunk.
 *
 * <p>This deliberately records no model inputs or inferred channel.  A flow
 * lookup can therefore only affect water which this generator demonstrably
 * placed, including after a save/reload.  Data lives on the chunk instance and
 * is bounded by the number of local columns.</p>
 */
public final class RiverChannelData {
    public static final String NBT_KEY = "terrain_diffusion_river_channels";
    private static final int MAX_ENTRIES = 256;

    private RiverChannelData() { }

    public record Entry(int bed, int water, int roof, double flowX, double flowZ, boolean lake) {
        public boolean isValid() {
            return bed < water && water <= roof
                    && Double.isFinite(flowX) && Double.isFinite(flowZ)
                    && Math.abs(flowX) <= 1.0D && Math.abs(flowZ) <= 1.0D;
        }
    }

    /** Records one successfully carved local column. Invalid columns fail closed. */
    public static void record(ChunkAccess chunk, int localX, int localZ, Entry entry) {
        chunk = unwrap(chunk);
        if (!validPosition(localX, localZ) || !validEntry(chunk, entry)) return;
        Map<Integer, Entry> entries = entries(chunk);
        synchronized (entries) {
            entries.put(key(localX, localZ), entry);
        }
    }

    /** Returns only a column explicitly recorded by the carver, or {@code null}. */
    public static Entry get(ChunkAccess chunk, int localX, int localZ) {
        if (!validPosition(localX, localZ)) return null;
        ChunkAccess actualChunk = unwrap(chunk);
        Map<Integer, Entry> entries = entries(actualChunk);
        synchronized (entries) {
            Entry entry = entries.get(key(localX, localZ));
            return validEntry(actualChunk, entry) ? entry : null;
        }
    }

    /** Copies a proto chunk's successful-column markers when it becomes a full chunk. */
    public static void copy(ChunkAccess source, ChunkAccess target) {
        source = unwrap(source);
        target = unwrap(target);
        if (source == target) return;
        Map<Integer, Entry> sourceEntries = entries(source);
        Map<Integer, Entry> targetEntries = entries(target);
        synchronized (sourceEntries) {
            synchronized (targetEntries) {
                targetEntries.clear();
                targetEntries.putAll(sourceEntries);
            }
        }
    }

    /** Writes this class's compact, bounded payload into the supplied chunk tag. */
    public static void write(ChunkAccess chunk, CompoundTag tag) {
        chunk = unwrap(chunk);
        Map<Integer, Entry> entries = entries(chunk);
        ListTag list = new ListTag();
        synchronized (entries) {
            for (Map.Entry<Integer, Entry> item : entries.entrySet()) {
                int packed = item.getKey();
                Entry entry = item.getValue();
                if (!validEntry(chunk, entry)) continue;
                CompoundTag encoded = new CompoundTag();
                encoded.putByte("x", (byte) (packed & 15));
                encoded.putByte("z", (byte) (packed >>> 4));
                encoded.putInt("bed", entry.bed());
                encoded.putInt("water", entry.water());
                encoded.putInt("roof", entry.roof());
                encoded.putDouble("flow_x", entry.flowX());
                encoded.putDouble("flow_z", entry.flowZ());
                encoded.putBoolean("lake", entry.lake());
                list.add(encoded);
            }
        }
        if (list.isEmpty()) tag.remove(NBT_KEY);
        else tag.put(NBT_KEY, list);
    }

    /** Replaces chunk data with validated serialized markers; malformed data is ignored. */
    public static void read(ChunkAccess chunk, CompoundTag tag) {
        chunk = unwrap(chunk);
        Map<Integer, Entry> entries = entries(chunk);
        synchronized (entries) {
            entries.clear();
            if (!tag.contains(NBT_KEY, Tag.TAG_LIST)) return;
            ListTag list = tag.getList(NBT_KEY, Tag.TAG_COMPOUND);
            int limit = Math.min(list.size(), MAX_ENTRIES);
            for (int i = 0; i < limit; i++) {
                CompoundTag encoded = list.getCompound(i);
                if (!hasEntryShape(encoded)) continue;
                int localX = encoded.getByte("x");
                int localZ = encoded.getByte("z");
                Entry entry = new Entry(encoded.getInt("bed"), encoded.getInt("water"), encoded.getInt("roof"),
                        encoded.getDouble("flow_x"), encoded.getDouble("flow_z"), encoded.getBoolean("lake"));
                if (validPosition(localX, localZ) && validEntry(chunk, entry)) {
                    entries.put(key(localX, localZ), entry);
                }
            }
        }
    }

    private static Map<Integer, Entry> entries(ChunkAccess chunk) {
        return ((RiverChannelDataHolder) unwrap(chunk)).terrainDiffusion$riverChannelEntries();
    }

    private static ChunkAccess unwrap(ChunkAccess chunk) {
        return chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : chunk;
    }

    private static boolean validEntry(ChunkAccess chunk, Entry entry) {
        if (entry == null || !entry.isValid()) return false;
        int minY = chunk.getMinBuildHeight();
        int maxYExclusive = minY + chunk.getHeight();
        return entry.bed() >= minY && entry.roof() < maxYExclusive;
    }

    private static boolean validPosition(int localX, int localZ) {
        return localX >= 0 && localX < 16 && localZ >= 0 && localZ < 16;
    }

    private static int key(int localX, int localZ) {
        return localX | (localZ << 4);
    }

    private static boolean hasEntryShape(CompoundTag encoded) {
        return encoded.contains("x", Tag.TAG_BYTE)
                && encoded.contains("z", Tag.TAG_BYTE)
                && encoded.contains("bed", Tag.TAG_INT)
                && encoded.contains("water", Tag.TAG_INT)
                && encoded.contains("roof", Tag.TAG_INT)
                && encoded.contains("flow_x", Tag.TAG_DOUBLE)
                && encoded.contains("flow_z", Tag.TAG_DOUBLE)
                && encoded.contains("lake", Tag.TAG_BYTE);
    }

    /** Factory used only by the ChunkAccess mixin. */
    public static Map<Integer, Entry> newStorage() {
        return new LinkedHashMap<>(MAX_ENTRIES);
    }
}
