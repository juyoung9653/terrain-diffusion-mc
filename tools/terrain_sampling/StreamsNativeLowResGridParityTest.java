import dev.streamsreflowing.core.config.StreamsConfig;
import dev.streamsreflowing.core.grid.LowResGrid;
import dev.streamsreflowing.core.plate.PlateDomain;
import dev.streamsreflowing.core.plate.PlateLattice;
import dev.streamsreflowing.core.terrain.TerrainSampler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Runs the shipped LowResGrid's scalar and batch branches over one identical plate. */
public final class StreamsNativeLowResGridParityTest {
    public static void main(String[] args) {
        runCase(false);
        runCase(true);
    }

    private static void runCase(boolean adaptive) {
        StreamsConfig config = configured(adaptive);
        if (config.gridSpacing() != 16 || config.terrainSampleSpacing() != 32
                || config.adaptiveGridRead() != adaptive) throw new AssertionError("production fill config lost");
        PlateLattice lattice = new PlateLattice(0x42L, config.plateScale(), config.plateJitter());
        PlateDomain domain = new PlateDomain(lattice, lattice.plateAt(0, 0));
        ExactSampler scalarSampler = new ExactSampler(false);
        ExactSampler batchSampler = new ExactSampler(true);
        LowResGrid scalar = new LowResGrid(domain, scalarSampler, config);
        LowResGrid batched = new LowResGrid(domain, batchSampler, config);
        if (scalar.size() != batched.size()) throw new AssertionError("grid shape changed");
        for (int i = 0; i < scalar.size(); i++) {
            if (Double.doubleToLongBits(scalar.height(i)) != Double.doubleToLongBits(batched.height(i)))
                throw new AssertionError("scalar/batch LowResGrid mismatch at " + i);
        }
        if (batchSampler.batchCalls == 0) throw new AssertionError("native batch branch was not used");
        System.out.println("PASS native 2.15.2 LowResGrid scalar/batch parity spacing=16 sampleStep=32 adaptive="
                + adaptive + " cells=" + scalar.size() + " batchCalls=" + batchSampler.batchCalls);
    }

    /**
     * Reconstruct the shipped record-like class from defaults, changing only tested fill
     * controls. The 2.15.2 JAR extends Record but omits the Record attribute, so its
     * reflection recordComponents() is null; use its ordered instance fields and named
     * public component accessors instead.
     */
    private static StreamsConfig configured(boolean adaptive) {
        try {
            StreamsConfig base = StreamsConfig.defaults();
            List<Field> fields = new ArrayList<>();
            for (Field field : StreamsConfig.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) fields.add(field);
            }
            Class<?>[] types = new Class<?>[fields.size()];
            Object[] values = new Object[fields.size()];
            for (int i = 0; i < fields.size(); i++) {
                Field field = fields.get(i);
                types[i] = field.getType();
                values[i] = StreamsConfig.class.getMethod(field.getName()).invoke(base);
                if (field.getName().equals("gridSpacing")) values[i] = 16;
                if (field.getName().equals("terrainSampleSpacing")) values[i] = 32;
                if (field.getName().equals("adaptiveGridRead")) values[i] = adaptive;
            }
            Constructor<StreamsConfig> ctor = StreamsConfig.class.getConstructor(types);
            return ctor.newInstance(values);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot reconstruct native StreamsConfig", failure);
        }
    }

    private static final class ExactSampler implements TerrainSampler {
        private final boolean batch;
        int batchCalls;
        ExactSampler(boolean batch) { this.batch = batch; }
        @Override public double height(int x, int z) {
            return Math.sin(x * 0.013) * 90.0 + Math.cos(z * 0.019) * 55.0 + x * 0.0001 + z * 0.00001;
        }
        @Override public boolean batchCapable() { return batch; }
        @Override public void heightsBatch(int[] xs, int[] zs, double[] out, int count) {
            batchCalls++;
            for (int i = 0; i < count; i++) out[i] = height(xs[i], zs[i]);
        }
    }
}
