import com.github.xandergos.terraindiffusionmc.world.RingBiomeSearch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/** Framework-free behavioral tests for RingBiomeSearch. */
public final class RingBiomeSearchTest {
    private record Point(int x, int z) { }

    private RingBiomeSearchTest() { }

    public static void main(String[] args) {
        testVanillaGridCoverageAtEveryTileSize();
        testDefaultTilesNeedAtMostFourLoads();
        testScoresOrderTilesButCannotRejectMispredictedTile();
        testOnlyPreferredBiomeIsReturned();
        testAllRejectedExhaustsTilesAndReturnsNull();
        testEvictingLoaderStillLoadsEachTileOnce();
        testTiedScoresChooseTilesInProportionToTheirArea();
        testRandomnessIsDeterministicAndDiverse();
        testWinningTileIsSearchedExhaustively();
        System.out.println("RingBiomeSearchTest: PASS");
    }

    private static void testVanillaGridCoverageAtEveryTileSize() {
        int[] sizes = {1, 2, 4, 128, 256, 512};
        int[][] centers = {{0, 0}, {-17, -35}, {15, -19}};
        int[] radii = {0, 1, 3, 5, 111, 112};
        for (int[] center : centers) {
            for (int radius : radii) {
                Set<Point> expected = vanillaGrid(center[0], center[1], radius);
                for (int size : sizes) {
                    Set<Point> actual = new HashSet<>();
                    for (RingBiomeSearch.Tile tile : RingBiomeSearch.tiles(center[0], center[1], radius, size)) {
                        for (int qz = tile.minQuartZ(); qz <= tile.maxQuartZ(); qz++) {
                            for (int qx = tile.minQuartX(); qx <= tile.maxQuartX(); qx++) {
                                check(actual.add(new Point(qx * 4, qz * 4)),
                                        "duplicate quart candidate for radius " + radius + ", tile size " + size);
                            }
                        }
                    }
                    check(actual.equals(expected), "radius " + radius + ", tile size " + size
                            + " differs from the vanilla quart grid");
                    if (radius == 112) check(actual.size() == 57 * 57,
                            "radius 112 must cover 57x57 quart candidates");
                }
            }
        }
    }

    private static void testDefaultTilesNeedAtMostFourLoads() {
        for (int[] center : new int[][] {{0, 0}, {-17, -35}, {15, -19}}) {
            List<RingBiomeSearch.Tile> tiles = RingBiomeSearch.tiles(center[0], center[1], 112, 256);
            check(tiles.size() <= 4, "default 256-block tiling needs more than four tiles");
            RingBiomeSearch.Result<String> result = RingBiomeSearch.find(
                    tiles, ignored -> 0, ignored -> (x, z) -> "preferred", b -> true, boundedRandom(7));
            check(result.loadedTiles() == 1, "first matching default tile should be loaded once");
        }
    }

    private static void testScoresOrderTilesButCannotRejectMispredictedTile() {
        RingBiomeSearch.Tile noMatch = new RingBiomeSearch.Tile(0, 0, 0, 0, 0, 0);
        RingBiomeSearch.Tile match = new RingBiomeSearch.Tile(4, 0, 1, 0, 1, 0);
        RingBiomeSearch.Result<String> result = RingBiomeSearch.find(
                List.of(noMatch, match), tile -> tile == noMatch ? 100 : -100,
                tile -> (x, z) -> tile == match ? "preferred" : "other",
                "preferred"::equals, boundedRandom(4));
        check("preferred".equals(result.biome()) && result.blockX() == 4,
                "low coarse score must not reject an exactly preferred tile");
        check(result.loadedTiles() == 2, "mispredicted first tile must be followed by the matching tile");

        RingBiomeSearch.Result<String> ranked = RingBiomeSearch.find(
                List.of(noMatch, match), tile -> tile == match ? 100 : -100,
                tile -> (x, z) -> "preferred", "preferred"::equals, boundedRandom(4));
        check(ranked.blockX() == 4 && ranked.loadedTiles() == 1,
                "higher score must make its match-containing tile the first one searched");
    }

    private static void testOnlyPreferredBiomeIsReturned() {
        RingBiomeSearch.Tile tile = new RingBiomeSearch.Tile(0, 0, 0, 0, 1, 0);
        RingBiomeSearch.Result<String> result = RingBiomeSearch.find(
                List.of(tile), ignored -> 0, ignored -> (x, z) -> x == 0 ? "other" : "preferred",
                "preferred"::equals, boundedRandom(2));
        check("preferred".equals(result.biome()) && result.blockX() == 4,
                "result must be an exact preferred sample, never a fallback sample");
    }

    private static void testAllRejectedExhaustsTilesAndReturnsNull() {
        List<RingBiomeSearch.Tile> tiles = RingBiomeSearch.tiles(-17, -35, 8, 4);
        int candidates = vanillaGrid(-17, -35, 8).size();
        RingBiomeSearch.Result<String> result = RingBiomeSearch.find(
                tiles, ignored -> 0, ignored -> (x, z) -> "rejected", "preferred"::equals, boundedRandom(3));
        check(result.biome() == null, "no-match result must have null biome fallback");
        check(result.loadedTiles() == tiles.size(), "no-match result must load every tile");
        check(result.testedSamples() == candidates, "no-match result must test every vanilla candidate");
    }

    private static void testEvictingLoaderStillLoadsEachTileOnce() {
        List<RingBiomeSearch.Tile> tiles = RingBiomeSearch.tiles(0, 0, 16, 4);
        Map<RingBiomeSearch.Tile, Integer> loads = new HashMap<>();
        List<RingBiomeSearch.Tile> simulatedGlobalCache = new ArrayList<>();
        RingBiomeSearch.Result<String> result = RingBiomeSearch.find(tiles, ignored -> 0, tile -> {
            loads.merge(tile, 1, Integer::sum);
            simulatedGlobalCache.clear(); // Simulate an unrelated global cache eviction on every load.
            simulatedGlobalCache.add(tile);
            return (x, z) -> "rejected";
        }, "preferred"::equals, boundedRandom(19));
        check(result.biome() == null, "eviction test intentionally has no preferred biome");
        check(loads.size() == tiles.size(), "every tile must have been loaded");
        for (int count : loads.values()) check(count == 1, "a tile was loaded more than once after cache eviction");
    }

    private static void testTiedScoresChooseTilesInProportionToTheirArea() {
        List<Point> first = tiedScoreChoices(551_903L, 4_000);
        List<Point> repeat = tiedScoreChoices(551_903L, 4_000);
        check(first.equals(repeat), "a continuous seeded random sequence must be deterministic");
        long smallTileWins = first.stream().filter(point -> point.x() == 0).count();
        double smallTileRate = smallTileWins / 4_000.0;
        check(smallTileRate >= 0.003 && smallTileRate <= 0.03,
                "one-sample tied tile should win near 1% against 99 samples; got " + smallTileRate);
    }

    private static List<Point> tiedScoreChoices(long seed, int searches) {
        RingBiomeSearch.Tile small = new RingBiomeSearch.Tile(0, 0, 0, 0, 0, 0);
        RingBiomeSearch.Tile large = new RingBiomeSearch.Tile(4, 0, 1, 0, 99, 0);
        Random random = new Random(seed);
        IntUnaryOperator continuousRandom = random::nextInt;
        List<Point> choices = new ArrayList<>();
        for (int i = 0; i < searches; i++) {
            RingBiomeSearch.Result<String> result = RingBiomeSearch.find(List.of(small, large), ignored -> 0,
                    ignored -> (x, z) -> "preferred", "preferred"::equals, continuousRandom);
            check(result.loadedTiles() == 1, "all-preferred tied search must load only its selected first tile");
            choices.add(new Point(result.blockX(), result.blockZ()));
        }
        return choices;
    }

    private static void testRandomnessIsDeterministicAndDiverse() {
        RingBiomeSearch.Tile tile = new RingBiomeSearch.Tile(0, 0, 0, 0, 7, 7);
        RingBiomeSearch.Result<String> first = runRandom(tile, 987654321L);
        RingBiomeSearch.Result<String> second = runRandom(tile, 987654321L);
        check(first.equals(second), "same random seed must produce the same result");
        Set<Point> accepted = new HashSet<>();
        for (long seed = 0; seed < 80; seed++) {
            RingBiomeSearch.Result<String> result = runRandom(tile, seed);
            check("preferred".equals(result.biome()), "random result must still be preferred");
            accepted.add(new Point(result.blockX(), result.blockZ()));
        }
        check(accepted.size() >= 12, "many seeds should select diverse accepted points");
    }

    private static RingBiomeSearch.Result<String> runRandom(RingBiomeSearch.Tile tile, long seed) {
        return RingBiomeSearch.find(List.of(tile), ignored -> 0, ignored -> (x, z) -> "preferred",
                "preferred"::equals, boundedRandom(seed));
    }

    private static void testWinningTileIsSearchedExhaustively() {
        RingBiomeSearch.Tile winner = new RingBiomeSearch.Tile(0, 0, 0, 0, 1, 1);
        RingBiomeSearch.Tile unused = new RingBiomeSearch.Tile(8, 0, 2, 0, 2, 0);
        Set<Point> sampled = new HashSet<>();
        Map<RingBiomeSearch.Tile, Integer> loads = new HashMap<>();
        RingBiomeSearch.Result<String> result = RingBiomeSearch.find(List.of(winner, unused),
                tile -> tile == winner ? 5 : 0, tile -> {
                    loads.merge(tile, 1, Integer::sum);
                    return (x, z) -> {
                        sampled.add(new Point(x, z));
                        return "preferred";
                    };
                }, "preferred"::equals, boundedRandom(12));
        check(result.loadedTiles() == 1 && result.testedSamples() == 4,
                "winning tile must be fully searched before returning");
        check(sampled.equals(Set.of(new Point(0, 0), new Point(4, 0), new Point(0, 4), new Point(4, 4))),
                "every candidate in winning tile must be sampled");
        check(loads.size() == 1 && loads.get(winner) == 1, "only winning tile should load once");
    }

    private static Set<Point> vanillaGrid(int x, int z, int radius) {
        int centerQx = Math.floorDiv(x, 4), centerQz = Math.floorDiv(z, 4), radiusQ = radius / 4;
        Set<Point> result = new HashSet<>();
        for (int qz = centerQz - radiusQ; qz <= centerQz + radiusQ; qz++)
            for (int qx = centerQx - radiusQ; qx <= centerQx + radiusQ; qx++)
                result.add(new Point(qx * 4, qz * 4));
        return result;
    }

    private static IntUnaryOperator boundedRandom(long seed) {
        Random random = new Random(seed);
        return random::nextInt;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
