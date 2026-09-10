import com.github.xandergos.terraindiffusionmc.compat.streams.StructureShieldGeometry;

import java.util.Arrays;
import java.util.List;

/** Dependency-free regression harness for the Streams shield tuple transformation. */
public final class StructureShieldGeometryTest {
    private static final int PAD = 3;

    public static void main(String[] args) {
        collisionRetainsNonTargetTuple();
        insufficientIdenticalTargetsLeaveOriginalUntouched();
        unchangedInputsRemainUntouched();
        paddingReachesOneToThreeBlocksOutsideChunk();
        entrancePieceRemainsProtected();
        largeUndergroundAggregateDoesNotShieldWithoutNearbyPiece();
        System.out.println("StructureShieldGeometryTest passed");
    }

    private static void collisionRetainsNonTargetTuple() {
        var aggregate = box(10, 20, 30, 40, 70);
        int[] original = concat(tuple(aggregate), tuple(aggregate), tuple(box(1, 2, 3, 4, 5)));
        int[] before = original.clone();
        int[] result = StructureShieldGeometry.replaceAggregates(original,
                List.of(new StructureShieldGeometry.Replacement(aggregate, List.of(box(12, 22, 14, 24, 68)))), PAD);
        require(count(result, tuple(aggregate)) == 1, "identical non-target aggregate must remain");
        require(contains(result, tuple(box(12, 22, 14, 24, 68))), "piece tuple missing");
        require(Arrays.equals(original, before), "input array and unrelated tuples must remain untouched");
    }

    private static void unchangedInputsRemainUntouched() {
        int[] malformed = {1, 2, 3};
        int[] missing = tuple(box(1, 1, 2, 2, 40));
        var replacement = new StructureShieldGeometry.Replacement(box(9, 9, 10, 10, 40), List.of(box(9, 9, 9, 9, 40)));
        require(StructureShieldGeometry.replaceAggregates(malformed, List.of(replacement), PAD) == malformed, "malformed tuple array must stay original");
        require(StructureShieldGeometry.replaceAggregates(missing, List.of(replacement), PAD) == missing, "missing aggregate must stay original");
    }

    private static void insufficientIdenticalTargetsLeaveOriginalUntouched() {
        var aggregate = box(10, 20, 30, 40, 70);
        int[] original = tuple(aggregate);
        var first = new StructureShieldGeometry.Replacement(aggregate, List.of(box(11, 21, 12, 22, 69)));
        var second = new StructureShieldGeometry.Replacement(aggregate, List.of(box(13, 23, 14, 24, 69)));
        require(StructureShieldGeometry.replaceAggregates(original, List.of(first, second), PAD) == original,
                "two target aggregates with only one matching tuple must leave the original array unchanged");
    }

    private static void paddingReachesOneToThreeBlocksOutsideChunk() {
        var piece = box(16, 4, 16, 5, 60);
        int[] result = StructureShieldGeometry.replaceAggregates(tuple(box(0, 0, 31, 31, 60)),
                List.of(new StructureShieldGeometry.Replacement(box(0, 0, 31, 31, 60), List.of(piece))), PAD);
        require(contains(result, new int[] {13, 1, 19, 8, 60}), "three-block piece padding must be exact");
        require(covers(result, 16, 4, 60) && covers(result, 17, 4, 60)
                && covers(result, 18, 4, 60) && covers(result, 19, 4, 60), "one to three blocks outside must remain protected");
        require(!covers(result, 20, 4, 60), "four blocks outside must not be protected");

        // The mixin selects pieces against the original chunk expanded by three blocks.
        int expandedMin = -3, expandedMax = 18;
        require(box(16, 0, 16, 0, 60).intersects(expandedMin, expandedMin, expandedMax, expandedMax)
                && box(17, 0, 17, 0, 60).intersects(expandedMin, expandedMin, expandedMax, expandedMax)
                && box(18, 0, 18, 0, 60).intersects(expandedMin, expandedMin, expandedMax, expandedMax),
                "east pieces one to three blocks outside must be selected");
        require(!box(19, 0, 19, 0, 60).intersects(expandedMin, expandedMin, expandedMax, expandedMax),
                "east piece four blocks outside must not be selected");
        require(box(-1, 0, -1, 0, 60).intersects(expandedMin, expandedMin, expandedMax, expandedMax)
                && box(-2, 0, -2, 0, 60).intersects(expandedMin, expandedMin, expandedMax, expandedMax)
                && box(-3, 0, -3, 0, 60).intersects(expandedMin, expandedMin, expandedMax, expandedMax),
                "west pieces one to three blocks outside must be selected");
        require(!box(-4, 0, -4, 0, 60).intersects(expandedMin, expandedMin, expandedMax, expandedMax),
                "west piece four blocks outside must not be selected");
        require(box(0, 16, 0, 16, 60).intersects(expandedMin, expandedMin, expandedMax, expandedMax)
                && box(0, 18, 0, 18, 60).intersects(expandedMin, expandedMin, expandedMax, expandedMax)
                && !box(0, 19, 0, 19, 60).intersects(expandedMin, expandedMin, expandedMax, expandedMax),
                "Z-boundary selection must use the same inclusive three-block range");
    }

    private static void entrancePieceRemainsProtected() {
        var aggregate = box(0, 0, 90, 90, 80);
        var entrance = box(14, 14, 18, 18, 79);
        int[] result = StructureShieldGeometry.replaceAggregates(tuple(aggregate),
                List.of(new StructureShieldGeometry.Replacement(aggregate, List.of(entrance))), PAD);
        require(contains(result, tuple(entrance)), "actual entrance piece protection must remain");
        require(contains(result, new int[] {11, 11, 21, 21, 79}), "piece maxY must be preserved");
    }

    private static void largeUndergroundAggregateDoesNotShieldWithoutNearbyPiece() {
        var aggregate = box(-100, -100, 100, 100, 70);
        int[] result = StructureShieldGeometry.replaceAggregates(tuple(aggregate),
                List.of(new StructureShieldGeometry.Replacement(aggregate, List.of())), PAD);
        require(!contains(result, tuple(aggregate)), "aggregate shield must be removed");
        require(!covers(result, 0, 0, 64), "distant piece must not shield the aggregate center");
    }

    private static StructureShieldGeometry.Box box(int minX, int minZ, int maxX, int maxZ, int maxY) {
        return new StructureShieldGeometry.Box(minX, minZ, maxX, maxZ, maxY);
    }

    private static int[] tuple(StructureShieldGeometry.Box box) { return box.paddedTuple(PAD); }
    private static int[] concat(int[]... parts) { int[] result = new int[parts.length * 5]; for (int i = 0; i < parts.length; i++) System.arraycopy(parts[i], 0, result, i * 5, 5); return result; }
    private static boolean contains(int[] values, int[] tuple) { return count(values, tuple) != 0; }
    private static int count(int[] values, int[] tuple) { int count = 0; for (int i = 0; i < values.length; i += 5) { boolean match = true; for (int j = 0; j < 5; j++) match &= values[i + j] == tuple[j]; if (match) count++; } return count; }
    private static boolean covers(int[] values, int x, int z, int groundY) { for (int i = 0; i < values.length; i += 5) if (x >= values[i] && x <= values[i + 2] && z >= values[i + 1] && z <= values[i + 3] && values[i + 4] >= groundY - 6) return true; return false; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
