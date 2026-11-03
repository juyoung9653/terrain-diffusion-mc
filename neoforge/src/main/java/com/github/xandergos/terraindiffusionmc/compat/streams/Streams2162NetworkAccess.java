package com.github.xandergos.terraindiffusionmc.compat.streams;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Audited 2.16.2 graph boundary.  Streams keeps its spatial index, lakes, sinks
 * and relief object; TD replaces only the segment list before that index is rebuilt.
 */
final class Streams2162NetworkAccess {
    private static final String NETWORK = "dev.streamsreflowing.core.river.RiverNetwork";
    private static final String BUILDER =
            "dev.streamsreflowing.core.river.NoDecompilePermissionEver_001";
    private static final String DOMAIN =
            "dev.streamsreflowing.core.plate.ProprietaryNiceJohn_001";

    private Streams2162NetworkAccess() { }

    static boolean applies(Object network) {
        if (network == null || !NETWORK.equals(network.getClass().getName())) return false;
        try {
            // RiverNetwork retains its class name across Streams releases.  This
            // accessor exists only in the audited 2.16.2 record layout.
            return List.class.isAssignableFrom(network.getClass()
                    .getMethod("sourceGivenToAuthorizedOnly1").getReturnType())
                    && network.getClass().getMethod("noDecompilePermission1").getReturnType()
                    .getName().equals("dev.streamsreflowing.core.plate.PlateKey");
        } catch (NoSuchMethodException changed) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    static List<?> segments(Object network) { return (List<?>) call(network, "sourceGivenToAuthorizedOnly1"); }
    static Object key(Object network) { return call(network, "noDecompilePermission1"); }
    @SuppressWarnings("unchecked")
    static List<?> lakes(Object network) { return (List<?>) call(network, "allRightsReserved1"); }
    @SuppressWarnings("unchecked")
    static List<?> sinks(Object network) { return (List<?>) call(network, "sinks"); }
    static Object relief(Object network) { return call(network, "niceJohn1"); }

    static Object lakeNear(Object network, int x, int z, int radius) {
        return call(network, "proprietary1", new Class<?>[] {int.class, int.class, int.class}, x, z, radius);
    }

    static boolean contains(Object domain, double x, double z) {
        return (Boolean) call(domain, "proprietary1", new Class<?>[] {double.class, double.class}, x, z);
    }

    static double borderDistance(Object domain, double x, double z) {
        return ((Number) call(domain, "seeAiNotice1", new Class<?>[] {double.class, double.class}, x, z)).doubleValue();
    }

    static Object reassemble(Object network, List<?> segments, Object config, boolean filterSinks) {
        try {
            ClassLoader loader = network.getClass().getClassLoader();
            Class<?> builder = Class.forName(BUILDER, false, loader);
            Class<?> keyType = Class.forName("dev.streamsreflowing.core.plate.PlateKey", false, loader);
            Class<?> reliefType = Class.forName("dev.streamsreflowing.core.NoDecompilePermissionEver_001", false, loader);
            Method assemble = builder.getMethod("proprietary1", keyType, List.class, List.class,
                    List.class, reliefType, config.getClass());
            return assemble.invoke(null, key(network), segments, lakes(network),
                    filterSinks ? retainedSinks(network, segments) : sinks(network), relief(network), config);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Streams 2.16.2 graph reassembly contract changed", error);
        }
    }

    private static List<?> retainedSinks(Object network, List<?> segments) {
        Set<Long> endpoints = new HashSet<>();
        for (Object segment : segments) endpoints.add(endpoint(segment, "proprietary2",
                "seeAiNotice2"));
        List<Object> retained = new ArrayList<>();
        for (Object sink : sinks(network)) {
            if (endpoints.contains(endpoint(sink, "x", "z"))) retained.add(sink);
        }
        return retained;
    }

    private static long endpoint(Object value, String xName, String zName) {
        try {
            double x = ((Number) value.getClass().getMethod(xName).invoke(value)).doubleValue();
            double z = ((Number) value.getClass().getMethod(zName).invoke(value)).doubleValue();
            return ((Math.round(x * 16.0) & 0xffffffffL) << 32) | (Math.round(z * 16.0) & 0xffffffffL);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Streams 2.16.2 endpoint contract changed", error);
        }
    }

    private static Object call(Object target, String name) { return call(target, name, new Class<?>[0]); }

    private static Object call(Object target, String name, Class<?>[] parameterTypes, Object... args) {
        try {
            return target.getClass().getMethod(name, parameterTypes).invoke(target, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException("Streams 2.16.2 graph call failed: " + name, cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Streams 2.16.2 graph contract changed: " + name, error);
        }
    }
}
