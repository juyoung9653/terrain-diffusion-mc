import dev.streamsreflowing.core.terrain.CachingTerrainSampler;
import dev.streamsreflowing.core.terrain.TerrainSampler;

/** Executes the shipped 2.15.2 CachingTerrainSampler, not a replacement fixture. */
public final class StreamsNativeCachingBatchContractTest {
    public static void main(String[] args) {
        int[] xs = {-511, -1, 0, 73, 255, 256};
        int[] zs = {510, -2, 0, 44, 255, 257};
        ExactHost scalarHost = new ExactHost();
        CachingTerrainSampler scalarCache = new CachingTerrainSampler(scalarHost, 2048, -1000.0);
        ExactHost batchHost = new ExactHost();
        CachingTerrainSampler batchCache = new CachingTerrainSampler(batchHost, 2048, -1000.0);
        if (batchCache.batchCapable()) throw new AssertionError("native wrapper changed delegate capability");
        double[] expected = new double[xs.length], actual = new double[xs.length];
        for (int i = 0; i < xs.length; i++) expected[i] = scalarCache.height(xs[i], zs[i]);
        batchCache.heightsBatch(xs, zs, actual, actual.length);
        if (batchHost.batchCalls != 1) throw new AssertionError("native wrapper did not forward one batch");
        for (int i = 0; i < xs.length; i++) {
            if (Double.doubleToLongBits(expected[i]) != Double.doubleToLongBits(actual[i]))
                throw new AssertionError("native batch scalar mismatch at " + i);
        }
        System.out.println("PASS native 2.15.2 CachingTerrainSampler forwards exact host batch");
    }

    private static final class ExactHost implements TerrainSampler {
        int batchCalls;
        @Override public double height(int x, int z) { return x * 0.125d - z * 0.03125d + 17.5d; }
        @Override public boolean batchCapable() { return false; }
        @Override public void heightsBatch(int[] xs, int[] zs, double[] out, int count) {
            batchCalls++;
            for (int i = 0; i < count; i++) out[i] = height(xs[i], zs[i]);
        }
    }
}
