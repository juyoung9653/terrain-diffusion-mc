import com.github.xandergos.terraindiffusionmc.world.structure.StructureHeightCache;

public final class StructureHeightCacheTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void invalid(int capacity) {
        try { new StructureHeightCache(capacity); throw new AssertionError("invalid capacity accepted: "+capacity); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    public static void main(String[] args) {
        check(StructureHeightCache.DEFAULT_CAPACITY==65_536,"documented default cap");
        invalid(-1); invalid(65_537);

        var cache=new StructureHeightCache(2);
        long negative=((long)-31<<32) ^ (17L & 0xffffffffL);
        long extreme=((long)Integer.MAX_VALUE<<32) ^ (Integer.MIN_VALUE & 0xffffffffL);
        check(cache.get(negative)==null && cache.get(extreme)==null,"negative and extreme coordinates miss independently");
        cache.recordHeightCall(); cache.recordHeightCall();
        var first=new StructureHeightCache.Heights(71,73);
        cache.put(negative,first);
        check(cache.get(negative).equals(first),"stored negative coordinate hits");
        cache.put(extreme,new StructureHeightCache.Heights(-12,-9));
        cache.put(42L,new StructureHeightCache.Heights(1,2));
        var snapshot=cache.snapshot();
        check(snapshot.hits()==1 && snapshot.misses()==2 && snapshot.nativeHeightCalls()==2,
                "lookup and native-call counters are exact");
        check(snapshot.entries()==2 && snapshot.capDrops()==1 && snapshot.capacity()==2,
                "full cache retains entries without eviction");
        check(cache.get(42L)==null,"dropped key remains uncached");

        var disabled=new StructureHeightCache(0);
        check(disabled.get(negative)==null,"disabled cache still exposes a miss");
        disabled.put(negative,first); disabled.recordHeightCall();
        snapshot=disabled.snapshot();
        check(snapshot.entries()==0 && snapshot.capDrops()==1 && snapshot.misses()==1
                && snapshot.nativeHeightCalls()==1,"disabled reference preserves accounting");

        var separate=new StructureHeightCache(1);
        check(separate.get(negative)==null,"caches never share entries");
        check(first.oceanFloor()==71 && first.worldSurface()==73,"height pair contains no support verdict");
        check(first.equals(new StructureHeightCache.Heights(71,73)),"height pairs have immutable value semantics");
        System.out.println("PASS StructureHeightCacheTest assertions="+assertions);
    }
}
