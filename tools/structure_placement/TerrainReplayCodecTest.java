import com.github.xandergos.terraindiffusionmc.diagnostic.TerrainReplayCodec;
import com.github.xandergos.terraindiffusionmc.diagnostic.TerrainReplayCodec.Key;
import com.github.xandergos.terraindiffusionmc.diagnostic.TerrainReplayCodec.Tile;
import com.github.xandergos.terraindiffusionmc.diagnostic.TerrainReplayStore;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyData;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;

public final class TerrainReplayCodecTest {
    static final String ID = "a".repeat(64);
    interface Checked { void run() throws Exception; }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void rejected(Checked action) throws Exception {
        try { action.run(); } catch (java.io.IOException | IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    static Tile tile() {
        return new Tile(new short[][]{{Short.MIN_VALUE, -1}, {0, Short.MAX_VALUE}},
                new short[][]{{1, 2}, {3, 4}}, null,
                new short[][]{{-1, 2, 3, 4}, {4, 3, 2, 1}},
                new float[][]{{-0f, 0f, Float.intBitsToFloat(0x7fc12345), Float.POSITIVE_INFINITY},
                    {1f, -1f, Float.NEGATIVE_INFINITY, Float.MIN_VALUE}, {2, 3, 4, 5}, {5, 6, 7, 8}, {9, 10, 11, 12},
                    {Float.intBitsToFloat(0x3e99999a), .5f, 1f, 0f}});
    }
    static HeightmapData nativeTile() {
        Tile t = tile();
        return new HeightmapData(t.heightmap(), t.biomeIds(), t.terralithBiomeIds(),
                new EcologyData(2, 2, t.ecologyIds()[0], t.ecologyIds()[1], t.ecologyValues()[0],
                    t.ecologyValues()[1], t.ecologyValues()[2], t.ecologyValues()[3], t.ecologyValues()[4],
                    t.ecologyValues()[5]), 2, 2);
    }
    static void resign(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, bytes.length - 32));
        System.arraycopy(digest, 0, bytes, bytes.length - 32, 32);
    }
    static void codec() throws Exception {
        for (int scale : new int[]{1, 4}) for (int origin : new int[]{-300, 300}) {
            Key key = new Key(ID, -123L, scale, origin, -origin, origin + 2, -origin + 2);
            for (Tile tile : new Tile[]{tile(), new Tile(null, null, null, null, null),
                    new Tile(tile().heightmap(), null, tile().heightmap(), null, null)}) {
                byte[] bytes = TerrainReplayCodec.encode(key, tile);
                check(Arrays.equals(bytes, TerrainReplayCodec.encode(key, TerrainReplayCodec.decode(key, bytes))));
                byte[] bad = bytes.clone(); bad[10] ^= 1;
                rejected(() -> TerrainReplayCodec.decode(key, bad));
                rejected(() -> TerrainReplayCodec.decode(new Key(ID, 124, scale, origin, -origin, origin + 2, -origin + 2), bytes));
                rejected(() -> TerrainReplayCodec.decode(new Key("b".repeat(64), -123, scale, origin, -origin, origin + 2, -origin + 2), bytes));
                rejected(() -> TerrainReplayCodec.decode(key, Arrays.copyOf(bytes, bytes.length - 1)));
                byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1); resign(trailing);
                rejected(() -> TerrainReplayCodec.decode(key, trailing));
                byte[] version = bytes.clone(); version[7] = 3; resign(version);
                rejected(() -> TerrainReplayCodec.decode(key, version));
                byte[] dimensions = bytes.clone(); dimensions[71] ^= 1; resign(dimensions);
                rejected(() -> TerrainReplayCodec.decode(key, dimensions));
                byte[] flag = bytes.clone(); flag[76] = 2; resign(flag);
                rejected(() -> TerrainReplayCodec.decode(key, flag));
            }
            rejected(() -> TerrainReplayCodec.encode(key, new Tile(new short[1][1], null, null, null, null)));
            rejected(() -> TerrainReplayCodec.encode(key, new Tile(null, null, null, new short[2][4], new float[4][4])));
        }
        rejected(() -> new Key(ID, 0, 0, 0, 0, 2, 2));
        rejected(() -> new Key(ID, 0, 1, Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 2));
        rejected(() -> new Key(ID, 0, 1, 0, 0, 4096, 4096));
        rejected(() -> new Key("no", 0, 1, 0, 0, 2, 2));
    }
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "codec" -> codec();
            case "full" -> {
                check(TerrainReplayStore.mode().equals("full"));
                check(TerrainReplayStore.replay(0, 0, 0, 0, 0, 0) == null);
                TerrainReplayStore.record(0, 0, 0, 0, 0, 0, null);
                check(TerrainReplayStore.report().get("snapshotId").equals(""));
            }
            case "reject-mode" -> rejected(TerrainReplayStore::mode);
            case "record" -> {
                for (int scale : new int[]{1, 4}) {
                    TerrainReplayStore.record(-123, -2, 3, 0, 5, scale, nativeTile());
                    TerrainReplayStore.record(-123, -2, 3, 0, 5, scale, nativeTile());
                }
                check(TerrainReplayStore.report().get("recordedTiles").equals(4L));
            }
            case "mismatch" -> {
                HeightmapData changed = nativeTile(); changed.heightmap[0][0]++;
                rejected(() -> TerrainReplayStore.record(-123, -2, 3, 0, 5, 1, changed));
                check(TerrainReplayStore.report().get("failure") != null);
            }
            case "replay" -> {
                for (int scale : new int[]{1, 4}) {
                    HeightmapData data = TerrainReplayStore.replay(-123, -2, 3, 0, 5, scale);
                    check(Arrays.deepEquals(data.heightmap, tile().heightmap()));
                    check(Arrays.deepEquals(data.biomeIds, tile().biomeIds()));
                    check(data.terralithBiomeIds == null && data.width == 2 && data.height == 2);
                    for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
                        int at = z * 2 + x; var sample = data.ecology.sample(x, z);
                        check(data.ecology.biomeId(x, z) == tile().ecologyIds()[0][at]);
                        check(data.ecology.terrainBiomeId(x, z) == tile().ecologyIds()[1][at]);
                        float[] actual = {sample.treeCover(), sample.temperature(), sample.moisture(), sample.slope(),
                                data.ecology.snowRetention(x, z), data.ecology.openWaterFraction(x, z)};
                        for (int plane = 0; plane < 6; plane++) check(Float.floatToRawIntBits(actual[plane])
                                == Float.floatToRawIntBits(tile().ecologyValues()[plane][at]));
                    }
                }
                check(TerrainReplayStore.report().get("replayedTiles").equals(2L));
                check(TerrainReplayStore.report().get("failure") == null);
            }
            case "miss" -> {
                rejected(() -> TerrainReplayStore.replay(-123, -2, 3, 0, 5, 1));
                check(TerrainReplayStore.report().get("misses").equals(1L));
                check(TerrainReplayStore.report().get("failure") != null);
            }
            default -> throw new AssertionError(args[0]);
        }
        System.out.println("PASS terrain replay " + args[0]);
    }
}
