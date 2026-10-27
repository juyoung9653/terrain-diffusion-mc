import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;

/** Creates a small, offline HotSpot dump for hprof_owners.py validation. */
public final class HprofFixture {
    static Object keep;
    static class Parent { final int[] inherited = new int[65_536]; }
    static final class Child extends Parent {
        final float[] cache = new float[65_536];
        final Object[] refs = {cache};
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("dump path required");
        keep = new Child();
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpHeap(args[0], true);
    }
}
