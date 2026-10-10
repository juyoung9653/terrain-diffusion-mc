package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.pipeline.EcologyData;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** Diagnostic process-local bridge at the full terrain tile boundary. */
public final class TerrainReplayStore {
    private static final String MODE = System.getProperty("td.terrainMode", "full");
    private static Path directory;
    private static String snapshotId = "", failure;
    private static boolean initialized;
    private static long recorded, replayed, misses, ioNanos;

    public static synchronized String mode() { initialize(); return MODE; }
    public static synchronized Map<String, Object> report() {
        initialize();
        var result = new LinkedHashMap<String, Object>();
        result.put("mode", MODE); result.put("snapshotId", snapshotId);
        result.put("recordedTiles", recorded); result.put("replayedTiles", replayed);
        result.put("misses", misses); result.put("snapshotIoSeconds", ioNanos / 1_000_000_000.0);
        result.put("failure", failure);
        result.put("bypassed", MODE.equals("replay") ? List.of("full-tile pipeline inference",
                "full-tile climate and biome classification", "full-tile ecology calculation") : List.of());
        return result;
    }
    private static void initialize() {
        if (initialized) return;
        if (!Set.of("full", "record", "replay").contains(MODE)) throw fail("Unknown td.terrainMode", null);
        if (!MODE.equals("full")) {
            if (!LocalTerrainProvider.legacySampling()) throw fail("Terrain snapshots require legacy sampling", null);
            if (MODE.equals("replay") && Boolean.getBoolean("td.shoreAudit"))
                throw fail("Terrain replay cannot produce shore audit evidence", null);
            snapshotId = System.getProperty("td.terrainSnapshotId", "");
            if (!snapshotId.matches("[0-9a-f]{64}")) throw fail("Invalid td.terrainSnapshotId", null);
            directory = Path.of(System.getProperty("td.terrainSnapshotDir", ""));
            if (!directory.isAbsolute()) throw fail("td.terrainSnapshotDir must be absolute", null);
            directory = directory.normalize();
            try {
                safePath(directory);
                if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw new IOException("Snapshot directory must exist");
                validateRequest();
            } catch (IOException | RuntimeException e) { throw fail("Invalid terrain snapshot directory/request", e); }
        }
        initialized = true;
    }

    private static void validateRequest() throws IOException {
        Path request = directory.resolve("request.json");
        safePath(request);
        if (!Files.isRegularFile(request, NOFOLLOW_LINKS)) throw new IOException("Missing snapshot request.json");
        try (var channel = FileChannel.open(request, READ, NOFOLLOW_LINKS);
             var in = new JsonReader(Channels.newReader(channel, StandardCharsets.UTF_8))) {
            if (channel.size() > 16 * 1024 * 1024) throw new IOException("Snapshot request exceeds size limit");
            in.setLenient(false);
            String fingerprint = null; boolean schema = false;
            in.beginObject();
            while (in.hasNext()) {
                String field = in.nextName();
                if (field.equals("fingerprint")) {
                    if (fingerprint != null || in.peek() != JsonToken.STRING) throw new IOException("Invalid/duplicate request fingerprint");
                    fingerprint = in.nextString();
                } else if (field.equals("schema")) {
                    if (schema || in.peek() != JsonToken.NUMBER || !in.nextString().equals("1")) throw new IOException("Invalid/duplicate request schema");
                    schema = true;
                } else in.skipValue();
            }
            in.endObject();
            if (!schema || !snapshotId.equals(fingerprint) || in.peek() != JsonToken.END_DOCUMENT)
                throw new IOException("Snapshot request identity/schema mismatch");
        }
    }

    public static synchronized HeightmapData replay(long seed, int i1, int j1, int i2, int j2, int scale) {
        initialize();
        if (!MODE.equals("replay")) return null;
        long start = System.nanoTime();
        try {
            var key = new TerrainReplayCodec.Key(snapshotId, seed, scale, i1, j1, i2, j2);
            var tile = TerrainReplayCodec.decode(key, read(directory.resolve(key.filename())));
            EcologyData ecology = tile.ecologyIds() == null ? null : new EcologyData(key.width(), key.height(),
                    tile.ecologyIds()[0], tile.ecologyIds()[1], tile.ecologyValues()[0], tile.ecologyValues()[1],
                    tile.ecologyValues()[2], tile.ecologyValues()[3], tile.ecologyValues()[4]);
            replayed++;
            return new HeightmapData(tile.heightmap(), tile.biomeIds(), tile.terralithBiomeIds(), ecology, key.width(), key.height());
        } catch (IOException | RuntimeException e) {
            misses++;
            throw fail("Terrain replay failed for seed=" + seed + " scale=" + scale + " bounds="
                    + i1 + "," + j1 + "," + i2 + "," + j2, e);
        } finally { ioNanos += System.nanoTime() - start; }
    }

    public static synchronized void record(long seed, int i1, int j1, int i2, int j2, int scale, HeightmapData data) {
        initialize();
        if (!MODE.equals("record")) return;
        long start = System.nanoTime();
        try {
            var key = new TerrainReplayCodec.Key(snapshotId, seed, scale, i1, j1, i2, j2);
            if (data == null || data.width != key.width() || data.height != key.height()) throw new IOException("Tile dimensions differ from request");
            short[][] ids = null; float[][] values = null;
            if (data.ecology != null) {
                if (data.ecology.width != data.width || data.ecology.height != data.height) throw new IOException("Ecology dimensions differ");
                ids = new short[2][data.width * data.height]; values = new float[5][data.width * data.height];
                for (int z = 0; z < data.height; z++) for (int x = 0; x < data.width; x++) {
                    int at = z * data.width + x;
                    var sample = data.ecology.sample(x, z);
                    ids[0][at] = (short)data.ecology.biomeId(x, z); ids[1][at] = data.ecology.terrainBiomeId(x, z);
                    values[0][at] = sample.treeCover(); values[1][at] = sample.temperature();
                    values[2][at] = sample.moisture(); values[3][at] = sample.slope();
                    values[4][at] = data.ecology.snowRetention(x, z);
                }
            }
            byte[] bytes = TerrainReplayCodec.encode(key, new TerrainReplayCodec.Tile(data.heightmap, data.biomeIds,
                    data.terralithBiomeIds, ids, values));
            Path target = directory.resolve(key.filename()), lock = directory.resolve(".tiles.lock");
            safePath(lock);
            try (var channel = FileChannel.open(lock, CREATE, WRITE, NOFOLLOW_LINKS); var ignored = channel.lock()) {
                if (Files.exists(target, NOFOLLOW_LINKS)) {
                    if (!Arrays.equals(bytes, read(target))) throw new IOException("Existing terrain snapshot differs for same key");
                } else {
                    Path temp = Files.createTempFile(directory, ".tile-", ".tmp");
                    try {
                        try (var out = FileChannel.open(temp, WRITE, NOFOLLOW_LINKS)) {
                            ByteBuffer buffer = ByteBuffer.wrap(bytes);
                            while (buffer.hasRemaining()) out.write(buffer);
                            out.force(true);
                        }
                        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                    } finally { Files.deleteIfExists(temp); }
                }
            }
            recorded++;
        } catch (IOException | RuntimeException e) { throw fail("Terrain snapshot recording failed", e); }
        finally { ioNanos += System.nanoTime() - start; }
    }
    private static byte[] read(Path path) throws IOException {
        safePath(path);
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw new IOException("Missing/nonregular terrain tile: " + path.getFileName());
        try (var channel = FileChannel.open(path, READ, NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 112 || size > TerrainReplayCodec.MAX_BYTES) throw new IOException("Invalid terrain tile size");
            ByteBuffer buffer = ByteBuffer.allocate((int)size);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new IOException("Truncated terrain tile");
            if (channel.size() != size) throw new IOException("Terrain tile changed during read");
            return buffer.array();
        }
    }
    private static void safePath(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part) || (Files.exists(part, NOFOLLOW_LINKS)
                    && Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes.class, NOFOLLOW_LINKS).isOther()))
                throw new IOException("Linked/special snapshot paths are forbidden: " + part);
    }
    private static IllegalStateException fail(String message, Exception cause) {
        if (failure == null) failure = message + (cause == null ? "" : ": " + cause.getMessage());
        return new IllegalStateException(message, cause);
    }
}
