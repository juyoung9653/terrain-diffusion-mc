import com.github.xandergos.terraindiffusionmc.world.river.RiverPreviewCache;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class RiverPreviewCacheTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("td-river-preview-cache-test-");
        RiverPreviewCache.Key key = new RiverPreviewCache.Key(1, 12345L, 2, -256, -256,
                256, 8, 1024, 1, 6, 1);
        RiverPreviewCache.Data data = sample(key);
        Path cache = directory.resolve("preview.tdrp.gz");

        RiverPreviewCache.write(cache, data);
        RiverPreviewCache.Data loaded = RiverPreviewCache.read(cache, key);
        require(loaded.key().equals(key), "round-trip key");
        require(Arrays.equals(data.ground(), loaded.ground()), "round-trip ground");
        require(Arrays.equals(data.runoff(), loaded.runoff()), "round-trip runoff");
        require(Arrays.equals(data.ocean(), loaded.ocean()), "round-trip ocean");
        require(Arrays.equals(data.fullGround(), loaded.fullGround()), "round-trip full ground");
        RiverPreviewCache.Key naturalKey = new RiverPreviewCache.Key(2, 12345L, 2, -256, -256,
                256, 8, 1024, 1, 6, 1);
        expectIOException(() -> RiverPreviewCache.read(cache, naturalKey));
        Path naturalCache = directory.resolve("natural.tdrp.gz");
        RiverPreviewCache.write(naturalCache, new RiverPreviewCache.Data(naturalKey,
                data.ground(), data.runoff(), data.ocean(), data.fullGround()));
        require(RiverPreviewCache.read(naturalCache, naturalKey).key().version() == 2, "natural version round-trip");
        expectIOException(() -> RiverPreviewCache.read(naturalCache, key));

        double[] extracted = loaded.ground();
        extracted[0] = -99;
        require(loaded.ground()[0] != -99, "accessor defensive copy");

        expectIOException(() -> RiverPreviewCache.read(cache, new RiverPreviewCache.Key(1, 12346L, 2, -256, -256,
                256, 8, 1024, 1, 6, 1)));

        RiverPreviewCache.Key profile2Key = new RiverPreviewCache.Key(1, 12345L, 2, -256, -256,
                256, 8, 1024, 1, 6, 2);
        expectIOException(() -> RiverPreviewCache.read(cache, profile2Key));
        Path legacy = directory.resolve("legacy.tdrp.gz");
        writeLegacy(legacy, data);
        require(RiverPreviewCache.read(legacy, key).key().terrainProfile() == 1,
                "legacy cache defaults to profile 1");
        expectIOException(() -> RiverPreviewCache.read(legacy, profile2Key));

        byte[] encoded = Files.readAllBytes(cache);
        Path truncated = directory.resolve("truncated.tdrp.gz");
        Files.write(truncated, Arrays.copyOf(encoded, encoded.length - 3));
        expectIOException(() -> RiverPreviewCache.read(truncated, key));

        Path trailing = directory.resolve("trailing.tdrp.gz");
        writeWithTrailingByte(cache, trailing);
        expectIOException(() -> RiverPreviewCache.read(trailing, key));

        Path corrupt = directory.resolve("corrupt.tdrp.gz");
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(corrupt)))) {
            output.writeInt(0);
            output.writeInt(1);
        }
        expectIOException(() -> RiverPreviewCache.read(corrupt, key));

        byte[] preserved = Files.readAllBytes(cache);
        expectIOException(() -> RiverPreviewCache.write(cache, data));
        require(Arrays.equals(preserved, Files.readAllBytes(cache)), "existing cache preservation");

        expectIllegalArgument(() -> new RiverPreviewCache.Data(key, new double[1], new double[1], new boolean[1], new short[1]));
        double[] nanGround = data.ground();
        nanGround[0] = Double.NaN;
        expectIllegalArgument(() -> new RiverPreviewCache.Data(key, nanGround, data.runoff(), data.ocean(), data.fullGround()));
        System.out.println("RiverPreviewCacheTest: OK");
    }

    private static RiverPreviewCache.Data sample(RiverPreviewCache.Key key) {
        double[] ground = new double[key.gridLength()];
        double[] runoff = new double[key.gridLength()];
        boolean[] ocean = new boolean[key.gridLength()];
        short[] fullGround = new short[key.fullLength()];
        for (int index = 0; index < ground.length; index++) {
            ground[index] = 64.25 + index;
            runoff[index] = index / 10.0;
            ocean[index] = index % 7 == 0;
        }
        for (int index = 0; index < fullGround.length; index++) fullGround[index] = (short) (index % 256);
        return new RiverPreviewCache.Data(key, ground, runoff, ocean, fullGround);
    }

    private static void writeWithTrailingByte(Path source, Path target) throws IOException {
        byte[] decoded;
        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(source))) {
            decoded = input.readAllBytes();
        }
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(target))) {
            output.write(decoded);
            output.write(7);
        }
    }

    private static void writeLegacy(Path target, RiverPreviewCache.Data data) throws IOException {
        RiverPreviewCache.Key key = data.key();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(target)))) {
            output.writeInt(0x54445250);
            output.writeInt(1);
            output.writeInt(key.version());
            output.writeLong(key.seed());
            output.writeInt(key.scale());
            output.writeInt(key.originX());
            output.writeInt(key.originZ());
            output.writeInt(key.size());
            output.writeInt(key.spacing());
            output.writeInt(key.minCatchment());
            output.writeInt(key.maxFillDepth());
            output.writeInt(key.maxCut());
            for (double value : data.ground()) output.writeDouble(value);
            for (double value : data.runoff()) output.writeDouble(value);
            for (boolean value : data.ocean()) output.writeByte(value ? 1 : 0);
            for (short value : data.fullGround()) output.writeShort(value);
        }
    }

    private static void expectIOException(ThrowingAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    private static void expectIllegalArgument(ThrowingAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
