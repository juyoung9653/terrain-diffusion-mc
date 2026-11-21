package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes a very short, dry inland continuation after a modeled marsh pond.
 *
 * <p>The native graph is rebuilt with its sink list filtered, so the removed
 * native sink cannot spawn a second terminal at the old dry endpoint.</p>
 */
public final class StreamsMarshMouthNetwork2173 {
    private static final double MAX_DRY_TAIL = 20.0;
    private static final double MAX_GRADE = 0.6;

    private StreamsMarshMouthNetwork2173() { }

    public static Object adaptFresh(Object network, Object domain, Object config) {
        return finish(adapt(network, domain, config), domain, config);
    }

    public static Object adaptLoaded(Object engine, Object key, Object network) {
        if (network == null || !Streams2162NetworkAccess.applies(network)) return network;
        try {
            Object config = engine.getClass().getMethod("config").invoke(engine);
            if (!StreamsNaturalRiverAdapter.active(config)
                    || StreamsNaturalRiverAdapter.version(config) != Streams2171NetworkAdapter.PROFILE) {
                return network;
            }
            Object lattice = engine.getClass().getMethod("lattice").invoke(engine);
            Class<?> domainType = Class.forName(
                    "dev.streamsreflowing.core.plate.ProprietaryNiceJohn_001", false,
                    engine.getClass().getClassLoader());
            Constructor<?> constructor = domainType.getConstructor(lattice.getClass(), key.getClass());
            Object domain = constructor.newInstance(lattice, key);
            return finish(adapt(network, domain, config), domain, config);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Streams 2.17.3 loaded-network domain changed", failure);
        }
    }

    /** Applies the shared dry-grade pass even when no mouth clipping was admitted. */
    private static Object finish(Object network, Object domain, Object config) {
        return StreamsDryGrade2173.refine(network, domain, config);
    }

    private static Object adapt(Object network, Object domain, Object config) {
        if (network == null
                || !StreamsNaturalRiverAdapter.active(config)
                || StreamsNaturalRiverAdapter.version(config) != Streams2171NetworkAdapter.PROFILE
                || !Streams2162NetworkAccess.applies(network)) return network;

        List<?> segments = Streams2162NetworkAccess.segments(network);
        if (segments.size() < 2) return network;

        Graph graph = Graph.of(segments);
        WaterField water = new WaterField(config);
        Map<Integer, Cut> cuts = new HashMap<>();
        Set<Integer> removed = new HashSet<>();
        Set<Long> displacedSinks = new HashSet<>();
        for (int sink : graph.sinks()) {
            Cut cut = dryPondCut(sink, segments, graph, network, domain, water);
            if (cut == null || overlaps(cut, cuts, removed)) continue;
            cuts.put(cut.segment(), cut);
            removed.addAll(cut.downstream());
            displacedSinks.add(Streams2162NetworkAccess.endpoint(segments.get(sink), "proprietary2", "seeAiNotice2"));
        }
        if (cuts.isEmpty() && removed.isEmpty()) return network;

        List<Object> retained = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            Cut cut = cuts.get(i);
            if (cut != null) retained.add(cut.rebind(segments.get(i)));
            else if (!removed.contains(i)) retained.add(segments.get(i));
        }
        // Preserve every unrelated native sink, including unmatched protected terminals.
        return Streams2162NetworkAccess.reassemble(network, retained, config, displacedSinks);
    }

    private static boolean overlaps(Cut cut, Map<Integer, Cut> cuts, Set<Integer> removed) {
        if (cuts.containsKey(cut.segment()) || removed.contains(cut.segment())) return true;
        for (int downstream : cut.downstream()) {
            if (cuts.containsKey(downstream) || removed.contains(downstream)) return true;
        }
        return false;
    }

    /** Finds a modeled-pond interior while walking no more than twenty dry blocks upstream. */
    private static Cut dryPondCut(int sink, List<?> segments, Graph graph, Object network,
                                  Object domain, WaterField water) {
        double downstreamLength = 0.0;
        int current = sink;
        List<Integer> downstream = new ArrayList<>();
        while (true) {
            Object segment = segments.get(current);
            double length = length(segment);
            if (!(length > 0.0) || !Double.isFinite(length)) return null;

            double allowed = MAX_DRY_TAIL - downstreamLength;
            if (allowed <= 0.0) return null;
            double start = Math.max(0.0, 1.0 - allowed / length);
            double pondT = lastPondInterior(segment, start, water);
            if (pondT >= 0.0) {
                double tailLength = downstreamLength + (1.0 - pondT) * length;
                if (tailLength < 1.0 || tailLength > MAX_DRY_TAIL
                        || !unbranchedStart(current, graph)
                        || !safeInland(current, pondT, downstream, segments, network, domain, water)) {
                    return null;
                }
                return new Cut(current, pondT, List.copyOf(downstream));
            }

            downstreamLength += length;
            if (downstreamLength >= MAX_DRY_TAIL || !unbranchedStart(current, graph)) return null;
            downstream.add(current);
            current = graph.onlyIncoming(current);
            if (current < 0) return null;
        }
    }

    /** Samples only the already bounded tail window, from the dry endpoint toward the pond. */
    private static double lastPondInterior(Object segment, double start, WaterField water) {
        double length = length(segment);
        int samples = Math.max(1, (int) Math.ceil((1.0 - start) * length));
        for (int step = 0; step <= samples; step++) {
            double t = 1.0 - (1.0 - start) * step / samples;
            int x = (int) Math.round(lerp(n(segment, "x1"), n(segment, "x2"), t));
            int z = (int) Math.round(lerp(n(segment, "z1"), n(segment, "z2"), t));
            if (substantialPond(water, x, z)) return t;
        }
        return -1.0;
    }

    private static boolean substantialPond(WaterField water, int x, int z) {
        int top = water.level(x, z);
        if (top <= 0) return false;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (water.level(x + dx, z + dz) != top) return false;
        }
        return true;
    }

    private static boolean unbranchedStart(int segment, Graph graph) {
        return graph.incomingCount(segment) <= 1 && graph.outgoingCount(segment) == 1;
    }

    private static boolean safeInland(int cutSegment, double cutT, List<Integer> downstream,
                                      List<?> segments, Object network, Object domain, WaterField water) {
        List<Part> parts = new ArrayList<>(downstream.size() + 1);
        parts.add(new Part(cutSegment, cutT, 1.0));
        for (int index : downstream) parts.add(new Part(index, 0.0, 1.0));
        for (Part part : parts) {
            Object segment = segments.get(part.segment());
            double segmentLength = length(segment);
            if (Math.abs(n(segment, "y2") - n(segment, "y1")) / segmentLength > MAX_GRADE) return false;
            int samples = Math.max(1, (int) Math.ceil((part.to() - part.from()) * segmentLength));
            for (int i = 0; i <= samples; i++) {
                double t = lerp(part.from(), part.to(), (double) i / samples);
                int x = (int) Math.round(lerp(n(segment, "x1"), n(segment, "x2"), t));
                int z = (int) Math.round(lerp(n(segment, "z1"), n(segment, "z2"), t));
                if (!Streams2162NetworkAccess.contains(domain, x, z)
                        || Streams2162NetworkAccess.borderDistance(domain, x, z) <= 8.0
                        || Streams2162NetworkAccess.lakeNear(network, x, z, 2) != null
                        || water.coastal(x, z)) return false;
            }
        }
        return true;
    }

    private static double n(Object segment, String field) {
        return StreamsRecordAccess.number(segment, field);
    }

    private static double length(Object segment) {
        return Math.hypot(n(segment, "x2") - n(segment, "x1"), n(segment, "z2") - n(segment, "z1"));
    }

    private static double lerp(double start, double end, double fraction) {
        return start + (end - start) * fraction;
    }

    private record Part(int segment, double from, double to) { }

    private record Cut(int segment, double t, List<Integer> downstream) {
        Object rebind(Object original) {
            return StreamsRecordAccess.copy(original, Map.of(
                    "x2", lerp(n(original, "x1"), n(original, "x2"), t),
                    "z2", lerp(n(original, "z1"), n(original, "z2"), t),
                    "y2", lerp(n(original, "y1"), n(original, "y2"), t),
                    "w2", lerp(n(original, "w1"), n(original, "w2"), t)));
        }
    }

    private record Node(long x, long z) { }

    private record Graph(Map<Node, List<Integer>> incoming, Map<Node, List<Integer>> outgoing,
                         List<?> segments) {
        static Graph of(List<?> segments) {
            Map<Node, List<Integer>> incoming = new HashMap<>();
            Map<Node, List<Integer>> outgoing = new HashMap<>();
            for (int i = 0; i < segments.size(); i++) {
                Object segment = segments.get(i);
                incoming.computeIfAbsent(node(segment, "x2", "z2"), ignored -> new ArrayList<>()).add(i);
                outgoing.computeIfAbsent(node(segment, "x1", "z1"), ignored -> new ArrayList<>()).add(i);
            }
            return new Graph(incoming, outgoing, segments);
        }

        List<Integer> sinks() {
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                Node end = node(segments.get(i), "x2", "z2");
                if (incoming.getOrDefault(end, List.of()).size() == 1
                        && outgoing.getOrDefault(end, List.of()).isEmpty()) result.add(i);
            }
            return result;
        }

        int incomingCount(int segment) {
            return incoming.getOrDefault(node(segments.get(segment), "x1", "z1"), List.of()).size();
        }

        int outgoingCount(int segment) {
            return outgoing.getOrDefault(node(segments.get(segment), "x1", "z1"), List.of()).size();
        }

        int onlyIncoming(int segment) {
            List<Integer> before = incoming.getOrDefault(node(segments.get(segment), "x1", "z1"), List.of());
            return before.size() == 1 ? before.getFirst() : -1;
        }

        private static Node node(Object segment, String x, String z) {
            return new Node(Math.round(n(segment, x) * 16.0), Math.round(n(segment, z) * 16.0));
        }
    }

    private static final class WaterField {
        private final LocalTerrainProvider provider;
        private final int size = TerrainDiffusionConfig.tileSize();
        private final Map<Long, LocalTerrainProvider.HeightmapData> marine = new HashMap<>();
        private final Map<Long, LocalTerrainProvider.HeightmapData> wet = new HashMap<>();

        WaterField(Object config) {
            provider = StreamsNaturalRiverAdapter.providerForNaturalGraph(config);
        }

        boolean coastal(int x, int z) {
            return StreamsMarineSampling.within16(x, z, marine, provider);
        }

        int level(int x, int z) {
            int x0 = Math.floorDiv(x, size) * size;
            int z0 = Math.floorDiv(z, size) * size;
            long key = ((long) x0 << 32) ^ (z0 & 0xffffffffL);
            LocalTerrainProvider.HeightmapData data = wet.computeIfAbsent(key,
                    ignored -> provider.fetchHeightmap(z0, x0, z0 + size, x0 + size));
            return data == null || data.wetlandWaterLevels == null ? 0
                    : data.wetlandWaterLevels[Math.floorMod(z, size)][Math.floorMod(x, size)];
        }
    }
}



