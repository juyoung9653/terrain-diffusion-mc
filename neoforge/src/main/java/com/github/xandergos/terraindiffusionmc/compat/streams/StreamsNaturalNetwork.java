package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Rebuilds native indices after bounded TD grade correction, before native persistence. */
public final class StreamsNaturalNetwork {
    private StreamsNaturalNetwork() { }

    public static Object refine(Object network, Object terrain, Object config) {
        try {
            Class<?> type = network.getClass();
            List<?> segments = Streams2162NetworkAccess.applies(network)
                    ? Streams2162NetworkAccess.segments(network)
                    : (List<?>) type.getMethod("segments").invoke(network);
            if (segments.isEmpty()) return network;
            List<Reach> input = reaches(segments);
            ClassLoader loader = type.getClassLoader();
            boolean modern = Streams2162NetworkAccess.applies(network);
            Method height = Class.forName("dev.streamsreflowing.core.terrain.TerrainSampler", false, loader)
                    .getMethod(modern ? "noDecompilePermission1" : "groundHeight", int.class, int.class);
            Method lakeNear = modern ? null
                    : type.getMethod("lakeNear", int.class, int.class, int.class);
            List<Reach> output = NaturalRiverMorphology.refine(input, (x, z) -> {
                try {
                    if (modern
                            ? Streams2162NetworkAccess.lakeNear(network, x, z, 2) != null
                            : lakeNear.invoke(network, x, z, 2) != null) return Double.NaN;
                    return ((Number) height.invoke(terrain, x, z)).doubleValue();
                } catch (ReflectiveOperationException e) { throw new IllegalStateException("TD river terrain sampling failed", e); }
            }, (int) n(config, "seaLevel"));
            if (output.equals(input)) return network;
            List<Object> updated = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                Reach reach = output.get(i);
                updated.add(reach.equals(input.get(i)) ? segments.get(i) : StreamsRecordAccess.copy(segments.get(i),
                        Map.of("y1", reach.y1(), "y2", reach.y2())));
            }
            return reassemble(network, updated, config);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Streams natural-network adapter layout", e);
        }
    }

    static List<Reach> reaches(List<?> segments) {
        List<Reach> result = new ArrayList<>(segments.size());
        for (Object s : segments) result.add(new Reach(n(s, "x1"), n(s, "z1"), n(s, "y1"), n(s, "w1"),
                n(s, "x2"), n(s, "z2"), n(s, "y2"), n(s, "w2"), n(s, "sp1"), n(s, "sp2")));
        return result;
    }

    /** Rebuilds the native index while preserving lakes/relief; selection may request sink filtering. */
    static Object reassemble(Object network, List<?> segments, Object config) { return reassemble(network, segments, config, false); }

    static Object reassemble(Object network, List<?> segments, Object config, boolean filterSinks) {
        try {
            if (Streams2162NetworkAccess.applies(network)) {
                return Streams2162NetworkAccess.reassemble(network, segments, config, filterSinks);
            }
            Class<?> type = network.getClass();
            Object key = type.getMethod("key").invoke(network);
            Object lakes = type.getMethod("lakes").invoke(network);
            Class<?> tracer = Class.forName("dev.streamsreflowing.core.river.NetworkTracer", false, type.getClassLoader());
            for (Method assemble : tracer.getMethods()) if (assemble.getName().equals("assemble") && assemble.getParameterCount() == 6) {
                return assemble.invoke(null, key, segments, lakes, filterSinks ? retainedSinks(network, segments) : type.getMethod("sinks").invoke(network),
                        type.getMethod("relief").invoke(network), config);
            }
            return tracer.getMethod("assemble", key.getClass(), List.class, List.class, config.getClass())
                    .invoke(null, key, segments, lakes, config);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Streams natural-network adapter layout", e);
        }
    }

    /**
     * Selection deletes segments but does not reinterpret native sink topology.
     * Retain each original sink, in native order, only while some retained segment
     * still ends at its rounded native endpoint.  This also preserves zero-length
     * placeholders that represent protected or otherwise unknown drainage.
     */
    private static List<?> retainedSinks(Object network, List<?> segments) throws ReflectiveOperationException {
        List<?> sinks = (List<?>) network.getClass().getMethod("sinks").invoke(network);
        if (sinks.isEmpty() || segments.isEmpty()) return List.of();
        java.util.Set<Long> endpoints = new java.util.HashSet<>();
        for (Object segment : segments) endpoints.add(key(n(segment, "x2"), n(segment, "z2")));
        List<Object> result = new ArrayList<>();
        for (Object sink : sinks) {
            long sinkKey = key(number(sink, "x"), number(sink, "z"));
            if (endpoints.contains(sinkKey)) result.add(sink);
        }
        return result;
    }
    private static long key(double x, double z) { return (Math.round(x * 16.0) & 0xffffffffL) << 32 | (Math.round(z * 16.0) & 0xffffffffL); }
    static double n(Object record, String name) { return StreamsRecordAccess.number(record, name); }
    private static double number(Object object, String name) throws ReflectiveOperationException {
        return ((Number) object.getClass().getMethod(name).invoke(object)).doubleValue();
    }
}








