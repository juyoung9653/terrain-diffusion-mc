package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverHierarchy;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Version-four hierarchy and width response; native routing and flow states remain native. */
public final class StreamsHierarchyNetwork {
    private static final Logger LOG = Logger.getLogger(StreamsHierarchyNetwork.class.getName());
    private StreamsHierarchyNetwork() { }

    public static Object select(Object network, Object domain, Object config) {
        try {
            boolean modern = Streams2162NetworkAccess.applies(network);
            List<?> segments = modern ? Streams2162NetworkAccess.segments(network)
                    : (List<?>) network.getClass().getMethod("segments").invoke(network);
            var reaches = StreamsNaturalNetwork.reaches(segments);
            Class<?> domainType = modern ? null : Class.forName("dev.streamsreflowing.core.plate.PlateDomain", false, network.getClass().getClassLoader());
            var contains = modern ? null : domainType.getMethod("contains", double.class, double.class);
            var borderDistance = modern ? null : domainType.getMethod("borderDistance", double.class, double.class);
            var lakeNear = modern ? null : network.getClass().getMethod("lakeNear", int.class, int.class, int.class);
            double border = StreamsNaturalNetwork.n(config, "borderMargin")
                    + Math.max(0, StreamsNaturalNetwork.n(config, "connectRadius")) + 2 * StreamsNaturalNetwork.n(config, "gridSpacing");
            double plate = StreamsNaturalNetwork.n(config, "plateScale");
            var selection = NaturalRiverHierarchy.select(reaches,
                    r -> modern ? protectedReach2162(network, domain, border, r)
                            : StreamsWatershedNetwork.protectedReach(network, domain, contains, borderDistance, lakeNear, border, r),
                    plate * .15, plate * .05);
            List<Object> retained = new ArrayList<>(selection.retainedIndices().size());
            for (int index : selection.retainedIndices()) {
                var reach = reaches.get(index);
                retained.add(StreamsRecordAccess.copy(segments.get(index), Map.of(
                        "w1", NaturalRiverProfile.narrowHalfWidthV4(reach.w1()),
                        "w2", NaturalRiverProfile.narrowHalfWidthV4(reach.w2()))));
            }
            LOG.info("Natural river v4 hierarchy: " + segments.size() + " -> " + retained.size()
                    + " segments; rejected sources=" + selection.rejectedSources());
            return StreamsNaturalNetwork.reassemble(network, retained, config, true);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Natural river v4 hierarchy adapter failed", failure);
        }
    }

    private static boolean protectedReach2162(Object network, Object domain, double margin,
                                              com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach reach) {
        double[] xs = {reach.x1(), reach.x2(), (reach.x1() + reach.x2()) * .5};
        double[] zs = {reach.z1(), reach.z2(), (reach.z1() + reach.z2()) * .5};
        for (int i = 0; i < xs.length; i++) if (!Streams2162NetworkAccess.contains(domain, xs[i], zs[i])
                || Streams2162NetworkAccess.borderDistance(domain, xs[i], zs[i]) <= margin
                || Streams2162NetworkAccess.lakeNear(network, (int) Math.round(xs[i]), (int) Math.round(zs[i]), 2) != null) return true;
        return false;
    }
}
