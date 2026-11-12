import com.github.xandergos.terraindiffusionmc.world.structure.StructureSiteCompatibility;
import java.util.concurrent.atomic.AtomicInteger;

public final class StructureSiteCompatibilityTest {
    private static final String MONUMENT = "betteroceanmonuments:ocean_monument";
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static StructureSiteCompatibility.Bounds monumentBounds() {
        return new StructureSiteCompatibility.Bounds(100, 40, 200, 214, 103, 325); // 115 by 126 observed envelope
    }
    public static void main(String[] args) {
        var bounds = monumentBounds(); int columns = (int)bounds.columnCount();
        check(columns == 115 * 126, "fixture retains the observed full footprint");
        var deep = StructureSiteCompatibility.check(MONUMENT, bounds, 2, columns, 63,
                (x,z) -> new StructureSiteCompatibility.Heights(42,63));
        check(deep.accepted() && deep.applicable() && deep.sampledColumns()==columns,
                "uniform deep ocean accepts after every footprint column is checked");
        var corner = StructureSiteCompatibility.check(MONUMENT, bounds, 2, columns, 63,
                (x,z) -> x==bounds.maxX() && z==bounds.maxZ() ? new StructureSiteCompatibility.Heights(43,63) : new StructureSiteCompatibility.Heights(42,63));
        check(!corner.accepted() && corner.sampledColumns()==columns && corner.reason().contains("shallow floor"),
                "one shallow coast corner rejects the complete monument start");
        AtomicInteger skippedCalls=new AtomicInteger();
        var other=StructureSiteCompatibility.check("minecraft:monument",bounds,2,columns,63,(x,z)->{skippedCalls.incrementAndGet();return null;});
        check(other.accepted() && !other.applicable() && skippedCalls.get()==0,"vanilla monument remains outside YUNG-specific guard");
        AtomicInteger budgetCalls=new AtomicInteger();
        var budget=StructureSiteCompatibility.check(MONUMENT,bounds,2,columns-1,63,(x,z)->{budgetCalls.incrementAndGet();return new StructureSiteCompatibility.Heights(42,63);});
        check(!budget.accepted() && budget.sampledColumns()==0 && budgetCalls.get()==0 && budget.reason().contains("sample budget"),
                "insufficient sample budget fails closed before native height work");
        System.out.println("PASS: Better Ocean Monuments deep-water site compatibility");
    }
}
