package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverConfluenceWidths;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import org.slf4j.LoggerFactory;

/** V7 widths respond to retained tributaries before native indexing and carving. */
public final class StreamsConfluenceNetwork {
    private StreamsConfluenceNetwork() { }

    public static Object refine(Object network, Object domain, Object config) {
        if (StreamsNaturalRiverAdapter.version(config) < 7) return network;
        try {
            boolean modern = Streams2162NetworkAccess.applies(network);
            var segments = modern ? Streams2162NetworkAccess.segments(network)
                    : (java.util.List<?>) network.getClass().getMethod("segments").invoke(network);
            var input = StreamsNaturalNetwork.reaches(segments);
            Class<?> domainType = modern ? null : Class.forName("dev.streamsreflowing.core.plate.PlateDomain", false,
                    network.getClass().getClassLoader());
            Method contains = modern ? null : domainType.getMethod("contains", double.class, double.class);
            Method borderDistance = modern ? null : domainType.getMethod("borderDistance", double.class, double.class);
            Method lakeNear = modern ? null : network.getClass().getMethod("lakeNear", int.class, int.class, int.class);
            double margin = StreamsNaturalNetwork.n(config, "borderMargin")
                    + Math.max(0, StreamsNaturalNetwork.n(config, "connectRadius"))
                    + 2 * StreamsNaturalNetwork.n(config, "gridSpacing");
            var output = NaturalRiverConfluenceWidths.refine(input, reach -> {
                int steps = Math.max(1, (int) Math.ceil(Math.hypot(
                        reach.x2() - reach.x1(), reach.z2() - reach.z1()) / 8));
                try {
                    for (int i = 0; i <= steps; i++) {
                        double t = (double) i / steps;
                        double x = reach.x1() + (reach.x2() - reach.x1()) * t;
                        double z = reach.z1() + (reach.z2() - reach.z1()) * t;
                        boolean protectedCell = modern
                                ? !Streams2162NetworkAccess.contains(domain, x, z)
                                        || Streams2162NetworkAccess.borderDistance(domain, x, z) <= margin
                                        || Streams2162NetworkAccess.lakeNear(network, (int) Math.floor(x), (int) Math.floor(z), 16) != null
                                : !(Boolean) contains.invoke(domain, x, z)
                                        || ((Number) borderDistance.invoke(domain, x, z)).doubleValue() <= margin
                                        || lakeNear.invoke(network, (int) Math.floor(x), (int) Math.floor(z), 16) != null;
                        if (protectedCell) {
                            return true;
                        }
                    }
                    return false;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot inspect protected river reach", e);
                }
            });
            if (output.equals(input)) return network;
            if (output.size() != input.size()) throw new IllegalStateException("Width refinement changed topology");
            var updated = new ArrayList<Object>(segments.size());
            int changed = 0;
            for (int i = 0; i < segments.size(); i++) {
                var before = input.get(i);
                var after = output.get(i);
                if (!Double.isFinite(after.w1()) || !Double.isFinite(after.w2())
                        || after.w1() < 0 || after.w2() < 0
                        || after.w1() > before.w1() || after.w2() > before.w2()) {
                    throw new IllegalStateException("Width refinement exceeded native capacity");
                }
                if (before.w1() == after.w1() && before.w2() == after.w2()) updated.add(segments.get(i));
                else {
                    changed++;
                    updated.add(StreamsRecordAccess.copy(segments.get(i),
                            Map.of("w1", after.w1(), "w2", after.w2())));
                }
            }
            LoggerFactory.getLogger("TerrainDiffusion/NaturalRivers").info(
                    "Natural river v7 confluence widths: {} of {} retained segments changed", changed, segments.size());
            return StreamsNaturalNetwork.reassemble(network, updated, config);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Streams confluence-width adapter", e);
        }
    }
}
