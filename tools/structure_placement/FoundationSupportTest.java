import com.github.xandergos.terraindiffusionmc.world.structure.FoundationSupport;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

public final class FoundationSupportTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static int repair(int maxFill, Set<Integer> air, Set<Integer> natural) {
        return FoundationSupport.repairDepth(maxFill, air::contains, natural::contains);
    }
    private static Set<Integer> set(int... values) {
        var result = new HashSet<Integer>();
        for (int value : values) result.add(value);
        return result;
    }
    public static void main(String[] args) {
        check(repair(3, set(1), set(2, 3)) == 1, "one-block void joins a two-block footing");
        check(repair(3, set(1, 2), set(3, 4)) == 2, "two-block void joins a two-block footing");
        check(repair(3, set(1, 2, 3), set(4, 5)) == 3, "maximum bounded void joins footing");
        check(repair(3, set(), set(1, 2)) == 0, "already supported floor stays untouched");
        check(repair(3, set(1), set(3, 4)) == 0, "non-natural obstruction is never overwritten");
        check(repair(3, set(1), set(2)) == 0, "one-block floating footing is rejected");
        check(repair(2, set(1, 2, 3), set(4, 5)) == 0, "gap deeper than policy is rejected");
        check(repair(0, set(1), set(2, 3)) == 0, "zero fill policy rejects repair");
        check(repair(-1, set(1), set(2, 3)) == 0, "negative fill policy rejects repair");

        var airQueries = new HashSet<Integer>();
        var naturalQueries = new HashSet<Integer>();
        int depth = FoundationSupport.repairDepth(2, value -> {
            airQueries.add(value);
            return value <= 2;
        }, value -> {
            naturalQueries.add(value);
            return value == 3 || value == 4;
        });
        check(depth == 2, "bounded query example repairs two blocks");
        check(airQueries.equals(set(1, 2)), "air scan stops at max fill");
        check(naturalQueries.equals(set(3, 4)), "footing scan reads exactly two blocks");
        check(assertions == 12, "all regression assertions executed");
        System.out.println("FoundationSupportTest: PASS assertions=" + assertions);
    }
}
