import com.github.xandergos.terraindiffusionmc.world.river.NaturalLakeLevels;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalLakeLevels.Lake;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class NaturalLakeLevelsTest {
    public static void main(String[] args) throws Exception {
        var lines = Files.readAllLines(Path.of("tools/river_planner/fixtures/nested-lake-masks.txt"));
        long[] inner = new long[lines.size()], outer = new long[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            String[] pair = lines.get(i).split(" ");
            inner[i] = Long.parseUnsignedLong(pair[0], 16);
            outer[i] = Long.parseUnsignedLong(pair[1], 16);
        }
        require(Arrays.stream(inner).map(Long::bitCount).sum() == 1543, "saved inner mask");
        require(Arrays.stream(outer).map(Long::bitCount).sum() == 1764, "saved outer mask");
        var saved = List.of(new Lake(106, 0, 3, -8014, 31, 118, 134, inner),
                new Lake(107, 0, 3, -8014, 31, 118, 134, outer));
        check(saved, 106, 106);
        for (int version = 0; version < 10; version++)
            require(Arrays.equals(NaturalLakeLevels.resolve(version, saved), new int[]{106,107}), "legacy changed");
        check(List.of(lake(107, 3), lake(106, 1)), 106, 106);
        check(List.of(lake(107, 3), lake(105, 3)), 105, 105); // equal masks
        check(List.of(lake(106, 1), lake(107, 3), lake(108, 7)), 106, 106, 106);
        check(List.of(lake(106, 1), lake(105, 2), lake(107, 3)), 105, 105, 105); // shared basin
        check(List.of(lake(107, 3), lake(105, 2), lake(106, 1)), 105, 105, 105); // reversed
        check(List.of(lake(106, 1), lake(107, 2)), 106, 107); // touching, not contained
        check(List.of(lake(106, 3), lake(107, 6)), 106, 107); // partial overlap
        check(List.of(lake(106, 0), lake(107, 3)), 106, 107); // empty
        check(List.of(lake(106, 1), new Lake(107, 1, 3, 0, 0, 64, 1, new long[]{3})), 106, 107);
        check(List.of(lake(106, 1), new Lake(107, 0, 3, 1, 0, 64, 1, new long[]{3})), 106, 107);
        check(List.of(lake(106, 1), new Lake(107, 0, 4, 0, 0, 64, 1, new long[]{3})), 106, 107);
        check(List.of(), new int[0]);
        System.out.println("NaturalLakeLevelsTest: PASS saved nested basin, legacy, ordering, idempotence and separate-basin exclusions");
    }

    private static Lake lake(int level, long mask) { return new Lake(level, 0, 3, 0, 0, 64, 1, new long[]{mask}); }
    private static void check(List<Lake> lakes, int... expected) {
        long[][] before = lakes.stream().map(l -> l.mask().clone()).toArray(long[][]::new);
        require(Arrays.equals(NaturalLakeLevels.resolve(10, lakes), expected), "wrong waterline");
        var normalized = new java.util.ArrayList<Lake>();
        for (int i = 0; i < lakes.size(); i++) {
            Lake l = lakes.get(i);
            require(Arrays.equals(before[i], l.mask()), "footprint changed");
            normalized.add(new Lake(expected[i], l.digDepth(), l.spacing(), l.minX(), l.minZ(), l.width(), l.height(), l.mask()));
        }
        require(Arrays.equals(NaturalLakeLevels.resolve(10, normalized), expected), "not idempotent");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
