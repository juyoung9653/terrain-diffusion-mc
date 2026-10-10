import com.github.xandergos.terraindiffusionmc.world.structure.PlacementGeometry;
import com.github.xandergos.terraindiffusionmc.world.structure.StructurePreflightControl;
import java.lang.reflect.Array;
import java.util.Optional;

/** Exercises the actual optional Streams signal and cache behavior, without a Minecraft server. */
public final class PreflightControlTest {
    public static void main(String[] args) throws Exception {
        var type=Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen$StructurePlatesPending");
        var constructor=type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object owners=Array.newInstance(constructor.getParameterTypes()[0].getComponentType(),0);
        RuntimeException pending=(RuntimeException)constructor.newInstance(owners);
        require(StructurePreflightControl.isDeferred(pending),"native pending signal recognized");
        require(!StructurePreflightControl.isDeferred(new IllegalStateException("StructurePlatesPending")),"ordinary error retains fallback");
        var cache=new PlacementGeometry.PreflightCache<String,Optional<String>>(8);
        try {
            cache.get("owner",() -> {
                try {throw pending;}
                catch(RuntimeException error) {
                    if(StructurePreflightControl.isDeferred(error)) throw error;
                    return Optional.empty();
                }
            });
            throw new AssertionError("pending was swallowed");
        } catch(RuntimeException error) {require(error==pending,"exact signal reaches scheduler");}
        require(cache.size()==0,"pending result is not cached as absent");
        require(cache.get("owner",()->Optional.of("village")).orElseThrow().equals("village"),"ready retry becomes viable");
        require(cache.get("owner",()->{throw new AssertionError("recomputed");}).isPresent(),"completed retry cached");
        System.out.println("PreflightControlTest: PASS (actual Streams exception, uncached defer, successful retry)");
    }
    private static void require(boolean condition,String message) {if(!condition) throw new AssertionError(message);}
}
