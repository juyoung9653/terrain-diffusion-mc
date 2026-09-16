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
            List<?> segments = (List<?>) network.getClass().getMethod("segments").invoke(network);
            var reaches = StreamsNaturalNetwork.reaches(segments);
            Class<?> domainType = Class.forName("dev.streamsreflowing.core.plate.PlateDomain", false, network.getClass().getClassLoader());
            var contains = domainType.getMethod("contains", double.class, double.class);
            var borderDistance = domainType.getMethod("borderDistance", double.class, double.class);
            var lakeNear = network.getClass().getMethod("lakeNear", int.class, int.class, int.class);
            double border = StreamsNaturalNetwork.n(config, "borderMargin")
                    + Math.max(0, StreamsNaturalNetwork.n(config, "connectRadius")) + 2 * StreamsNaturalNetwork.n(config, "gridSpacing");
            double plate = StreamsNaturalNetwork.n(config, "plateScale");
            var selection = NaturalRiverHierarchy.select(reaches,
                    r -> StreamsWatershedNetwork.protectedReach(network, domain, contains, borderDistance, lakeNear, border, r),
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
}
