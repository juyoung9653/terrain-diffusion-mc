package com.github.xandergos.terraindiffusionmc.world.river;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Exact immutable inputs for a finite river preview. This class intentionally
 * has no Minecraft dependency so restarts cannot re-sample nondeterministic
 * model output after a cache has been committed.
 */
public final class RiverPreviewCache {
    private static final int MAGIC = 0x54445250; // TDRP
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_GRID_SIDE = 513;

    private RiverPreviewCache() {
    }

    public record Key(int version, long seed, int scale, int originX, int originZ, int size, int spacing,
                      int minCatchment, int maxFillDepth, int maxCut) {
        public Key {
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported river preview cache key version: " + version);
            }
            if (scale < 1 || scale > 6) {
                throw new IllegalArgumentException("River preview cache scale must be from 1 through 6: " + scale);
            }
            validateBounds(size, spacing, minCatchment, maxFillDepth, maxCut);
            if (originX > Integer.MAX_VALUE - size || originZ > Integer.MAX_VALUE - size) {
                throw new IllegalArgumentException("River preview cache bounds exceed the integer coordinate range");
            }
        }

        public int gridSide() {
            return size / spacing + 1;
        }

        public int gridLength() {
            return gridSide() * gridSide();
        }

        public int fullLength() {
            int side = size + 1;
            return Math.multiplyExact(side, side);
        }
    }

    public record Data(Key key, double[] ground, double[] runoff, boolean[] ocean, short[] fullGround) {
        public Data {
            if (key == null || ground == null || runoff == null || ocean == null || fullGround == null) {
                throw new IllegalArgumentException("River preview cache data cannot contain null fields");
            }
            validateArrays(key, ground, runoff, ocean, fullGround);
            ground = ground.clone();
            runoff = runoff.clone();
            ocean = ocean.clone();
            fullGround = fullGround.clone();
        }

        @Override
        public double[] ground() {
            return ground.clone();
        }

        @Override
        public double[] runoff() {
            return runoff.clone();
        }

        @Override
        public boolean[] ocean() {
            return ocean.clone();
        }

        @Override
        public short[] fullGround() {
            return fullGround.clone();
        }
    }

    public static Data read(Path path, Key expected) throws IOException {
        if (path == null || expected == null) {
            throw new IllegalArgumentException("Cache path and expected key are required");
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid river preview cache header");
            }
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported river preview cache format");
            }
            Key actual = readKey(input);
            if (!actual.equals(expected)) {
                throw new IOException("River preview cache key does not match this world settings");
            }

            int gridLength = expected.gridLength();
            int fullLength = expected.fullLength();
            double[] ground = new double[gridLength];
            double[] runoff = new double[gridLength];
            boolean[] ocean = new boolean[gridLength];
            short[] fullGround = new short[fullLength];
            for (int index = 0; index < gridLength; index++) ground[index] = input.readDouble();
            for (int index = 0; index < gridLength; index++) runoff[index] = input.readDouble();
            for (int index = 0; index < gridLength; index++) {
                int encoded = input.readUnsignedByte();
                if (encoded != 0 && encoded != 1) throw new IOException("Invalid river preview cache ocean value");
                ocean[index] = encoded == 1;
            }
            for (int index = 0; index < fullLength; index++) fullGround[index] = input.readShort();
            if (input.read() != -1) {
                throw new IOException("Trailing data in river preview cache");
            }
            try {
                return new Data(actual, ground, runoff, ocean, fullGround);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid river preview cache payload", invalid);
            }
        } catch (EOFException truncated) {
            throw new IOException("Truncated river preview cache", truncated);
        }
    }

    public static void write(Path path, Data data) throws IOException {
        if (path == null || data == null) {
            throw new IllegalArgumentException("Cache path and data are required");
        }
        validateArrays(data.key, data.ground, data.runoff, data.ocean, data.fullGround);
        Path target = path.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("River preview cache requires a parent directory");
        Files.createDirectories(parent);
        if (Files.exists(target)) throw new FileAlreadyExistsException(target.toString());

        Path temporary = parent.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        boolean committed = false;
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary))))) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);
                writeKey(output, data.key);
                for (double value : data.ground) output.writeDouble(value);
                for (double value : data.runoff) output.writeDouble(value);
                for (boolean value : data.ocean) output.writeByte(value ? 1 : 0);
                for (short value : data.fullGround) output.writeShort(value);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic river preview cache creation is unavailable", unsupported);
            }
            committed = true;
        } finally {
            if (!committed) Files.deleteIfExists(temporary);
        }
    }

    private static Key readKey(DataInputStream input) throws IOException {
        try {
            return new Key(input.readInt(), input.readLong(), input.readInt(), input.readInt(), input.readInt(),
                    input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid river preview cache key", invalid);
        }
    }

    private static void writeKey(DataOutputStream output, Key key) throws IOException {
        output.writeInt(key.version);
        output.writeLong(key.seed);
        output.writeInt(key.scale);
        output.writeInt(key.originX);
        output.writeInt(key.originZ);
        output.writeInt(key.size);
        output.writeInt(key.spacing);
        output.writeInt(key.minCatchment);
        output.writeInt(key.maxFillDepth);
        output.writeInt(key.maxCut);
    }

    private static void validateBounds(int size, int spacing, int minCatchment, int maxFillDepth, int maxCut) {
        if (size < 256 || size > 8192 || !isPowerOfTwo(size)) {
            throw new IllegalArgumentException("River preview cache size must be a power of two from 256 through 8192");
        }
        if (spacing < 8 || spacing > 64 || !isPowerOfTwo(spacing) || size % spacing != 0) {
            throw new IllegalArgumentException("River preview cache spacing must be a power of two from 8 through 64 dividing size");
        }
        int gridSide = size / spacing + 1;
        if (gridSide > MAX_GRID_SIDE) {
            throw new IllegalArgumentException("River preview cache grid must contain at most 513 by 513 samples");
        }
        if (minCatchment < 1 || minCatchment > size * size) {
            throw new IllegalArgumentException("River preview cache min catchment is out of bounds");
        }
        if (maxFillDepth < 0 || maxFillDepth > 1) {
            throw new IllegalArgumentException("River preview cache max fill depth must be from 0 through 1");
        }
        if (maxCut < 0 || maxCut > 64) {
            throw new IllegalArgumentException("River preview cache max cut must be from 0 through 64");
        }
    }

    private static void validateArrays(Key key, double[] ground, double[] runoff, boolean[] ocean, short[] fullGround) {
        if (ground.length != key.gridLength() || runoff.length != key.gridLength() || ocean.length != key.gridLength()
                || fullGround.length != key.fullLength()) {
            throw new IllegalArgumentException("River preview cache array lengths do not match its key");
        }
        for (double value : ground) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("River preview cache ground must be finite");
        }
        for (double value : runoff) {
            if (!Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("River preview cache runoff must be finite and nonnegative");
            }
        }
    }

    private static boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }
}
