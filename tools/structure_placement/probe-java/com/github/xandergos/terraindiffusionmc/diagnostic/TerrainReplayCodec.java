package com.github.xandergos.terraindiffusionmc.diagnostic;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Versioned, bounded binary format; no Minecraft dependencies. Floats retain raw bits. */
public final class TerrainReplayCodec {
    private static final int MAGIC = 0x54445452, VERSION = 2;
    public static final int MAX_BYTES = 128 * 1024 * 1024;
    public record Key(String snapshotId, long seed, int scale, int i1, int j1, int i2, int j2) {
        public Key {
            if (snapshotId == null || !snapshotId.matches("[0-9a-f]{64}") || scale < 1
                    || (long)i2 - i1 < 1 || (long)j2 - j1 < 1
                    || (long)i2 - i1 > 4096 || (long)j2 - j1 > 4096
                    || ((long)i2 - i1) * ((long)j2 - j1) > 4_000_000)
                throw new IllegalArgumentException("Invalid terrain snapshot key or dimensions");
        }
        public int width() { return j2 - j1; }
        public int height() { return i2 - i1; }
        public String filename() {
            return seed + "_" + scale + "_" + i1 + "_" + j1 + "_" + i2 + "_" + j2 + ".tile";
        }
    }
    public record Tile(short[][] heightmap, short[][] biomeIds, short[][] terralithBiomeIds,
                       short[][] ecologyIds, float[][] ecologyValues) { }

    public static byte[] encode(Key key, Tile tile) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(VERSION);
            out.write(HexFormat.of().parseHex(key.snapshotId()));
            out.writeLong(key.seed()); out.writeInt(key.scale());
            out.writeInt(key.i1()); out.writeInt(key.j1()); out.writeInt(key.i2()); out.writeInt(key.j2());
            out.writeInt(key.width()); out.writeInt(key.height());
            writeShorts(out, tile.heightmap(), key.height(), key.width());
            writeShorts(out, tile.biomeIds(), key.height(), key.width());
            writeShorts(out, tile.terralithBiomeIds(), key.height(), key.width());
            boolean ecology = tile.ecologyIds() != null;
            if (ecology != (tile.ecologyValues() != null)) throw new IOException("Incomplete ecology");
            out.writeBoolean(ecology);
            if (ecology) {
                int cells = key.width() * key.height();
                writeShorts(out, tile.ecologyIds(), 2, cells);
                if (tile.ecologyValues().length != 6) throw new IOException("Invalid ecology plane count");
                for (float[] plane : tile.ecologyValues()) {
                    if (plane == null || plane.length != cells) throw new IOException("Invalid ecology dimensions");
                    for (float value : plane) out.writeInt(Float.floatToRawIntBits(value));
                }
            }
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length + 32 > MAX_BYTES) throw new IOException("Terrain snapshot exceeds size limit");
        bytes.write(digest(payload));
        return bytes.toByteArray();
    }

    public static Tile decode(Key expected, byte[] bytes) throws IOException {
        if (bytes.length < 112 || bytes.length > MAX_BYTES) throw new IOException("Invalid terrain snapshot size");
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(payload), Arrays.copyOfRange(bytes, payload.length, bytes.length)))
            throw new IOException("Terrain snapshot checksum mismatch");
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported terrain snapshot format");
            Key actual;
            try {
                actual = new Key(HexFormat.of().formatHex(in.readNBytes(32)), in.readLong(), in.readInt(),
                        in.readInt(), in.readInt(), in.readInt(), in.readInt());
            } catch (IllegalArgumentException e) { throw new IOException("Invalid terrain snapshot header", e); }
            if (!actual.equals(expected) || in.readInt() != expected.width() || in.readInt() != expected.height())
                throw new IOException("Terrain snapshot key/dimensions mismatch");
            short[][] heights = readShorts(in, expected.height(), expected.width());
            short[][] biomes = readShorts(in, expected.height(), expected.width());
            short[][] terralith = readShorts(in, expected.height(), expected.width());
            short[][] ids = null; float[][] values = null;
            if (flag(in)) {
                int cells = expected.width() * expected.height();
                ids = readShorts(in, 2, cells);
                if (ids == null || (long)cells * 24 > in.available()) throw new IOException("Incomplete ecology payload");
                values = new float[6][cells];
                for (float[] plane : values) for (int i = 0; i < cells; i++) plane[i] = Float.intBitsToFloat(in.readInt());
            }
            if (in.available() != 0) throw new IOException("Trailing terrain snapshot payload");
            return new Tile(heights, biomes, terralith, ids, values);
        }
    }

    private static void writeShorts(DataOutputStream out, short[][] values, int rows, int cols) throws IOException {
        out.writeBoolean(values != null);
        if (values == null) return;
        if (values.length != rows) throw new IOException("Invalid short array rows");
        for (short[] row : values) {
            if (row == null || row.length != cols) throw new IOException("Invalid short array columns");
            for (short value : row) out.writeShort(value);
        }
    }
    private static short[][] readShorts(DataInputStream in, int rows, int cols) throws IOException {
        if (!flag(in)) return null;
        if ((long)rows * cols * 2 > in.available()) throw new IOException("Truncated terrain array");
        short[][] values = new short[rows][cols];
        for (short[] row : values) for (int i = 0; i < cols; i++) row[i] = in.readShort();
        return values;
    }
    private static boolean flag(DataInputStream in) throws IOException {
        int flag = in.readUnsignedByte();
        if (flag > 1) throw new IOException("Invalid terrain presence flag");
        return flag == 1;
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
