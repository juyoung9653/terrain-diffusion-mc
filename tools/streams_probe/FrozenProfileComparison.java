import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Compares preserved profile configuration values without replacing its JAR. */
public final class FrozenProfileComparison {
    private static final String[] ACCESSORS = {"seaLevel", "plateScale", "plateJitter", "gridSpacing", "terrainSampleSpacing", "valleySnapRadius", "minRiverAccumulation", "baseWidth", "maxWidth", "widthScale", "streamDepth", "stepDeltaY", "bankWidth", "bankSlope", "bankNoise", "bankSteepFactor", "ditchInset", "aRiver", "borderMargin", "smoothingPasses", "meanderStrength", "seaLevelExtension", "reconnectRadius", "wetnessDryCutoff", "wetnessWetReference", "connectNearbyWater", "connectRadius", "sinkRooted", "growFromWater", "growDensity", "confineToBanks", "containmentBerm", "wetnessMultiplier", "wetnessDryFloor", "minSeaChainNodes", "minLengthScope", "lakeMinArea", "lakeMaxArea", "outletBiomes", "hydraulics"};

    public static void main(String[] args) throws Exception {
        URL nativeJar = Path.of(args[1]).toUri().toURL();
        try (URLClassLoader frozen = loader(Path.of(args[0]).toUri().toURL(), nativeJar);
             URLClassLoader current = loader(Path.of(args[2]).toUri().toURL(), nativeJar)) {
            for (int scaleIndex = 3; scaleIndex < args.length; scaleIndex++) {
                int scale = Integer.parseInt(args[scaleIndex]);
                for (int profile = 1; profile <= 3; profile++) {
                    if (!config(frozen, scale, profile).equals(config(current, scale, profile))) {
                        throw new AssertionError("scale " + scale + " profile " + profile + " differs from frozen-v3");
                    }
                }
            }
        }
        System.out.println("FrozenProfileComparison: PASS scales 2,5,6 profiles 1-3");
    }

    private static URLClassLoader loader(URL first, URL nativeJar) {
        return new URLClassLoader(new URL[]{first, nativeJar}, ClassLoader.getPlatformClassLoader());
    }

    private static String config(ClassLoader loader, int scale, int profile) throws Exception {
        Class<?> config = Class.forName("dev.streamsreflowing.core.config.StreamsConfig", true, loader);
        Object original = config.getMethod("defaults").invoke(null);
        Class<?> adapter = Class.forName("com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalConfig", true, loader);
        Object result = adapter.getMethod("apply", Object.class, int.class, int.class).invoke(null, original, scale, profile);
        StringBuilder values = new StringBuilder();
        for (String name : ACCESSORS) values.append(name).append('=').append(stable(config.getMethod(name).invoke(result))).append('\n');
        return values.toString();
    }

    private static String stable(Object value) throws Exception {
        if (value == null) return "null";
        if (value instanceof Iterable<?> values) {
            StringBuilder result = new StringBuilder("[");
            for (Object item : values) result.append(stable(item)).append(',');
            return result.append(']').toString();
        }
        var components = value.getClass().getRecordComponents();
        if (components == null) return String.valueOf(value);
        StringBuilder result = new StringBuilder(value.getClass().getName()).append('(');
        for (var component : components) result.append(component.getName()).append('=').append(stable(component.getAccessor().invoke(value))).append(',');
        return result.append(')').toString();
    }
}
