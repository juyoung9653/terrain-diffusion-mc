package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverShallowProfile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;

/**
 * Applies the V14 shallow-grade correction from TD's model surface, never from
 * a persisted Streams grade.  Any shaped wetland-water cell is a hard pin: the
 * wetland routing policy owns that reach, while dry upstream reaches may ramp
 * naturally toward it.
 */
public final class StreamsDryGrade2173 {
    private StreamsDryGrade2173() { }

    public static Object refine(Object network, Object domain, Object config) {
        if (network == null || !Streams2162NetworkAccess.applies(network)
                || !StreamsNaturalRiverAdapter.active(config)
                || StreamsNaturalRiverAdapter.version(config) != Streams2171NetworkAdapter.PROFILE) return network;
        List<?> segments = Streams2162NetworkAccess.segments(network);
        List<NaturalRiverMorphology.Reach> input = StreamsNaturalNetwork.reaches(segments);
        if (input.isEmpty()) return network;
        ModelGround model = new ModelGround(StreamsNaturalRiverAdapter.providerForNaturalGraph(config));
        int inset = (int) Math.round(StreamsNaturalNetwork.n(config, "ditchInset"));
        double margin = StreamsNaturalNetwork.n(config, "borderMargin")
                + Math.max(0, StreamsNaturalNetwork.n(config, "connectRadius"))
                + 2 * StreamsNaturalNetwork.n(config, "gridSpacing");
        NaturalRiverMorphology.Ground guarded = (x, z) -> {
            if (!Streams2162NetworkAccess.contains(domain, x, z)
                    || Streams2162NetworkAccess.borderDistance(domain, x, z) <= margin
                    || Streams2162NetworkAccess.lakeNear(network, x, z, 16) != null) return Double.NaN;
            return model.height(x, z);
        };
        List<NaturalRiverMorphology.Reach> dryInput = new ArrayList<>();
        List<Integer> dryIndexes = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            NaturalRiverMorphology.Reach reach = input.get(i);
            // Wide reaches cannot receive a shallow uplift, so retain them as
            // native constraints without sampling a potentially expensive tile.
            // Only modeled wetland water splits connectivity; coast/lake/domain
            // protection stays in the solver and therefore remains a hard pin.
            if ((reach.w1() >= 4 && reach.w2() >= 4) || !wetReach(reach, model)) {
                dryIndexes.add(i);
                dryInput.add(reach);
            }
        }
        if (dryInput.isEmpty()) return network;
        List<NaturalRiverMorphology.Reach> dryOutput = NaturalRiverShallowProfile.refineDry(dryInput,
                (x, z) -> NaturalRiverShallowProfile.localGroundFloor(guarded, x, z),
                (int) StreamsNaturalNetwork.n(config, "seaLevel"), inset);
        if (dryOutput.equals(dryInput)) return network;
        List<NaturalRiverMorphology.Reach> output = new ArrayList<>(input);
        for (int i = 0; i < dryIndexes.size(); i++) output.set(dryIndexes.get(i), dryOutput.get(i));
        List<Object> updated = new ArrayList<>(segments.size());
        int changed = 0;
        for (int i = 0; i < segments.size(); i++) {
            var before = input.get(i); var after = output.get(i);
            if (before.equals(after)) updated.add(segments.get(i));
            else { changed++; updated.add(StreamsRecordAccess.copy(segments.get(i),
                    Map.of("y1", after.y1(), "y2", after.y2()))); }
        }
        LoggerFactory.getLogger("TerrainDiffusion/NaturalRivers").info(
                "V14 dry model grades: {} changed segments", changed);
        return StreamsNaturalNetwork.reassemble(network, updated, config);
    }

    /** Modeled wetland water is a hydraulic boundary; other guards remain pins. */
    private static boolean wetReach(NaturalRiverMorphology.Reach reach, ModelGround ground) {
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(reach.x2() - reach.x1(),
                reach.z2() - reach.z1())));
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            int x = (int) Math.floor(reach.x1() + (reach.x2() - reach.x1()) * t);
            int z = (int) Math.floor(reach.z1() + (reach.z2() - reach.z1()) * t);
            if (ground.wet(x, z)) return true;
        }
        return false;
    }

    private static final class ModelGround {
        private final LocalTerrainProvider provider;
        private final int size = TerrainDiffusionConfig.tileSize();
        private final Map<Long, LocalTerrainProvider.HeightmapData> tiles = new HashMap<>();
        private final Map<Long, LocalTerrainProvider.HeightmapData> marine = new HashMap<>();
        ModelGround(LocalTerrainProvider provider) { this.provider = provider; }
        double height(int x, int z) {
            if (StreamsMarineSampling.within16(x, z, marine, provider)) return Double.NaN;
            LocalTerrainProvider.HeightmapData data = tile(x, z);
            if (data == null || data.heightmap == null) return Double.NaN;
            int localX = Math.floorMod(x, size), localZ = Math.floorMod(z, size);
            if (localX >= data.width || localZ >= data.height
                    || (data.wetlandWaterLevels != null && data.wetlandWaterLevels[localZ][localX] != 0))
                return Double.NaN;
            return HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
        }
        boolean wet(int x, int z) {
            LocalTerrainProvider.HeightmapData data = tile(x, z);
            int localX = Math.floorMod(x, size), localZ = Math.floorMod(z, size);
            return data != null && data.wetlandWaterLevels != null && localX < data.width && localZ < data.height
                    && data.wetlandWaterLevels[localZ][localX] != 0;
        }
        private LocalTerrainProvider.HeightmapData tile(int x, int z) {
            int x0 = Math.floorDiv(x, size) * size, z0 = Math.floorDiv(z, size) * size;
            long key = ((long) x0 << 32) ^ (z0 & 0xffffffffL);
            return tiles.computeIfAbsent(key,
                    ignored -> provider.fetchHeightmap(z0, x0, z0 + size, x0 + size));
        }
    }
}
