package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverPlanform;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** V11 only: gently bend long straight reaches before native indexing and persistence. */
public final class StreamsPlanformNetwork {
    private StreamsPlanformNetwork() { }

    public static Object refine(Object network, Object domain, NaturalRiverMorphology.Ground ground, Object config,
                                long seed, int profile) {
        if (profile < 11) return network;
        if (!Streams2162NetworkAccess.applies(network)) {
            throw new IllegalStateException("Natural river V11 requires Streams 2.16.2");
        }
        double spacing = StreamsRecordAccess.number(config, "gridSpacing");
        List<?> original = Streams2162NetworkAccess.segments(network);
        List<Object> updated = reshapeSegments(original, ground,
                (x, z, radius) -> Streams2162NetworkAccess.contains(domain, x, z)
                && Streams2162NetworkAccess.borderDistance(domain, x, z) > radius + 8.0
                && Streams2162NetworkAccess.lakeNear(network, (int) Math.floor(x), (int) Math.floor(z),
                        (int) Math.ceil(radius / Math.max(1.0, spacing)) + 1) == null,
                seed, (int) StreamsRecordAccess.number(config, "seaLevel"),
                (int) Math.round(StreamsRecordAccess.number(config, "ditchInset")));
        if (updated.equals(original)) return network;
        return Streams2162NetworkAccess.reassemble(network, updated, config, false);
    }

    static List<Object> reshapeSegments(List<?> original, NaturalRiverMorphology.Ground ground,
                                       NaturalRiverPlanform.Corridor corridor, long seed, int seaLevel) {
        return reshapeSegments(original, ground, corridor, seed, seaLevel, 0);
    }

    static List<Object> reshapeSegments(List<?> original, NaturalRiverMorphology.Ground ground,
                                       NaturalRiverPlanform.Corridor corridor, long seed, int seaLevel, int waterInset) {
        var input = StreamsNaturalNetwork.reaches(original);
        var output = NaturalRiverPlanform.refine(input, ground, corridor, seed, seaLevel, waterInset);
        List<Object> updated = new ArrayList<>(output.size());
        for (var piece : output) {
            var reach = piece.reach();
            Object source = original.get(piece.sourceIndex());
            updated.add(reach.equals(input.get(piece.sourceIndex())) ? source : StreamsRecordAccess.copy(source,
                    Map.of("x1", reach.x1(), "z1", reach.z1(), "y1", reach.y1(), "w1", reach.w1(),
                            "x2", reach.x2(), "z2", reach.z2(), "y2", reach.y2(), "w2", reach.w2(),
                            "sp1", reach.speed1(), "sp2", reach.speed2())));
        }
        return List.copyOf(updated);
    }
}
