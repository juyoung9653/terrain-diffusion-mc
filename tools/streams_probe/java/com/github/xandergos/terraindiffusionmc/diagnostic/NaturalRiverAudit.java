package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyShowcaseProfile;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRecordAccess;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Explicitly bounded plan export. Coast terminals are planned graph data, never physical outlet proof. */
final class NaturalRiverAudit {
    private NaturalRiverAudit() { }
    static void export(ServerLevel level) throws Exception {
        int x = Integer.getInteger("td.streamsProbe.naturalCenterX",
                Integer.getInteger("td.streamsProbe.topologyCenterX", -3573));
        int z = Integer.getInteger("td.streamsProbe.naturalCenterZ",
                Integer.getInteger("td.streamsProbe.topologyCenterZ", 92));
        int radius = Math.max(64, Math.min(2048, Integer.getInteger("td.streamsProbe.naturalRadius", 768)));
        Object engine = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                .getMethod("engine", WorldGenLevel.class).invoke(null, level);
        Object[] plates = (Object[]) engine.getClass().getMethod("platesNear", double.class, double.class, double.class)
                .invoke(engine, (double) x, (double) z, (double) radius);
        Map<String, Edge> edges = new HashMap<>();
        for (Object plate : plates) {
            Object network = engine.getClass().getMethod("networkFor", plate.getClass()).invoke(engine, plate);
            for (Object segment : segments(network)) {
                Edge edge = Edge.read(segment);
                edges.putIfAbsent(edge.key(), edge);
            }
        }
        List<Edge> ordered = edges.values().stream().sorted(Edge.ORDER).toList();
        StringBuilder csv = new StringBuilder("x1,z1,y1,w1,x2,z2,y2,w2\n");
        for (Edge edge : ordered) csv.append(edge.csv()).append('\n');
        var root = level.getServer().getWorldPath(LevelResource.ROOT);
        Files.writeString(root.resolve("td-natural-river-network.csv"), csv);
        Object config = engine.getClass().getMethod("config").invoke(engine);
        StringBuilder profile = new StringBuilder("enabled=" + WorldNaturalRiverState.enabled(level)
                + "\nversion=" + WorldNaturalRiverState.version(level) + "\n");
        for (String field : List.of("plateScale", "gridSpacing", "minRiverAccumulation", "minSeaChainNodes", "minLengthScope",
                "growDensity", "growFromWater", "baseWidth", "widthScale", "wetnessMultiplier", "confineToBanks",
                "meanderStrength", "streamDepth", "maxWidth", "hydraulics")) {
            profile.append(field).append('=').append(StreamsRecordAccess.read(config, field)).append('\n');
        }
        profile.append(String.format(Locale.ROOT, "bounds=%d,%d,%d,%d%n", x-radius,z-radius,x+radius,z+radius));
        profile.append("coastTerminalMeaning=marine-adjacent planned terminals; not proven physical outlets\n");
        Files.writeString(root.resolve("td-natural-river-profile.txt"), profile);
        if (Boolean.getBoolean("td.streamsProbe.naturalCoastAudit")) {
            exportMarineAdjacentTerminals(level, ordered, x, z, radius, root);
        }
    }

    /** The 2.16.2 RiverNetwork preserves the public type but renames its segment accessor. */
    @SuppressWarnings("unchecked")
    private static List<?> segments(Object network) throws ReflectiveOperationException {
        try {
            return (List<?>) network.getClass().getMethod("segments").invoke(network);
        } catch (NoSuchMethodException legacyAbsent) {
            return (List<?>) network.getClass().getMethod("sourceGivenToAuthorizedOnly1").invoke(network);
        }
    }

    /**
     * Finds only directed graph terminals inside the same square used by the plan export. This reads TD tiles directly;
     * it never asks Minecraft to generate chunks. A true value means marine-adjacent in the TD model, not a wet path.
     */
    private static void exportMarineAdjacentTerminals(ServerLevel level, List<Edge> edges, int centerX, int centerZ,
                                                       int radius, java.nio.file.Path root) throws Exception {
        Map<String, Degree> degrees = new HashMap<>();
        Map<String, Node> nodes = new HashMap<>();
        for (Edge edge : edges) {
            if (edge.start().key().equals(edge.end().key())) continue;
            nodes.putIfAbsent(edge.start().key(), edge.start());
            nodes.putIfAbsent(edge.end().key(), edge.end());
            degrees.computeIfAbsent(edge.start().key(), ignored -> new Degree()).outgoing++;
            degrees.computeIfAbsent(edge.end().key(), ignored -> new Degree()).incoming++;
        }
        int seaLevel = level.getChunkSource().getGenerator().getSeaLevel();
        Map<Long, LocalTerrainProvider.HeightmapData> tiles = new HashMap<>();
        List<Node> terminals = degrees.entrySet().stream()
                .filter(entry -> entry.getValue().outgoing == 0 && entry.getValue().incoming > 0)
                .map(entry -> nodes.get(entry.getKey()))
                .filter(node -> inBounds(node, centerX, centerZ, radius))
                .sorted(Node.ORDER).toList();
        StringBuilder csv = new StringBuilder("x,z,gradeY,fullWidth,marineWithin32\n");
        for (Node terminal : terminals) {
            boolean marine = terminal.y <= seaLevel + 2 && marineWithin32(terminal, tiles);
            csv.append(number(terminal.x)).append(',').append(number(terminal.z)).append(',')
                    .append(number(terminal.y)).append(',').append(number(2.0 * terminal.width)).append(',')
                    .append(marine).append('\n');
        }
        Files.writeString(root.resolve("td-natural-coast-terminals.csv"), csv);
    }

    private static boolean inBounds(Node node, int x, int z, int radius) {
        return node.x >= x - radius && node.x <= x + radius && node.z >= z - radius && node.z <= z + radius;
    }

    /** Samples the endpoint and a radius-32, 8-block grid within the circle, using a bounded per-export tile cache. */
    private static boolean marineWithin32(Node node, Map<Long, LocalTerrainProvider.HeightmapData> tiles) {
        int x = (int) Math.round(node.x), z = (int) Math.round(node.z);
        for (int dz = -32; dz <= 32; dz += 8) for (int dx = -32; dx <= 32; dx += 8) {
            if (dx * dx + dz * dz > 32 * 32) continue;
            MarineMask mask = marineMaskAt(x + dx, z + dz, tiles);
            if (mask != null && mask.heightmap < 0 && EcologyShowcaseProfile.isVanillaOcean(mask.biomeId)) return true;
        }
        return false;
    }

    /** Same tile coordinates and raw TD heightmap convention as StreamsReflowingProbe.tdMarineMaskAt. */
    private static MarineMask marineMaskAt(int x, int z, Map<Long, LocalTerrainProvider.HeightmapData> tiles) {
        int tileSize = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(tileSize);
        int tileX = x >> shift, tileZ = z >> shift;
        long key = ChunkPos.asLong(tileX, tileZ);
        LocalTerrainProvider.HeightmapData data = tiles.get(key);
        if (data == null && !tiles.containsKey(key)) {
            int startX = tileX << shift, startZ = tileZ << shift;
            data = LocalTerrainProvider.getInstance().fetchHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
            tiles.put(key, data);
        }
        if (data == null || data.heightmap == null || data.biomeIds == null) return null;
        int localX = x - (tileX << shift), localZ = z - (tileZ << shift);
        if (localX < 0 || localZ < 0 || localX >= data.width || localZ >= data.height) return null;
        return new MarineMask(data.heightmap[localZ][localX], data.biomeIds[localZ][localX]);
    }

    private static String number(double value) { return Double.toString(value); }

    private record MarineMask(short heightmap, short biomeId) { }
    private static final class Degree { int incoming; int outgoing; }
    private record Node(double x, double z, double y, double width) {
        static final Comparator<Node> ORDER = Comparator.comparingDouble(Node::x).thenComparingDouble(Node::z)
                .thenComparingDouble(Node::y).thenComparingDouble(Node::width);
        String key() { return Math.round(x * 16) + "," + Math.round(z * 16); }
    }
    private record Edge(Node start, Node end) {
        static final Comparator<Edge> ORDER = Comparator.comparing(Edge::start, Node.ORDER).thenComparing(Edge::end, Node.ORDER);
        static Edge read(Object segment) {
            return new Edge(node(segment, "x1", "z1", "y1", "w1"), node(segment, "x2", "z2", "y2", "w2"));
        }
        private static Node node(Object segment, String x, String z, String y, String width) {
            return new Node(StreamsRecordAccess.number(segment, x), StreamsRecordAccess.number(segment, z),
                    StreamsRecordAccess.number(segment, y), StreamsRecordAccess.number(segment, width));
        }
        /** Preserve parallel geometry with distinct grades/widths; only byte-identical exported segment values deduplicate. */
        String key() { return csv(); }
        String csv() { return number(start.x) + ',' + number(start.z) + ',' + number(start.y) + ',' + number(start.width)
                + ',' + number(end.x) + ',' + number(end.z) + ',' + number(end.y) + ',' + number(end.width); }
    }
}
