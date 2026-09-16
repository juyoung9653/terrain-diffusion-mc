package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology.Reach;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverWatersheds;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverWatersheds.Selection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.BiPredicate;
import java.util.logging.Logger;

/** Pure V3 whole-component selection; the caller supplies native objects reflectively. */
public final class StreamsWatershedNetwork {
    private static final Logger LOG = Logger.getLogger(StreamsWatershedNetwork.class.getName());
    private StreamsWatershedNetwork() { }

    public static Selection select(List<Reach> reaches, Predicate<Reach> protectedReach,
                                   BiPredicate<Integer, Integer> marine, int seaLevel, double plateScale) {
        if (protectedReach == null || marine == null) throw new NullPointerException();
        return NaturalRiverWatersheds.select(reaches, new NaturalRiverWatersheds.Guard() {
            @Override public boolean protectedReach(Reach reach) { return protectedReach.test(reach); }
            @Override public boolean marineTerminal(Reach reach) {
                return reach.y2() <= seaLevel + 1.0
                        && marine.test((int) Math.round(reach.x2()), (int) Math.round(reach.z2()));
            }
        }, plateScale * .375, plateScale * .0625);
    }

    /** Reflective bridge used by the adapter and harness; the caller supplies its bounded marine predicate. */
    public static Object select(Object network, Object domain, Object terrain, Object config,
                                BiPredicate<Integer, Integer> marine) {
        if (marine == null) throw new NullPointerException("marine");
        try {
            List<?> segments = (List<?>) network.getClass().getMethod("segments").invoke(network);
            if (segments.isEmpty()) return network;
            List<Reach> reaches = StreamsNaturalNetwork.reaches(segments);
            ClassLoader loader = network.getClass().getClassLoader();
            var domainType = Class.forName("dev.streamsreflowing.core.plate.PlateDomain", false, loader);
            var contains = domainType.getMethod("contains", double.class, double.class);
            var borderDistance = domainType.getMethod("borderDistance", double.class, double.class);
            var lakeNear = network.getClass().getMethod("lakeNear", int.class, int.class, int.class);
            // An adjacent plate's native connector may reach inside this domain.
            double border = StreamsNaturalNetwork.n(config, "borderMargin")
                    + Math.max(0, StreamsNaturalNetwork.n(config, "connectRadius"))
                    + 2.0 * StreamsNaturalNetwork.n(config, "gridSpacing");
            int seaLevel = (int) StreamsNaturalNetwork.n(config, "seaLevel");
            double plateScale = StreamsNaturalNetwork.n(config, "plateScale");
            Predicate<Reach> protectedReach = reach -> protectedReach(network, domain, contains, borderDistance, lakeNear, border, reach);
            Selection selection = select(reaches, protectedReach, marine, seaLevel, plateScale);
            if (selection.retainedIndices().size() == segments.size()) return network;
            List<Object> retained = new java.util.ArrayList<>(selection.retainedIndices().size());
            for (int index : selection.retainedIndices()) retained.add(segments.get(index));
            LOG.info(() -> "Natural river v3 plate selection: " + segments.size() + " -> " + retained.size() + " segments, rejected " + selection.rejectedBasins() + " coastal components, pruned " + selection.prunedHeadwaters() + " headwater twigs");
            return StreamsNaturalNetwork.reassemble(network, retained, config, true);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LOG.warning("Natural river v3 selection failed open; preserving native network: " + failure);
            return network;
        }
    }

    static boolean protectedReach(Object network, Object domain, java.lang.reflect.Method contains,
                                          java.lang.reflect.Method borderDistance, java.lang.reflect.Method lakeNear,
                                          double safeBorder, Reach reach) {
        double[] xs = {reach.x1(), reach.x2(), (reach.x1() + reach.x2()) * .5};
        double[] zs = {reach.z1(), reach.z2(), (reach.z1() + reach.z2()) * .5};
        try {
            for (int i = 0; i < xs.length; i++) {
                if (!((Boolean) contains.invoke(domain, xs[i], zs[i]))
                        || ((Number) borderDistance.invoke(domain, xs[i], zs[i])).doubleValue() <= safeBorder
                        || lakeNear.invoke(network, (int) Math.round(xs[i]), (int) Math.round(zs[i]), 2) != null) return true;
            }
            return false;
        } catch (ReflectiveOperationException failure) { return true; }
    }
}



