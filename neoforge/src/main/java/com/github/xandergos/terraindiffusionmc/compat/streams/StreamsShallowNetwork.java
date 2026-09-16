package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverShallowProfile;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import org.slf4j.LoggerFactory;

/** V6 fits shallow tributary grades before native indexing and persistence. */
public final class StreamsShallowNetwork {
    private StreamsShallowNetwork() { }

    public static Object refine(Object network, Object domain, Object config) {
        if (StreamsNaturalRiverAdapter.version(config) != 6) return network;
        try {
            var segments = (java.util.List<?>) network.getClass().getMethod("segments").invoke(network);
            var input = StreamsNaturalNetwork.reaches(segments);
            var originalTerrain = StreamsNaturalRiverAdapter.originalTerrain(config);
            ClassLoader loader = network.getClass().getClassLoader();
            Class<?> domainType = Class.forName("dev.streamsreflowing.core.plate.PlateDomain", false, loader);
            Method contains = domainType.getMethod("contains", double.class, double.class);
            Method borderDistance = domainType.getMethod("borderDistance", double.class, double.class);
            Method lakeNear = network.getClass().getMethod("lakeNear", int.class, int.class, int.class);
            double margin = StreamsNaturalNetwork.n(config, "borderMargin")
                    + Math.max(0, StreamsNaturalNetwork.n(config, "connectRadius"))
                    + 2 * StreamsNaturalNetwork.n(config, "gridSpacing");
            java.util.Set<Long> pinned = new java.util.HashSet<>();
            Map<Long, Double> groundCache = new java.util.HashMap<>();
            for (var reach : input) {
                if (!Double.isFinite(reach.x1()) || !Double.isFinite(reach.z1())
                        || !Double.isFinite(reach.x2()) || !Double.isFinite(reach.z2())) return network;
                if (reach.w1() >= 4 && reach.w2() >= 4) continue;
                int steps = Math.max(1, (int) Math.ceil(Math.hypot(reach.x2() - reach.x1(), reach.z2() - reach.z1()) / 8));
                for (int i = 0; i <= steps; i++) {
                    double t = (double) i / steps;
                    double x = reach.x1() + (reach.x2() - reach.x1()) * t;
                    double z = reach.z1() + (reach.z2() - reach.z1()) * t;
                    if (!(Boolean) contains.invoke(domain, x, z)
                            || ((Number) borderDistance.invoke(domain, x, z)).doubleValue() <= margin
                            || lakeNear.invoke(network, (int) Math.floor(x), (int) Math.floor(z), 16) != null) {
                        pinned.add(cell((int) Math.floor(reach.x1()), (int) Math.floor(reach.z1())));
                        pinned.add(cell((int) Math.floor(reach.x2()), (int) Math.floor(reach.z2())));
                        break;
                    }
                }
            }
            // Match the existing width-independent planner inset, leaving native
            // global routing and lake-aware inset blending untouched.
            int inset = (int) Math.round(StreamsNaturalNetwork.n(config, "ditchInset"));
            var output = NaturalRiverShallowProfile.refine(input, (x, z) -> {
                try {
                    if (pinned.contains(cell(x, z)) || !(Boolean) contains.invoke(domain, (double) x, (double) z)
                            || ((Number) borderDistance.invoke(domain, (double) x, (double) z)).doubleValue() <= margin
                            || lakeNear.invoke(network, x, z, 16) != null) return Double.NaN;
                    double ground = Double.POSITIVE_INFINITY;
                    // The lowest local undisturbed terrain constrains the water
                    // target; a high centre voxel must not perch water over a bank.
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        long key = cell(x + dx, z + dz);
                        Double sample = groundCache.get(key);
                        if (sample == null) {
                            // Real ground delegates to generator.getBaseHeight,
                            // bypassing the routing sampler's filtered/calibrated grade.
                            sample = originalTerrain.height(x + dx, z + dz);
                            groundCache.put(key, sample);
                        }
                        if (!Double.isFinite(sample)) return Double.NaN;
                        ground = Math.min(ground, sample);
                    }
                    return ground;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot sample shallow river terrain", e);
                }
            }, (int) StreamsNaturalNetwork.n(config, "seaLevel"), inset);
            if (output.equals(input)) return network;
            var updated = new ArrayList<Object>(segments.size());
            int changed = 0;
            double largest = 0;
            for (int i = 0; i < segments.size(); i++) {
                var before = input.get(i);
                var after = output.get(i);
                if (before.equals(after)) updated.add(segments.get(i));
                else {
                    changed++;
                    largest = Math.max(largest, Math.max(after.y1() - before.y1(), after.y2() - before.y2()));
                    updated.add(StreamsRecordAccess.copy(segments.get(i), Map.of("y1", after.y1(), "y2", after.y2())));
                }
            }
            LoggerFactory.getLogger("TerrainDiffusion/NaturalRivers").info(
                    "Natural river v6 shallow grades: {} changed segments; maximum lift {}", changed, largest);
            return StreamsNaturalNetwork.reassemble(network, updated, config);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Streams shallow-network adapter", e);
        }
    }

    private static long cell(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
