package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalLakeLevels;
import java.util.ArrayList;
import java.util.List;

/** Audited 2.13.8 lake constructor; invoked at assembly, including cached graph loads. */
public final class StreamsLakeLevels {
    private StreamsLakeLevels() { }

    public static List<?> resolve(List<?> lakes, Object config) {
        int version = StreamsNaturalRiverAdapter.version(config);
        if (version < 10 || lakes.size() < 2) return lakes;
        try {
            var type = lakes.getFirst().getClass();
            var level = type.getMethod("level");
            var depth = type.getMethod("digDepth");
            var spacing = type.getMethod("spacing");
            var x = type.getMethod("cellMinX");
            var z = type.getMethod("cellMinZ");
            var width = type.getMethod("cellsWide");
            var height = type.getMethod("cellsHigh");
            var mask = type.getMethod("mask");
            List<NaturalLakeLevels.Lake> inputs = new ArrayList<>(lakes.size());
            for (Object lake : lakes) inputs.add(new NaturalLakeLevels.Lake(
                    (int) level.invoke(lake), (int) depth.invoke(lake), (int) spacing.invoke(lake),
                    (int) x.invoke(lake), (int) z.invoke(lake), (int) width.invoke(lake),
                    (int) height.invoke(lake), (long[]) mask.invoke(lake)));
            int[] levels = NaturalLakeLevels.resolve(version, inputs);
            List<Object> result = null;
            var constructor = type.getConstructor(int.class, int.class, int.class, int.class,
                    int.class, int.class, int.class, long[].class);
            for (int i = 0; i < levels.length; i++) {
                var lake = inputs.get(i);
                if (levels[i] == lake.level()) continue;
                if (result == null) result = new ArrayList<>(lakes);
                result.set(i, constructor.newInstance(levels[i], lake.digDepth(), lake.spacing(),
                        lake.minX(), lake.minZ(), lake.width(), lake.height(), lake.mask()));
            }
            return result == null ? lakes : result;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Streams nested-lake adapter", failure);
        }
    }
}
