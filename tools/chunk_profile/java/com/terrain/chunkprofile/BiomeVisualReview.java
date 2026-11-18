package com.terrain.chunkprofile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.slf4j.Logger;

/**
 * Captures repeatable, native-client evidence for an independent biome realism
 * review.  This deliberately records evidence only: an outside reviewer decides
 * whether the scenery is acceptable.
 */
public final class BiomeVisualReview {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int TICKS = 100;
    private static final int RADIUS = 1;
    private static final int TICKET_DISTANCE = 2;
    private static final TicketType<ChunkPos> TICKET = TicketType.create("td-biome-visual-review",
            Comparator.comparingLong(ChunkPos::toLong));

    private BiomeVisualReview() { }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> run(MinecraftServer server, Path planPath, Path output) throws Exception {
        JsonObject document = JsonParser.parseString(Files.readString(planPath)).getAsJsonObject();
        Map<String, Object> raw = JSON.fromJson(document, Map.class);
        Plan plan = Plan.parse(raw, document.get("seed").getAsLong());
        String reviewToolFingerprint = System.getProperty("chunkprofile.biomeReviewToolFingerprint", "");
        if (reviewToolFingerprint.isBlank()) throw new IllegalStateException("biome review tool fingerprint is missing");
        String diagnosticJarSha256 = System.getProperty("chunkprofile.biomeReviewDiagnosticJarSha256", "");
        if (!diagnosticJarSha256.matches("[0-9a-f]{64}")) throw new IllegalStateException("biome review diagnostic JAR hash is missing");
        String runId = Long.toUnsignedString(System.nanoTime(), 36);
        Path reviewRoot = output.resolveSibling("biome-visual-review-" + runId);
        Files.createDirectories(reviewRoot);
        ServerLevel level = server.overworld();
        if (level.getSeed() != plan.seed) throw new IllegalStateException("biome review seed differs from plan");
        CaptureLighting lighting = server.submit(() -> CaptureLighting.freeze(level)).get(30, TimeUnit.SECONDS);
        try {
        List<TracePoint> tracePoints = tracePositions();
        List<Map<String, Object>> wetlandTerrainTrace = tracePoints.isEmpty() ? List.of()
                : server.submit(() -> traceWetlandTerrain(tracePoints)).get(120, TimeUnit.SECONDS);
        List<Column> columns = scanPlanWindows(server, level, plan);
        for (Explicit explicit : plan.explicit) {
            boolean present = columns.stream().anyMatch(column -> column.x == explicit.x && column.z == explicit.z);
            if (!present) columns.add(server.submit(() -> observe(level, explicit.x, explicit.z)).get(30, TimeUnit.SECONDS));
        }
        Path columnsPath = reviewRoot.resolve("columns.json");
        atomic(columnsPath, Map.of("schema", 1, "seed", plan.seed, "scale", plan.scale,
                "columns", columns.stream().map(Column::toMap).toList()));
        Selection selection = choose(plan, columns);
        List<Map<String, Object>> inspectionReceipts = new ArrayList<>();
        for (InspectionRequest request : plan.inspectionRequests) {
            InspectionResolution resolution = resolveInspection(request, plan.windows, columns);
            inspectionReceipts.add(resolution.toMap());
            // Inspection evidence is additive: preserve original regression coordinates.
            selection.sites.add(new Selected(request.id, resolution.resolved.x, resolution.resolved.z,
                    resolution.resolved.biome, new ArrayList<>(List.of("inspection-" + request.kind)),
                    resolution.resolved, resolution));
        }
        if (selection.sites.size() > plan.maxSites) {
            selection.gaps.add("maxSites " + plan.maxSites + " is below required evidence sites " + selection.sites.size());
            selection.sites.subList(plan.maxSites, selection.sites.size()).clear();
        }
        Path selectionPath = reviewRoot.resolve("selection.json");
        List<Map<String, Object>> sites = new ArrayList<>();
        for (Selected selected : selection.sites) sites.add(captureSite(server, level, selected, runId, plan.windows));
        // Root inspections resolve against settled blocks after their tickets have ticked.
        // Persist the mutable capture anchor, rather than the provisional census anchor.
        atomic(selectionPath, selection.toMap());
        inspectionReceipts = selection.sites.stream().filter(site -> site.inspection != null)
                .map(site -> site.inspection.toMap()).toList();
        boolean captured = selection.gaps.isEmpty()
                && sites.stream().allMatch(site -> "captured".equals(site.get("status")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", captured ? "captured" : "failed");
        result.put("seed", plan.seed); result.put("scale", plan.scale);
        result.put("sourceFingerprint", plan.sourceFingerprint);
        result.put("artifactSha256", plan.artifactSha256);
        result.put("reviewToolFingerprint", reviewToolFingerprint);
        result.put("diagnosticJarSha256", diagnosticJarSha256);
        result.put("captureLighting", lighting.captureMap());
        result.put("reviewRoot", reviewRoot.toAbsolutePath().toString());
        result.put("wetlandTerrainTrace", wetlandTerrainTrace);
        result.put("planPath", planPath.toAbsolutePath().toString());
        result.put("columnsPath", columnsPath.toAbsolutePath().toString());
        result.put("selectionPath", selectionPath.toAbsolutePath().toString());
        result.put("requiredBiomes", plan.requiredBiomes); result.put("coverageGaps", selection.gaps);
        result.put("inspectionRequests", inspectionReceipts);
        result.put("sites", sites); result.put("requiredServerTicks", TICKS);
        result.put("scope", "Native screenshots and settled saved-block observations for independent review; captured is not a realism acceptance decision.");
        return result;
        } finally {
            server.submit(() -> lighting.restore(level)).get(30, TimeUnit.SECONDS);
        }
    }

    /** Optional, bounded provider trace for comparing shaped pipeline output with saved blocks. */
    private static List<TracePoint> tracePositions() {
        String raw = System.getProperty("td.wetland.tracePositions", "");
        if (raw.isBlank()) return List.of();
        String[] rows = raw.split(";", -1);
        if (rows.length > 8) throw new IllegalArgumentException("td.wetland.tracePositions permits at most 8 positions");
        List<TracePoint> result = new ArrayList<>();
        for (String row : rows) {
            String[] pair = row.split(",", -1);
            if (pair.length != 2) throw new IllegalArgumentException("trace position must be x,z");
            try { result.add(new TracePoint(Integer.parseInt(pair[0]), Integer.parseInt(pair[1]))); }
            catch (NumberFormatException failure) { throw new IllegalArgumentException("trace position must use integer x,z", failure); }
        }
        return result;
    }

    /** Runs on the server thread before chunk tickets; fetchHeightmap delegates inference safely to its own worker. */
    private static List<Map<String, Object>> traceWetlandTerrain(List<TracePoint> points) {
        try {
            Class<?> config = Class.forName("com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig");
            Class<?> providerType = Class.forName("com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider");
            Class<?> converter = Class.forName("com.github.xandergos.terraindiffusionmc.world.HeightConverter");
            Class<?> scale = Class.forName("com.github.xandergos.terraindiffusionmc.world.WorldScaleManager");
            int size = ((Number) config.getMethod("tileSize").invoke(null)).intValue();
            int currentScale = ((Number) scale.getMethod("getCurrentScale").invoke(null)).intValue();
            Object provider = providerType.getMethod("getInstance").invoke(null);
            Method fetch = providerType.getMethod("fetchHeightmap", int.class, int.class, int.class, int.class);
            Map<String, Object> tiles = new LinkedHashMap<>();
            for (TracePoint point : points) {
                int x0 = Math.floorDiv(point.x, size) * size, z0 = Math.floorDiv(point.z, size) * size;
                String key = x0 + "," + z0;
                if (!tiles.containsKey(key)) tiles.put(key, fetch.invoke(provider, z0, x0, z0 + size, x0 + size));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (TracePoint point : points) {
                int x0 = Math.floorDiv(point.x, size) * size, z0 = Math.floorDiv(point.z, size) * size;
                Object data = tiles.get(x0 + "," + z0);
                if (data == null) throw new IllegalStateException("trace provider returned null tile");
                int localX = point.x - x0, localZ = point.z - z0;
                short[][] heights = (short[][]) field(data, "heightmap");
                short[][] water = (short[][]) field(data, "wetlandWaterLevels");
                short shapedHeight = heights[localZ][localX];
                int boundaryY = ((Number) converter.getMethod("convertToMinecraftHeight", short.class, int.class)
                        .invoke(null, shapedHeight, currentScale)).intValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("x", point.x); row.put("z", point.z); row.put("tile", List.of(x0, z0)); row.put("tileSize", size);
                row.put("shapedHeight", shapedHeight); row.put("boundaryY", boundaryY); row.put("expectedGroundY", boundaryY - 1);
                row.put("wetlandWaterLevel", water == null ? 0 : (int) water[localZ][localX]);
                Object ecology = field(data, "ecology");
                if (ecology != null) {
                    Object sample = ecology.getClass().getMethod("sample", int.class, int.class).invoke(ecology, localX, localZ);
                    row.put("terrainBiomeId", ((Number) ecology.getClass().getMethod("terrainBiomeId", int.class, int.class).invoke(ecology, localX, localZ)).intValue());
                    row.put("ecology", recordValues(sample));
                }
                rows.add(row);
            }
            return rows;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("wetland terrain trace reflection failed", failure);
        }
    }

    private static Object field(Object value, String name) throws ReflectiveOperationException {
        Field field = value.getClass().getField(name); return field.get(value);
    }
    private static Map<String, Object> recordValues(Object value) throws ReflectiveOperationException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var component : value.getClass().getRecordComponents()) result.put(component.getName(), component.getAccessor().invoke(value));
        return result;
    }

    private static List<Column> scanPlanWindows(MinecraftServer server, ServerLevel level, Plan plan) throws Exception {
        List<Column> result = new ArrayList<>();
        for (Window window : plan.windows) {
            List<ChunkPos> chunks = new ArrayList<>();
            for (int z = (window.minZ >> 4) - 1; z <= (window.maxZ >> 4) + 1; z++) for (int x = (window.minX >> 4) - 1; x <= (window.maxX >> 4) + 1; x++) chunks.add(new ChunkPos(x, z));
            server.submit(() -> chunks.forEach(pos -> level.getChunkSource().addRegionTicket(TICKET, pos, TICKET_DISTANCE, pos))).get(60, TimeUnit.SECONDS);
            try {
                List<? extends CompletableFuture<?>> futures = server.submit(() -> chunks.stream().map(pos -> level.getChunkSource().getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true)).toList()).get(30, TimeUnit.SECONDS);
                for (CompletableFuture<?> future : futures) future.get(180, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!server.submit(() -> chunks.stream().allMatch(pos -> level.shouldTickBlocksAt(pos.toLong()))).get(30, TimeUnit.SECONDS)) {
                    if (System.nanoTime() > deadline) throw new IllegalStateException("review window did not become tick eligible");
                    Thread.sleep(50);
                }
                long start = server.submit(level::getGameTime).get(30, TimeUnit.SECONDS);
                while (server.submit(level::getGameTime).get(30, TimeUnit.SECONDS) - start < TICKS) {
                    if (System.nanoTime() > deadline + TimeUnit.SECONDS.toNanos(180)) throw new IllegalStateException("review window did not advance ticks");
                    Thread.sleep(50);
                }
                List<Column> windowColumns = server.submit(() -> {
                    List<Column> observed = new ArrayList<>();
                    for (int z = window.minZ; z <= window.maxZ; z++)
                        for (int x = window.minX; x <= window.maxX; x++) observed.add(observe(level, x, z));
                    return observed;
                }).get(180, TimeUnit.SECONDS);
                result.addAll(windowColumns);
            } finally {
                server.submit(() -> chunks.forEach(pos -> level.getChunkSource().removeRegionTicket(TICKET, pos, TICKET_DISTANCE, pos))).get(60, TimeUnit.SECONDS);
            }
        }
        return result;
    }

    private static Column observe(ServerLevel level, int x, int z) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int floorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
        int groundY = substrate(level, x, z, Math.max(surfaceY, floorY));
        BlockPos groundPos = new BlockPos(x, groundY, z);
        BlockState ground = level.getBlockState(groundPos);
        int topWater = topWater(level, x, z, surfaceY);
        int waterDepth = 0;
        if (topWater != Integer.MIN_VALUE) {
            for (int y = topWater; y >= level.getMinBuildHeight() && level.getFluidState(new BlockPos(x, y, z)).is(FluidTags.WATER); y--) waterDepth++;
        }
        WaterExposure waterExposure = waterExposure(level, x, z, groundY, topWater);
        ResourceLocation biome = level.getBiome(new BlockPos(x, Math.max(level.getMinBuildHeight(), groundY), z)).unwrapKey()
                .map(key -> key.location()).orElse(ResourceLocation.withDefaultNamespace("unkeyed"));
        int delta = 0;
        boolean boundary = false;
        for (int[] d : new int[][] {{1,0},{-1,0},{0,1},{0,-1}}) {
            int otherSurface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x + d[0], z + d[1]) - 1;
            int otherGround = substrate(level, x + d[0], z + d[1], otherSurface);
            delta = Math.max(delta, Math.abs(groundY - otherGround));
            ResourceLocation other = level.getBiome(new BlockPos(x + d[0], Math.max(level.getMinBuildHeight(), otherGround), z + d[1]))
                    .unwrapKey().map(key -> key.location()).orElse(ResourceLocation.withDefaultNamespace("unkeyed"));
            boundary |= !biome.equals(other);
        }
        LogSupport support = logSupport(level, x, z, surfaceY, groundY);
        boolean anomaly = (waterExposure.exposed && (ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT))) || delta >= 2;
        return new Column(x, z, biome.toString(), groundY, surfaceY, waterDepth,
                blockId(ground), substrateClassification(level, groundPos, ground), waterExposure.exposed,
                waterExposure.covered, waterExposure.coveringBlock, support, delta, boundary, anomaly);
    }

    private static int substrate(ServerLevel level, int x, int z, int start) {
        int min = level.getMinBuildHeight();
        for (int y = Math.max(min, start); y >= min; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!"not-substrate".equals(substrateClassification(level, pos, level.getBlockState(pos)))) return y;
        }
        return min;
    }

    /**
     * World-surface includes pads, flowers, roots and decorative rocks.  Those
     * are visually useful but are never the terrain substrate.  Use material
     * tags for native ground and the Terrain Slabs namespace for its deliberately
     * non-full collision terrain layers; this keeps mud valid even when its
     * collision shape is not a full cube.
     */
    private static String substrateClassification(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS) || state.canBeReplaced()) return "not-substrate";
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = id.getPath();
        // Roots and known decorative-rock blocks may be solid but are never terrain.
        if (path.equals("mangrove_roots") || path.equals("muddy_mangrove_roots")
                || path.contains("bee_nest") || path.contains("small_rock")
                || "rocks".equals(id.getNamespace())) return "not-substrate";
        if ("terrain_slabs".equals(id.getNamespace())) return "terrain-slab";
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.MUD) || state.is(Blocks.CLAY) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE) || state.is(Blocks.END_STONE)
                || state.is(Blocks.CRIMSON_NYLIUM) || state.is(Blocks.WARPED_NYLIUM)
                || path.endsWith("terracotta")) return "known-natural";
        // Preserve unfamiliar, full solid mod terrain for reviewer inspection rather than
        // silently drilling through it to buried dirt or bedrock.
        return Block.isShapeFullBlock(state.getCollisionShape(level, pos)) ? "unknown-full-solid" : "not-substrate";
    }

    private static int topWater(ServerLevel level, int x, int z, int surface) {
        for (int y = Math.max(level.getMinBuildHeight(), surface); y >= level.getMinBuildHeight(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getFluidState(pos).is(FluidTags.WATER)) return y;
            if (!"not-substrate".equals(substrateClassification(level, pos, level.getBlockState(pos)))) return Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    /** Distinguishes an exposed bed from soil below a waterlogged root or other solid cover. */
    private static WaterExposure waterExposure(ServerLevel level, int x, int z, int groundY, int topWater) {
        if (topWater == Integer.MIN_VALUE || topWater <= groundY) return new WaterExposure(false, false, null);
        for (int y = groundY + 1; y <= topWater; y++) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (!state.getFluidState().is(FluidTags.WATER)) return new WaterExposure(false, true, blockId(state));
            if (!isOpenWaterOrPlant(state)) return new WaterExposure(false, true, blockId(state));
        }
        return new WaterExposure(true, false, null);
    }

    private static boolean isOpenWaterOrPlant(BlockState state) {
        return state.is(Blocks.WATER) || state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS);
    }

    private static LogSupport logSupport(ServerLevel level, int x, int z, int surfaceY, int groundY) {
        int lowestLogY = Integer.MAX_VALUE;
        String lowestLogBlock = null;
        int start = Math.max(surfaceY, groundY + 32);
        for (int y = start; y >= Math.max(groundY - 1, level.getMinBuildHeight()); y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (state.is(BlockTags.LOGS)) {
                lowestLogY = y;
                lowestLogBlock = blockId(state);
            }
        }
        BlockState support = level.getBlockState(new BlockPos(x,
                lowestLogBlock == null ? groundY : Math.max(level.getMinBuildHeight(), lowestLogY - 1), z));
        return new LogSupport(lowestLogBlock == null ? null : lowestLogY, lowestLogBlock,
                blockId(support), lowestLogBlock != null && support.is(Blocks.MUD));
    }

    private static Selection choose(Plan plan, List<Column> columns) {
        List<Selected> selected = new ArrayList<>(); List<String> gaps = new ArrayList<>();
        for (Explicit site : plan.explicit) {
            Column observed = columns.stream().filter(column -> column.x == site.x && column.z == site.z).findFirst()
                    .orElseThrow(() -> new IllegalStateException("regression point was not observed"));
            add(selected, observed, site.id, "regression");
        }
        for (String biome : plan.requiredBiomes) {
            List<Column> candidates = columns.stream().filter(c -> biome.equals(c.biome)).toList();
            if (candidates.isEmpty()) { gaps.add("required biome absent: " + biome); continue; }
            pick(selected, candidates.stream().filter(c -> !c.boundary).toList(), biome, "interior", plan.seed, gaps);
            pick(selected, candidates.stream().filter(c -> c.boundary).toList(), biome, "boundary", plan.seed, gaps);
            pick(selected, candidates, biome, "random", plan.seed, gaps);
            List<Column> anomalies = candidates.stream().filter(c -> c.anomaly).toList();
            if (!anomalies.isEmpty()) pick(selected, anomalies, biome, "anomaly", plan.seed, gaps);
        }
        // A site can carry several reasons. Keep deterministic order and fail coverage rather than silently truncating it.
        if (selected.size() > plan.maxSites) gaps.add("maxSites " + plan.maxSites + " is below required evidence sites " + selected.size());
        if (selected.size() > plan.maxSites) selected = new ArrayList<>(selected.subList(0, plan.maxSites));
        return new Selection(selected, gaps);
    }

    /** Resolve review requests from the generated census only; a later 100-tick reread verifies every resolved feature. */
    private static InspectionResolution resolveInspection(InspectionRequest request, List<Window> windows,
                                                           List<Column> columns) {
        Column requested = columnAt(columns, request.x, request.z);
        List<Column> nearby = columns.stream().filter(c -> inWindow(c, windows)
                && Math.max(Math.abs(c.x - request.x), Math.abs(c.z - request.z)) <= 12).toList();
        return switch (request.kind) {
            case "root" -> resolveRoot(request, requested, nearby);
            case "underwater" -> resolveUnderwater(request, requested, nearby);
            case "bank" -> resolveBank(request, requested, nearby, false);
            case "boundary" -> resolveBank(request, requested, nearby, true);
            default -> throw new IllegalArgumentException("unsupported inspection kind " + request.kind);
        };
    }

    private static InspectionResolution resolveRoot(InspectionRequest request, Column requested, List<Column> nearby) {
        List<Column> candidates = nearby.stream().filter(BiomeVisualReview::groundedLog).toList();
        if (candidates.isEmpty()) return noTarget(request, requested, "no-target-grounded-log-within-12");
        Column chosen = nearest(request, candidates);
        return new InspectionResolution(request, chosen, candidates.size(), "grounded-log-contact", List.of(chosen));
    }

    private static InspectionResolution resolveUnderwater(InspectionRequest request, Column requested,
                                                           List<Column> nearby) {
        List<Column> candidates = nearby.stream().filter(c -> c.exposedWaterBed && !c.covered && c.waterDepth >= 2).toList();
        if (candidates.isEmpty()) return noTarget(request, requested, "no-target-exposed-water-depth-ge-2-within-12");
        Column chosen = candidates.stream().min(Comparator.comparingInt((Column c) -> distance(request, c))
                .thenComparing(Comparator.comparingInt((Column c) -> c.waterDepth).reversed())
                .thenComparingInt(c -> c.x).thenComparingInt(c -> c.z)).orElseThrow();
        return new InspectionResolution(request, chosen, candidates.size(), "exposed-water-bed-depth-ge-2", List.of(chosen));
    }

    private static InspectionResolution resolveBank(InspectionRequest request, Column requested,
                                                     List<Column> nearby, boolean boundary) {
        List<Profile> profiles = new ArrayList<>();
        Map<String, Column> grid = new LinkedHashMap<>();
        for (Column column : nearby) grid.put(column.x + "," + column.z, column);
        for (int i = 0; i < nearby.size(); i++) for (int j = i + 1; j < nearby.size(); j++) {
            Column a = nearby.get(i), b = nearby.get(j);
            if (Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z)) > 6 || !connectedColumns(a, b, grid)) continue;
            if (boundary ? a.bedBlock.equals(b.bedBlock) : Math.abs(a.groundY - b.groundY) < 2) continue;
            Column crest = a.groundY >= b.groundY ? a : b;
            Column toe = crest == a ? b : a;
            if (!wetToe(toe)) continue;
            profiles.add(new Profile(crest, toe));
        }
        if (profiles.isEmpty()) return noTarget(request, requested,
                boundary ? "no-target-material-boundary-within-12" : "no-target-bank-profile-within-12");
        Profile chosen = profiles.stream().min(Comparator.comparingInt((Profile p) -> Math.min(distance(request, p.crest), distance(request, p.toe)))
                .thenComparing(Comparator.comparingInt((Profile p) -> Math.abs(p.crest.groundY - p.toe.groundY)).reversed())
                .thenComparingInt(p -> p.crest.x).thenComparingInt(p -> p.crest.z)
                .thenComparingInt(p -> p.toe.x).thenComparingInt(p -> p.toe.z)).orElseThrow();
        return new InspectionResolution(request, chosen.toe, profiles.size(),
                boundary ? "material-boundary-profile" : "bank-profile", List.of(chosen.crest, chosen.toe));
    }

    /** Reads only the ticketed, settled capture neighborhood. All paths are bounded to the requested 12-block square. */
    private static InspectionResolution resolveSettledRoot(ServerLevel level, InspectionRequest request, List<Window> windows) {
        Column requested = observe(level, request.x, request.z);
        List<BlockPos> roots = new ArrayList<>(), groundedLogs = new ArrayList<>();
        for (int z = request.z - 12; z <= request.z + 12; z++) for (int x = request.x - 12; x <= request.x + 12; x++) {
            if (!inWindow(x, z, windows)) continue;
            int ground = substrate(level, x, z, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1);
            for (int y = ground; y <= ground + 24; y++) {
                BlockPos pos = new BlockPos(x, y, z); BlockState state = level.getBlockState(pos);
                if (rootBlock(state)) roots.add(pos);
                else if (state.is(BlockTags.LOGS) && y == ground + 1) groundedLogs.add(pos);
            }
        }
        roots.sort(rootOrder(request));
        if (!roots.isEmpty()) {
            // The nearest actual root is the evidence target even if floating or broken. Do not search a farther healthy tree.
            RootConnectivity chosen = rootConnectivity(level, request, roots.getFirst(), windows);
            Column anchor = observe(level, chosen.root.getX(), chosen.root.getZ());
            return new InspectionResolution(request, anchor, roots.size(), chosen.reason, columnsFor(level, chosen), chosen);
        }
        groundedLogs.sort(rootOrder(request));
        if (!groundedLogs.isEmpty()) {
            BlockPos log = groundedLogs.get(0); Column anchor = observe(level, log.getX(), log.getZ());
            RootConnectivity fallback = new RootConnectivity(log, List.of(new RootBlock(log, blockId(level.getBlockState(log)))), true, true,
                    "grounded-log-contact", false);
            return new InspectionResolution(request, anchor, groundedLogs.size(), "grounded-log-contact", List.of(anchor), fallback);
        }
        return new InspectionResolution(request, requested, 0, "no-target-root-or-grounded-log-within-12", List.of(),
                new RootConnectivity(new BlockPos(request.x, requested.groundY, request.z), List.of(), false, false, "no-target", false));
    }
    private static Comparator<BlockPos> rootOrder(InspectionRequest request) {
        return Comparator.comparingInt((BlockPos pos) -> Math.max(Math.abs(pos.getX() - request.x), Math.abs(pos.getZ() - request.z)))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ);
    }
    private static boolean inWindow(int x, int z, List<Window> windows) { return windows.stream().anyMatch(w -> x >= w.minX && x <= w.maxX && z >= w.minZ && z <= w.maxZ); }
    private static boolean rootBlock(BlockState state) { return state.is(Blocks.MANGROVE_ROOTS) || state.is(Blocks.MUDDY_MANGROVE_ROOTS); }
    private static boolean rootOrLog(BlockState state) { return rootBlock(state) || state.is(BlockTags.LOGS); }
    static RootWalk boundedRootPath(BlockPos start, Predicate<BlockPos> connected, Predicate<BlockPos> log, Predicate<BlockPos> allowed) {
        Deque<BlockPos> queue = new ArrayDeque<>(); Map<BlockPos, BlockPos> parent = new HashMap<>(); queue.add(start); parent.put(start, null);
        BlockPos endpoint = null, farthest = start; boolean capped = false, boundary = false;
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            if (log.test(current)) { endpoint = current; break; }
            if (parent.size() >= 2048) { capped = true; break; } // never enqueue node 2049
            for (int[] d : ROOT_NEIGHBORS) {
                BlockPos next = current.offset(d[0], d[1], d[2]); if (parent.containsKey(next)) continue;
                if (!allowed.test(next)) { if (connected.test(next)) boundary = true; continue; }
                if (!connected.test(next)) continue;
                if (parent.size() >= 2048) { capped = true; break; }
                parent.put(next, current); queue.addLast(next);
                int nextDistance=Math.abs(next.getX()-start.getX())+Math.abs(next.getY()-start.getY())+Math.abs(next.getZ()-start.getZ());
                int farDistance=Math.abs(farthest.getX()-start.getX())+Math.abs(farthest.getY()-start.getY())+Math.abs(farthest.getZ()-start.getZ());
                if(nextDistance>farDistance || (nextDistance==farDistance && next.getY()>farthest.getY())) farthest=next;
            }
        }
        List<BlockPos> path = new ArrayList<>();
        for (BlockPos at = endpoint != null ? endpoint : farthest; at != null; at = parent.get(at)) path.add(at);
        java.util.Collections.reverse(path);
        return new RootWalk(path, endpoint != null, capped, boundary);
    }
    private static RootConnectivity rootConnectivity(ServerLevel level, InspectionRequest request, BlockPos start, List<Window> windows) {
        int floor = substrate(level, start.getX(), start.getZ(), level.getHeight(Heightmap.Types.WORLD_SURFACE, start.getX(), start.getZ()) - 1);
        Predicate<BlockPos> allowed = next -> Math.max(Math.abs(next.getX() - request.x), Math.abs(next.getZ() - request.z)) <= 12
                && next.getY() >= floor && next.getY() <= floor + 24 && inWindow(next.getX(), next.getZ(), windows);
        RootWalk walk = boundedRootPath(start, next -> rootOrLog(level.getBlockState(next)),
                next -> level.getBlockState(next).is(BlockTags.LOGS), allowed);
        List<RootBlock> path = walk.path.stream().map(pos -> new RootBlock(pos, blockId(level.getBlockState(pos)))).toList();
        boolean grounded = rootGrounded(level, start);
        return new RootConnectivity(start, path, walk.complete, grounded,
                walk.complete ? "root-to-trunk-connected" : walk.capped ? "visited-cap-2048" : walk.boundary ? "search-boundary-before-trunk" : "broken-root-no-trunk", walk.capped);
    }
    /** A mangrove root may enter the bed from its side; fluid, foliage and decoration are not bed contact. */
    static boolean rootGroundContact(String classification) { return !"not-substrate".equals(classification); }
    private static boolean rootGrounded(ServerLevel level, BlockPos root) {
        for (int[] d : ROOT_NEIGHBORS) { BlockPos neighborPos=root.offset(d[0],d[1],d[2]);
            if (rootGroundContact(substrateClassification(level, neighborPos, level.getBlockState(neighborPos)))) return true;
        } return false;
    }
    private static List<Column> columnsFor(ServerLevel level, RootConnectivity connectivity) {
        List<Column> out = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>();
        for (RootBlock block : connectivity.path) if (seen.add(block.pos.getX() + "," + block.pos.getZ())) out.add(observe(level, block.pos.getX(), block.pos.getZ()));
        return out;
    }
    private static final int[][] ROOT_NEIGHBORS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    private static InspectionResolution noTarget(InspectionRequest request, Column requested, String reason) {
        return new InspectionResolution(request, requested, 0, reason, List.of());
    }
    private static boolean groundedLog(Column c) { return c.support.lowestLogY != null && c.support.lowestLogY == c.groundY + 1; }
    private static Column nearest(InspectionRequest request, List<Column> candidates) {
        return candidates.stream().min(Comparator.comparingInt((Column c) -> distance(request, c))
                .thenComparingInt(c -> c.x).thenComparingInt(c -> c.z)).orElseThrow();
    }
    private static int distance(InspectionRequest request, Column column) {
        return Math.max(Math.abs(column.x - request.x), Math.abs(column.z - request.z));
    }
    private static Column columnAt(List<Column> columns, int x, int z) {
        return columns.stream().filter(c -> c.x == x && c.z == z).findFirst()
                .orElseThrow(() -> new IllegalStateException("inspection request was not observed"));
    }
    private static boolean inWindow(Column c, List<Window> windows) {
        return windows.stream().anyMatch(w -> c.x >= w.minX && c.x <= w.maxX && c.z >= w.minZ && c.z <= w.maxZ);
    }
    private static boolean wetToe(Column column) {
        return column.waterDepth > 0 || "minecraft:mud".equals(column.bedBlock);
    }
    /** Every Bresenham sample must come from the same bounded census; no gap can be implied as a profile. */
    private static boolean connectedColumns(Column a, Column b, Map<String, Column> grid) {
        int x = a.x, z = a.z, dx = Math.abs(b.x - x), dz = Math.abs(b.z - z);
        int sx = x < b.x ? 1 : -1, sz = z < b.z ? 1 : -1, error = dx - dz;
        while (true) {
            if (!grid.containsKey(x + "," + z)) return false;
            if (x == b.x && z == b.z) return true;
            int twice = error * 2;
            if (twice > -dz) { error -= dz; x += sx; }
            if (twice < dx) { error += dx; z += sz; }
        }
    }

    private static void pick(List<Selected> selected, List<Column> candidates, String biome, String reason, long seed, List<String> gaps) {
        if (candidates.isEmpty()) { gaps.add("missing " + reason + " evidence for " + biome); return; }
        Column best = "anomaly".equals(reason)
                ? candidates.stream().max(Comparator.<Column>comparingInt(BiomeVisualReview::anomalyScore).thenComparingLong(c -> hash(seed, c.x, c.z))).orElseThrow()
                : candidates.stream().min(Comparator.comparingLong(c -> hash(seed ^ reason.hashCode(), c.x, c.z))).orElseThrow();
        add(selected, best, biome + "-" + reason, reason);
    }

    private static int anomalyScore(Column column) {
        int score = column.cardinalHeightDelta * 10;
        if (column.exposedWaterBed && (column.bedBlock.equals("minecraft:grass_block") || column.bedBlock.equals("minecraft:dirt"))) score += 10_000;
        return score;
    }

    private static void add(List<Selected> selected, Column candidate, String id, String reason) {
        for (Selected existing : selected) if (existing.x == candidate.x && existing.z == candidate.z) { existing.reasons.add(reason); return; }
        selected.add(new Selected(id, candidate.x, candidate.z, candidate.biome, new ArrayList<>(List.of(reason)), candidate));
    }

    private static long hash(long seed, int x, int z) {
        long value = seed ^ (x * 341873128712L) ^ (z * 132897987541L);
        value ^= value >>> 33; value *= 0xff51afd7ed558ccdL; value ^= value >>> 33; return value;
    }

    private static Map<String, Object> captureSite(MinecraftServer server, ServerLevel level, Selected selected, String runId, List<Window> windows) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("id", selected.id); result.put("x", selected.x); result.put("z", selected.z);
        result.put("biome", selected.biome); result.put("reasons", selected.reasons); result.put("column", selected.column.toMap());
        if (selected.inspection != null) result.put("inspectionRequest", selected.inspection.toMap());
        LinkedHashSet<ChunkPos> ticketSet = new LinkedHashSet<>(nearby(selected.x, selected.z));
        // Root discovery scans the request square, which can sit two chunks from its provisional census anchor.
        if (selected.inspection != null && "root".equals(selected.inspection.request.kind)) ticketSet.addAll(rootRequestTickets(selected.inspection.request.x, selected.inspection.request.z));
        List<ChunkPos> chunks = new ArrayList<>(ticketSet); List<Map<String, Object>> images = new ArrayList<>();
        try {
            server.submit(() -> chunks.forEach(pos -> level.getChunkSource().addRegionTicket(TICKET, pos, TICKET_DISTANCE, pos))).get(60, TimeUnit.SECONDS);
            List<? extends CompletableFuture<?>> futures = server.submit(() -> chunks.stream().map(pos -> level.getChunkSource().getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true)).toList()).get(30, TimeUnit.SECONDS);
            for (CompletableFuture<?> future : futures) future.get(180, TimeUnit.SECONDS);
            boolean eligible = server.submit(() -> chunks.stream().allMatch(pos -> level.shouldTickBlocksAt(pos.toLong()))).get(30, TimeUnit.SECONDS);
            result.put("tickEligible", eligible); if (!eligible) throw new IllegalStateException("review chunks not tick eligible");
            server.submit(() -> server.getPlayerList().getPlayers().getFirst().teleportTo(level, selected.x + .5, selected.column.surfaceY + 24D, selected.z + .5, 180F, 55F)).get(30, TimeUnit.SECONDS);
            long start = server.submit(level::getGameTime).get(30, TimeUnit.SECONDS);
            long tickDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
            while (server.submit(level::getGameTime).get(30, TimeUnit.SECONDS) - start < TICKS) {
                if (System.nanoTime() > tickDeadline) throw new IllegalStateException("site did not advance 100 ticks");
                Thread.sleep(50L);
            }
            result.put("reachedServerTicks", server.submit(level::getGameTime).get(30, TimeUnit.SECONDS) - start);
            Column actual = server.submit(() -> observe(level, selected.x, selected.z)).get(30, TimeUnit.SECONDS);
            if (selected.inspection != null && "root".equals(selected.inspection.request.kind)) {
                InspectionResolution settled = server.submit(() -> resolveSettledRoot(level, selected.inspection.request, windows)).get(30, TimeUnit.SECONDS);
                Column settledAnchor = server.submit(() -> observe(level, settled.resolved.x, settled.resolved.z)).get(30, TimeUnit.SECONDS);
                selected.reanchor(settledAnchor, settled);
                if (!ticketSet.containsAll(nearby(selected.x, selected.z)))
                    throw new IllegalStateException("settled root anchor lies outside ticketed capture union");
                actual = settledAnchor;
                result.put("x", selected.x); result.put("z", selected.z); result.put("biome", selected.biome);
                result.put("column", selected.column.toMap()); result.put("inspectionRequest", settled.toMap());
            }
            result.put("settledColumn", actual.toMap());
            List<Column> currentInspectionEndpoints = List.of();
            List<Column> currentProfile = List.of();
            if (selected.inspection != null) {
                List<Column> currentEndpoints = new ArrayList<>();
                for (Column endpoint : selected.inspection.endpoints)
                    currentEndpoints.add(server.submit(() -> observe(level, endpoint.x, endpoint.z)).get(30, TimeUnit.SECONDS));
                Map<String, Object> observations = new LinkedHashMap<>();
                observations.put("resolved", actual.toMap());
                observations.put("endpoints", currentEndpoints.stream().map(Column::toMap).toList());
                result.put("inspectionCurrentObservations", observations);
                if (selected.inspection.candidateCount > 0 && !inspectionMatches(selected.inspection, actual, currentEndpoints))
                    throw new IllegalStateException("inspection observation changed after census: " + selected.inspection.request.id);
                currentInspectionEndpoints = currentEndpoints;
                if (("bank".equals(selected.inspection.request.kind) || "boundary".equals(selected.inspection.request.kind))
                        && currentEndpoints.size() == 2) currentProfile = server.submit(() -> profilePath(level, currentEndpoints.get(0), currentEndpoints.get(1))).get(30, TimeUnit.SECONDS);
                if (!currentProfile.isEmpty()) observations.put("profileSegments", currentProfile.stream().map(Column::toMap).toList());
            }
            Column captureActual = actual;
            GameType priorGameMode = null;
            boolean underwaterDetail = captureActual.waterDepth > 0;
            boolean priorHud = setHudHidden(true);
            try {
                if (underwaterDetail) {
                    priorGameMode = server.submit(() -> server.getPlayerList().getPlayers().getFirst().gameMode.getGameModeForPlayer()).get(30, TimeUnit.SECONDS);
                    server.submit(() -> server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SPECTATOR)).get(30, TimeUnit.SECONDS);
                    if (!awaitClientSpectator()) throw new IllegalStateException("client did not confirm spectator mode for underwater detail");
                }
                double aerialX = selected.x + 16.5, aerialZ = selected.z + 16.5;
                double aerialY = actual.groundY + Math.max(32D, actual.waterDepth + 32D);
                images.add(capture(selected, runId, "aerial", aerialX, aerialY, aerialZ,
                        aimYaw(aerialX, aerialZ, selected.x + .5, selected.z + .5),
                        aimPitch(aerialX, aerialY + 1.62, aerialZ, selected.x + .5, actual.groundY + .5, selected.z + .5), "none", null, null, null));
                ViewTarget sideTarget = inspectionTarget(selected.inspection, actual, currentInspectionEndpoints, false);
                List<Vec3> contextTargets = !currentProfile.isEmpty() ? profileTargets(currentProfile)
                        : server.submit(() -> rootTargets(level, captureActual, selected.inspection)).get(30, TimeUnit.SECONDS);
                Camera side = server.submit(() -> openSideCamera(level, sideTarget.x, sideTarget.z,
                        (int) Math.floor(sideTarget.y), contextTargets)).get(30, TimeUnit.SECONDS);
                images.add(capture(selected, runId, "side", side.x, side.y, side.z,
                        aimYaw(side.x, side.z, sideTarget.x + .5, sideTarget.z + .5),
                        aimPitch(side.x, side.y + 1.62, side.z, sideTarget.x + .5, sideTarget.y, sideTarget.z + .5), side.obstruction, side.lineOfSight,
                        side.visibilityClearRays, side.visibilityRayCount));
                ViewTarget detailTarget = inspectionTarget(selected.inspection, actual, currentInspectionEndpoints, true);
                List<Vec3> detailContext = !currentProfile.isEmpty() ? detailProfileTargets(currentProfile)
                        : server.submit(() -> rootTargets(level, captureActual, selected.inspection)).get(30, TimeUnit.SECONDS);
                List<Column> detailProfile = currentProfile;
                Camera detail = server.submit(() -> openDetailCamera(level, detailTarget.x, detailTarget.z, captureActual.groundY,
                        underwaterDetail, detailTarget.y, detailContext, detailProfile)).get(30, TimeUnit.SECONDS);
                Map<String, Object> detailImage = capture(selected, runId, "detail", detail.x, detail.y, detail.z,
                        aimYaw(detail.x, detail.z, detailTarget.x + .5, detailTarget.z + .5),
                        aimPitch(detail.x, detail.y + 1.62, detail.z, detailTarget.x + .5, detailTarget.y, detailTarget.z + .5), detail.obstruction, detail.lineOfSight,
                        detail.visibilityClearRays, detail.visibilityRayCount);
                detailImage.put("eyeMedium", server.submit(() -> eyeMedium(level, detail)).get(30, TimeUnit.SECONDS));
                detailImage.put("gameMode", underwaterDetail ? GameType.SPECTATOR.getName() : "unchanged");
                images.add(detailImage);
            } finally {
                setHudHidden(priorHud);
                if (priorGameMode != null) {
                    GameType restoreGameMode = priorGameMode;
                    server.submit(() -> server.getPlayerList().getPlayers().getFirst().setGameMode(restoreGameMode)).get(30, TimeUnit.SECONDS);
                }
            }
            result.put("images", images); result.put("status", "captured");
        } catch (Throwable failure) { result.put("status", "failed"); result.put("error", failure.toString()); LOG.error("Biome visual capture failed at {}", selected.id, failure); }
        finally { try { server.submit(() -> chunks.forEach(pos -> level.getChunkSource().removeRegionTicket(TICKET, pos, TICKET_DISTANCE, pos))).get(60, TimeUnit.SECONDS); } catch (Throwable cleanup) { result.put("ticketCleanupError", cleanup.toString()); result.put("status", "failed"); } }
        return result;
    }

    private static Map<String, Object> capture(Selected site, String runId, String view, double x, double y, double z, float yaw, float pitch,
                                                String obstruction, Boolean lineOfSight, Integer visibilityClearRays,
                                                Integer visibilityRayCount) throws Exception {
        String filename = "td-biome-review-" + runId + "-" + safe(site.id) + "-" + view + ".png";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120); long moved = -1;
        while (System.nanoTime() < deadline) {
            CompletableFuture<Boolean> arrived = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> { try { Minecraft mc = Minecraft.getInstance(); boolean ready = clientNeighborhoodArrived(mc, site.x, site.z); if (ready) camera(mc, x, y, z, yaw, pitch); arrived.complete(ready); } catch (Throwable failure) { arrived.completeExceptionally(failure); } });
            if (!arrived.get(30, TimeUnit.SECONDS)) { Thread.sleep(250); continue; }
            if (moved < 0) { moved = System.nanoTime(); Thread.sleep(250); continue; }
            if (System.nanoTime() - moved < TimeUnit.SECONDS.toNanos(3)) { Thread.sleep(100); continue; }
            CompletableFuture<String> saved = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> { try { Minecraft mc = Minecraft.getInstance(); camera(mc, x, y, z, yaw, pitch); Screenshot.grab(mc.gameDirectory, filename, mc.getMainRenderTarget(), message -> saved.complete(message.getString())); } catch (Throwable failure) { saved.completeExceptionally(failure); } });
            String message = saved.get(30, TimeUnit.SECONDS); Path path = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve(filename).toAbsolutePath();
            long files = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (!Files.isRegularFile(path) && System.nanoTime() < files) Thread.sleep(50);
            if (!Files.isRegularFile(path)) throw new IllegalStateException("screenshot callback did not create " + filename);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("view", view); result.put("path", path.toString());
            result.put("camera", Map.of("x", x, "y", y, "z", z, "yaw", yaw, "pitch", pitch));
            result.put("obstruction", obstruction); result.put("lineOfSight", lineOfSight);
            result.put("visibilityClearRays", visibilityClearRays); result.put("visibilityRayCount", visibilityRayCount);
            result.put("renderDelaySeconds", 3); result.put("message", message);
            return result;
        }
        throw new IllegalStateException("client did not render " + site.id + "/" + view);
    }

    private static void camera(Minecraft mc, double x, double y, double z, float yaw, float pitch) {
        mc.player.setDeltaMovement(0, 0, 0); mc.player.setPos(x, y, z); mc.player.setYRot(yaw); mc.player.setXRot(pitch); mc.player.yRotO = yaw; mc.player.xRotO = pitch;
    }
    private static float aimYaw(double cameraX, double cameraZ, double targetX, double targetZ) {
        return (float) Math.toDegrees(Math.atan2(-(targetX - cameraX), targetZ - cameraZ));
    }
    private static float aimPitch(double cameraX, double cameraY, double cameraZ, double targetX, double targetY, double targetZ) {
        double horizontal = Math.hypot(targetX - cameraX, targetZ - cameraZ);
        return (float) -Math.toDegrees(Math.atan2(targetY - cameraY, horizontal));
    }
    private static ViewTarget inspectionTarget(InspectionResolution inspection, Column actual, List<Column> currentEndpoints,
                                              boolean detail) {
        if (inspection == null) return new ViewTarget(actual.x, actual.z, detailTargetY(actual.groundY));
        if ("root".equals(inspection.request.kind) && inspection.rootConnectivity != null
                && !inspection.rootConnectivity.path.isEmpty()) {
            List<RootBlock> path = inspection.rootConnectivity.path; double x=0,z=0,y=0;
            for (RootBlock block : path) { x+=block.pos.getX(); z+=block.pos.getZ(); y+=block.pos.getY(); }
            return new ViewTarget((int) Math.round(x/path.size()), (int) Math.round(z/path.size()), y/path.size() + .5);
        }
        if ("root".equals(inspection.request.kind) && actual.support.lowestLogY != null)
            return new ViewTarget(actual.x, actual.z, actual.support.lowestLogY + .05);
        if (currentEndpoints.size() == 2) {
            Column a = currentEndpoints.get(0), b = currentEndpoints.get(1);
            return detail ? new ViewTarget((a.x + b.x) / 2, (a.z + b.z) / 2, (a.groundY + b.groundY) / 2D + 1D)
                    : new ViewTarget((a.x + b.x) / 2, (a.z + b.z) / 2, (a.groundY + b.groundY) / 2D + 1D);
        }
        return new ViewTarget(actual.x, actual.z, detailTargetY(actual.groundY));
    }
    private static boolean inspectionMatches(InspectionResolution inspection, Column actual, List<Column> endpoints) {
        return switch (inspection.request.kind) {
            case "root" -> inspection.rootConnectivity != null || groundedLog(actual);
            case "underwater" -> actual.exposedWaterBed && !actual.covered && actual.waterDepth >= 2;
            case "bank" -> endpoints.size() == 2 && Math.abs(endpoints.get(0).groundY - endpoints.get(1).groundY) >= 2;
            case "boundary" -> endpoints.size() == 2 && !endpoints.get(0).bedBlock.equals(endpoints.get(1).bedBlock);
            default -> false;
        };
    }
    private static boolean clientNeighborhoodArrived(Minecraft mc, int x, int z) {
        if (mc.level == null || mc.player == null) return false;
        int cx = x >> 4, cz = z >> 4;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            if (mc.level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false) == null) return false;
        return true;
    }
    private static boolean setHudHidden(boolean hidden) throws Exception {
        CompletableFuture<Boolean> value = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> { try { Minecraft mc = Minecraft.getInstance(); boolean old = mc.options.hideGui; mc.options.hideGui = hidden; value.complete(old); } catch (Throwable failure) { value.completeExceptionally(failure); } });
        return value.get(30, TimeUnit.SECONDS);
    }
    private static boolean awaitClientSpectator() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            CompletableFuture<Boolean> value = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> { try {
                Minecraft mc = Minecraft.getInstance();
                value.complete(mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR);
            } catch (Throwable failure) { value.completeExceptionally(failure); } });
            if (value.get(30, TimeUnit.SECONDS)) return true;
            Thread.sleep(100L);
        }
        return false;
    }
    private static String eyeMedium(ServerLevel level, Camera camera) {
        BlockPos eye = BlockPos.containing(camera.x, camera.y + 1.62, camera.z);
        BlockState state = level.getBlockState(eye);
        return state.is(Blocks.WATER) ? "minecraft:water" : state.isAir() ? "minecraft:air" : blockId(state);
    }
    /** Finds a two-block-clear camera. Water detail is intentionally submerged so the bed is visible. */
    private static Camera openCamera(ServerLevel level, int startX, int groundY, int startZ, int targetX, int targetZ,
                                     boolean waterDetail) {
        if (waterDetail) return new Camera(targetX + .5, groundY + .7, targetZ + .5, "water-detail camera; shallow water may place eye above surface", null, null, null);
        for (int radius = 0; radius <= 5; radius++) for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
            int x = startX + dx, z = startZ + dz;
            int y = substrate(level, x, z, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1) + 1;
            BlockPos feet = new BlockPos(x, y, z);
            if (level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir())
                return new Camera(x + .5, y, z + .5, "none", null, null, null);
        }
        return new Camera(targetX + .5, groundY + 2.2, targetZ + .5, "no two-block-clear camera found", null, null, null);
    }
    /** Fixed compass search avoids aesthetic selection while proving a clear collider ray to the exact bank target. */
    private static Camera openSideCamera(ServerLevel level, int targetX, int targetZ, int targetGroundY,
                                         List<Vec3> contextTargets) {
        double[][] directions = sideDirections(contextTargets);
        int[][] targetPatch = {{0,0},{3,0},{-3,0},{0,3},{0,-3}};
        Camera best = null;
        for (int radius : new int[] {10, 14, 18, 8, 12, 16}) for (int lift : new int[] {0, 4, 8, 12}) for (double[] direction : directions) {
            int x = (int) Math.floor(targetX + direction[0] * radius), z = (int) Math.floor(targetZ + direction[1] * radius);
            int y = substrate(level, x, z, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1) + 1 + lift;
            BlockPos feet = new BlockPos(x, y, z);
            if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) continue;
            Vec3 eye = new Vec3(x + .5, y + 1.62, z + .5);
            List<Vec3> targets = !contextTargets.isEmpty() ? contextTargets
                    : List.of(new Vec3(targetX + .5, targetGroundY + 1.0, targetZ + .5),
                    new Vec3(targetX + 3.5, targetGroundY + 1.0, targetZ + .5), new Vec3(targetX - 2.5, targetGroundY + 1.0, targetZ + .5),
                    new Vec3(targetX + .5, targetGroundY + 1.0, targetZ + 3.5), new Vec3(targetX + .5, targetGroundY + 1.0, targetZ - 2.5));
            int clear = 0; boolean centreClear = false;
            for (int index = 0; index < targets.size(); index++) {
                Vec3 target = targets.get(index);
                boolean visible = intendedTargetVisible(level.clip(new ClipContext(eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty())), target);
                if (visible) clear++;
                if (index == 0) centreClear = visible;
            }
            int required = contextTargets.isEmpty() ? 4 : targets.size();
            Camera candidate = new Camera(x + .5, y, z + .5,
                    centreClear && clear >= required ? "none" : "outline visibility " + clear + "/" + targets.size(),
                    centreClear && clear >= required, clear, targets.size());
            if (centreClear && clear >= required) return candidate;
            if (best == null || clear > best.visibilityClearRays) best = candidate;
        }
        if (best != null) return new Camera(best.x, best.y, best.z,
                "no side camera reached all required contextual targets " + (contextTargets.isEmpty() ? 4 : best.visibilityRayCount) + "/"
                        + best.visibilityRayCount + " outline visibility", false,
                best.visibilityClearRays, best.visibilityRayCount);
        return new Camera(targetX + .5, targetGroundY + 2.2, targetZ + .5,
                "no two-block-clear side camera in bounded search", false, 0, targetPatch.length);
    }
    /** Contextual root/path targets use a block-face hit as visibility, since their centres are inside solid blocks. */
    static boolean intendedTargetVisible(HitResult hit, Vec3 target) { return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(target) <= .6; }
    static double[][] sideDirections(List<Vec3> targets) {
        double[][] compass={{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,-1},{1,-1}};
        if (targets.size()<2) return compass; Vec3 a=targets.get(0),b=targets.get(targets.size()-1); double dx=b.x-a.x,dz=b.z-a.z,length=Math.hypot(dx,dz); if(length<.001)return compass;
        double[][] out=new double[compass.length+2][];out[0]=new double[]{-dz/length,dx/length};out[1]=new double[]{dz/length,-dx/length};System.arraycopy(compass,0,out,2,compass.length);return out;
    }
    private static List<Vec3> profileTargets(List<Column> path) {
        return path.stream().map(c -> new Vec3(c.x + .5, c.groundY + 1.05, c.z + .5)).toList();
    }
    /** Current settled columns on the complete endpoint line, including every intervening block. */
    private static List<Column> profilePath(ServerLevel level, Column a, Column b) {
        List<Column> out = new ArrayList<>(); int x=a.x,z=a.z,dx=Math.abs(b.x-x),dz=Math.abs(b.z-z),sx=x<b.x?1:-1,sz=z<b.z?1:-1,error=dx-dz;
        while (true) { out.add(observe(level,x,z)); if(x==b.x&&z==b.z)return out; int twice=error*2; if(twice>-dz){error-=dz;x+=sx;} if(twice<dx){error+=dx;z+=sz;} }
    }
    /** Only actual contiguous trunk logs above a confirmed support are camera targets. */
    private static List<Vec3> rootTargets(ServerLevel level, Column actual, InspectionResolution inspection) {
        if (inspection == null || !"root".equals(inspection.request.kind)) return List.of();
        if (inspection.rootConnectivity != null && !inspection.rootConnectivity.path.isEmpty())
            return inspection.rootConnectivity.path.stream().map(block -> new Vec3(block.pos.getX() + .5, block.pos.getY() + .5, block.pos.getZ() + .5)).toList();
        if (!groundedLog(actual)) return List.of();
        List<Vec3> out = new ArrayList<>(); out.add(new Vec3(actual.x+.5, actual.groundY+1.05, actual.z+.5));
        for (int y=actual.support.lowestLogY; y<=actual.support.lowestLogY+2; y++) {
            if (!level.getBlockState(new BlockPos(actual.x,y,actual.z)).is(BlockTags.LOGS)) break;
            out.add(new Vec3(actual.x+.5,y+.5,actual.z+.5));
        }
        return out;
    }
    /** Short oblique detail view frames several bed blocks and the adjoining toe instead of a single texture. */
    private static Camera openDetailCamera(ServerLevel level, int targetX, int targetZ, int targetGroundY, boolean water,
                                           double centreTargetY, List<Vec3> contextTargets, List<Column> profile) {
        int[][] directions = detailDirections(targetX, targetZ, profile);
        int[][] targetPatch = {{0,0},{1,0},{-1,0},{0,1},{0,-1}};
        Camera best = null;
        double[] radii = !profile.isEmpty() && water ? new double[] {4, 6, 8, 3, 2, 1.5} : new double[] {1.5, 2, 3, 4, 6};
        for (double radius : radii) for (int lift : new int[] {0, 1}) for (int[] direction : directions) {
            int x = (int) Math.floor(targetX + direction[0] * radius), z = (int) Math.floor(targetZ + direction[1] * radius);
            int localGroundY = substrate(level, x, z, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1);
            int localTopWater = topWater(level, x, z, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1);
            List<Double> eyes = water ? underwaterEyes(localGroundY, localTopWater, lift) : List.of(localGroundY + 2.62 + lift);
            for (double eyeY : eyes) {
            double y = eyeY - 1.62;
            BlockPos eyeBlock = BlockPos.containing(x + .5, eyeY, z + .5);
            BlockState eyeState = level.getBlockState(eyeBlock);
            if (water ? !eyeState.is(Blocks.WATER) : !eyeState.isAir()) continue;
            if (!water && !level.getBlockState(eyeBlock.above()).isAir()) continue;
            Vec3 eye = new Vec3(x + .5, eyeY, z + .5);
            List<Vec3> targets = contextTargets.isEmpty() ? List.of() : contextTargets;
            int clear = 0; boolean centreClear = false;
            int targetCount = targets.isEmpty() ? targetPatch.length : targets.size();
            for (int index = 0; index < targetCount; index++) {
                int[] offset = targetPatch[Math.min(index, targetPatch.length - 1)];
                int targetSurfaceY = offset[0] == 0 && offset[1] == 0 ? targetGroundY
                        : substrate(level, targetX + offset[0], targetZ + offset[1],
                                level.getHeight(Heightmap.Types.WORLD_SURFACE, targetX + offset[0], targetZ + offset[1]) - 1);
                Vec3 target = targets.isEmpty() ? new Vec3(targetX + offset[0] + .5,
                        index == 0 ? centreTargetY : detailTargetY(targetSurfaceY), targetZ + offset[1] + .5) : targets.get(index);
                if (!nearEyeOutlineClear(level, eye, target)) continue;
                HitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
                boolean visible = intendedTargetVisible(hit, target);
                if (visible) clear++;
                if (index == 0) centreClear = visible;
            }
            int required = targets.isEmpty() ? 4 : targetCount;
            Camera candidate = new Camera(x + .5, y, z + .5,
                    centreClear && clear >= required ? "none" : "detail outline visibility " + clear + "/" + targetCount,
                    centreClear && clear >= required, clear, targetCount);
            if (centreClear && clear >= required) return candidate;
            if (best == null || clear > best.visibilityClearRays) best = candidate;
            }
        }
        if (best != null) return new Camera(best.x, best.y, best.z,
                "no detail camera reached all required contextual targets " + best.visibilityRayCount + "/" + best.visibilityRayCount + " outline visibility", false,
                best.visibilityClearRays, best.visibilityRayCount);
        return new Camera(targetX + .5, targetGroundY + (water ? 1.0 : 2.2), targetZ + .5,
                "no clear oblique detail camera in bounded search", false, 0, targetPatch.length);
    }
    static List<Double> underwaterEyes(int groundY, int topWater, int lift) {
        if (topWater == Integer.MIN_VALUE || topWater <= groundY) return List.of();
        List<Double> eyes = new ArrayList<>();
        for (double value : new double[] {groundY + 1.2 + lift, Math.min(groundY + 2.2 + lift, topWater + .7), topWater + .7})
            if (value > groundY + 1 && value < topWater + 1 && eyes.stream().noneMatch(other -> Math.abs(other-value)<.001)) eyes.add(value);
        return eyes;
    }
    /** Bed, lower face, mid face, upper face and crest: solid face hits near these are valid evidence. */
    private static List<Vec3> detailProfileTargets(List<Column> path) {
        if (path.isEmpty()) return List.of(); List<Vec3> out = new ArrayList<>();
        for (int i : new int[] {0, path.size()/4, path.size()/2, (path.size()*3)/4, path.size()-1}) {
            Column c=path.get(i); Vec3 point=new Vec3(c.x+.5,c.groundY+1.05,c.z+.5);
            if (!out.contains(point)) out.add(point);
        } return out;
    }
    /** For a contextual underwater bank, face the crest from the toe side before generic compass fallback. */
    static int[][] detailDirections(int targetX, int targetZ, List<Column> profile) {
        int[][] standard={{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,-1},{1,-1}};
        if (profile.isEmpty()) return standard; Column crest=profile.get(0),toe=profile.get(profile.size()-1);
        int dx=Integer.signum(toe.x-crest.x), dz=Integer.signum(toe.z-crest.z); if(dx==0&&dz==0)return standard;
        int[][] out=new int[standard.length][]; out[0]=new int[]{dx,dz}; int k=1;
        for(int[] d:standard) if(!(d[0]==dx&&d[1]==dz)) out[k++]=d; return out;
    }
    private static boolean nearEyeOutlineClear(ServerLevel level, Vec3 eye, Vec3 target) {
        Vec3 delta = target.subtract(eye); double distance = delta.length();
        if (distance <= .001) return false;
        Vec3 buffer = eye.add(delta.scale(Math.min(.75, distance) / distance));
        return level.clip(new ClipContext(eye, buffer, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                CollisionContext.empty())).getType() == HitResult.Type.MISS;
    }
    /** Keep outline rays above the substrate: targeting inside the bed makes every ray collide. */
    static double detailTargetY(int groundY) { return groundY + 1.05; }
    private static List<ChunkPos> nearby(int x, int z) { List<ChunkPos> out = new ArrayList<>(); int cx = x >> 4, cz = z >> 4; for (int dz=-RADIUS; dz<=RADIUS; dz++) for (int dx=-RADIUS; dx<=RADIUS; dx++) out.add(new ChunkPos(cx+dx, cz+dz)); return out; }
    /** A 5x5 request ticket square contains the 3x3 camera neighborhoods of any root found in request Chebyshev radius 12. */
    static List<ChunkPos> rootRequestTickets(int x, int z) { List<ChunkPos> out=new ArrayList<>();int cx=x>>4,cz=z>>4;for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++)out.add(new ChunkPos(cx+dx,cz+dz));return out; }
    private static String safe(String input) { return input.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String blockId(BlockState state) { return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }
    private static void atomic(Path path, Object value) throws IOException { Path temp = path.resolveSibling(path.getFileName() + ".tmp"); Files.writeString(temp, JSON.toJson(value)); Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }

    private record Window(String id, int minX, int maxX, int minZ, int maxZ) { }
    private record Explicit(String id, int x, int z) { }
    private record InspectionRequest(String id, int x, int z, String kind) { }
    private record Profile(Column crest, Column toe) { }
    private record InspectionResolution(InspectionRequest request, Column resolved, int candidateCount, String reason,
                                        List<Column> endpoints, RootConnectivity rootConnectivity) {
        InspectionResolution(InspectionRequest request, Column resolved, int candidateCount, String reason, List<Column> endpoints) {
            this(request, resolved, candidateCount, reason, endpoints, null);
        }
        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", request.id); result.put("kind", request.kind);
            result.put("requested", Map.of("x", request.x, "z", request.z));
            result.put("resolved", Map.of("x", resolved.x, "z", resolved.z));
            result.put("candidateCount", candidateCount); result.put("reason", reason);
            result.put("endpoints", endpoints.stream().map(c -> Map.of("x", c.x, "z", c.z)).toList());
            if (rootConnectivity != null) result.put("rootConnectivity", rootConnectivity.toMap());
            return result;
        }
    }
    private record RootWalk(List<BlockPos> path, boolean complete, boolean capped, boolean boundary) { }
    private record RootBlock(BlockPos pos, String block) { Map<String, Object> toMap(){ return Map.of("x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),"block",block); } }
    private record RootConnectivity(BlockPos root, List<RootBlock> path, boolean complete, boolean grounded, String reason, boolean capped) {
        Map<String, Object> toMap(){ Map<String,Object> out=new LinkedHashMap<>(); out.put("complete",complete);out.put("grounded",grounded);out.put("reason",reason);out.put("unknownReason",complete ? null : reason);out.put("visitedCapReached",capped);out.put("path",path.stream().map(RootBlock::toMap).toList());out.put("endpoints",path.isEmpty()?List.of():List.of(path.get(0).toMap(),path.get(path.size()-1).toMap()));return out; }
    }
    private record TracePoint(int x, int z) { }
    /** Fixture-only deterministic light/weather state, restored before the diagnostic exits. */
    private record CaptureLighting(long dayTime, boolean daylightCycle, boolean weatherCycle,
                                   int clearWeatherTime, int rainTime, int thunderTime,
                                   boolean raining, boolean thundering) {
        static CaptureLighting freeze(ServerLevel level) {
            ServerLevelData data = (ServerLevelData) level.getLevelData(); var rules = level.getGameRules();
            CaptureLighting saved = new CaptureLighting(level.getDayTime(), rules.getRule(GameRules.RULE_DAYLIGHT).get(),
                    rules.getRule(GameRules.RULE_WEATHER_CYCLE).get(), data.getClearWeatherTime(), data.getRainTime(),
                    data.getThunderTime(), data.isRaining(), data.isThundering());
            level.setDayTime(6000L);
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
            level.setWeatherParameters(0, 0, false, false);
            return saved;
        }
        void restore(ServerLevel level) {
            ServerLevelData data = (ServerLevelData) level.getLevelData(); var rules = level.getGameRules();
            level.setDayTime(dayTime);
            rules.getRule(GameRules.RULE_DAYLIGHT).set(daylightCycle, null);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(weatherCycle, null);
            data.setClearWeatherTime(clearWeatherTime); data.setRainTime(rainTime); data.setThunderTime(thunderTime);
            data.setRaining(raining); data.setThundering(thundering);
        }
        Map<String, Object> captureMap() { return Map.of("dayTime", 6000L, "daylightCycleFrozen", true,
                "weatherCycleFrozen", true, "raining", false, "thundering", false); }
    }
    private record LogSupport(Integer lowestLogY, String lowestLogBlock, String supportBlock, boolean treeOnMud) { }
    private record WaterExposure(boolean exposed, boolean covered, String coveringBlock) { }
    private record ViewTarget(int x, int z, double y) { }
    private record Camera(double x, double y, double z, String obstruction, Boolean lineOfSight,
                          Integer visibilityClearRays, Integer visibilityRayCount) { }
    private record Column(int x, int z, String biome, int groundY, int surfaceY, int waterDepth, String bedBlock, String substrateClassification, boolean exposedWaterBed, boolean covered, String coveringBlock, LogSupport support, int cardinalHeightDelta, boolean boundary, boolean anomaly) {
        Map<String,Object> toMap() { Map<String,Object> result = new LinkedHashMap<>(); result.put("x",x); result.put("z",z); result.put("biome",biome); result.put("groundY",groundY); result.put("surfaceY",surfaceY); result.put("waterDepth",waterDepth); result.put("bedBlock",bedBlock); result.put("substrateClassification",substrateClassification); result.put("exposedWaterBed",exposedWaterBed); result.put("covered",covered); result.put("coveringBlock",coveringBlock); result.put("lowestLogY",support.lowestLogY); result.put("lowestLogBlock",support.lowestLogBlock); result.put("supportBlock",support.supportBlock); result.put("treeOnMud",support.treeOnMud); result.put("cardinalHeightDelta",cardinalHeightDelta); result.put("boundary",boundary); result.put("anomaly",anomaly); return result; }
    }
    private static final class Selected { final String id; int x,z; String biome; final List<String> reasons; Column column; InspectionResolution inspection; Selected(String id,int x,int z,String biome,List<String> reasons,Column column){this(id,x,z,biome,reasons,column,null);} Selected(String id,int x,int z,String biome,List<String> reasons,Column column,InspectionResolution inspection){this.id=id;this.x=x;this.z=z;this.biome=biome;this.reasons=reasons;this.column=column;this.inspection=inspection;} void reanchor(Column anchor, InspectionResolution resolution){x=anchor.x;z=anchor.z;biome=anchor.biome;column=anchor;inspection=resolution;} }
    private record Selection(List<Selected> sites, List<String> gaps) {
        Map<String,Object> toMap(){ Map<String,Object> out=new LinkedHashMap<>(); out.put("schema",1);out.put("coverageGaps",gaps);out.put("sites",sites.stream().map(Selection::siteMap).toList());return out; }
        private static Map<String,Object> siteMap(Selected site){ Map<String,Object> out=new LinkedHashMap<>();out.put("id",site.id);out.put("x",site.x);out.put("z",site.z);out.put("biome",site.biome);out.put("reasons",site.reasons);out.put("column",site.column.toMap());if(site.inspection!=null)out.put("inspectionRequest",site.inspection.toMap());return out; }
    }
    private record Plan(long seed, int scale, String sourceFingerprint, String artifactSha256, List<String> requiredBiomes, List<Window> windows, List<Explicit> explicit, List<InspectionRequest> inspectionRequests, int maxSites) {
        static Plan parse(Map<String,Object> raw, long exactSeed) {
            if (!(raw.get("schema") instanceof Number schema) || schema.intValue()!=1) throw new IllegalArgumentException("biome review plan schema must be 1");
            if (!(raw.get("seed") instanceof Number seed) || !(raw.get("scale") instanceof Number scale)) throw new IllegalArgumentException("biome review plan requires numeric seed and scale");
            String fingerprint = text(raw.get("sourceFingerprint"), "sourceFingerprint");
            String artifact = text(raw.get("artifactSha256"), "artifactSha256");
            List<String> biomes = strings(raw.get("requiredBiomes"), "requiredBiomes"); if (biomes.isEmpty()) throw new IllegalArgumentException("requiredBiomes is empty");
            List<Window> windows = windows(raw.get("windows")); if (windows.isEmpty()) throw new IllegalArgumentException("windows is empty");
            List<Explicit> explicit = explicit(raw.get("regressionSites"), "regressionSites"); explicit.addAll(explicit(raw.get("explicitSites"), "explicitSites"));
            List<InspectionRequest> inspectionRequests = inspectionRequests(raw.get("inspectionRequests"), windows);
            int max = raw.get("maxSites") instanceof Number n ? n.intValue() : 16; if (max < 1 || max > 64) throw new IllegalArgumentException("maxSites must be 1..64");
            return new Plan(exactSeed, scale.intValue(), fingerprint, artifact, biomes, windows, explicit, inspectionRequests, max);
        }
        @SuppressWarnings("unchecked") static List<String> strings(Object raw,String name){ if (!(raw instanceof List<?> rows)) throw new IllegalArgumentException(name+" must be an array"); List<String> out=new ArrayList<>(); for(Object v:rows){if(!(v instanceof String s)||s.isBlank())throw new IllegalArgumentException(name+" contains invalid id");out.add(s);} return out; }
        @SuppressWarnings("unchecked") static List<Window> windows(Object raw){ if (!(raw instanceof List<?> rows)) throw new IllegalArgumentException("windows must be an array"); List<Window> out=new ArrayList<>(); Set<String> ids=new LinkedHashSet<>(); for(Object row:rows){if(!(row instanceof Map<?,?> map))throw new IllegalArgumentException("window must be an object");String id=text(map.get("id"),"window id");int minX=num(map.get("minX"),"minX"),maxX=num(map.get("maxX"),"maxX"),minZ=num(map.get("minZ"),"minZ"),maxZ=num(map.get("maxZ"),"maxZ");if(!ids.add(id)||minX>maxX||minZ>maxZ||Math.floorMod(minX,16)!=0||Math.floorMod(minZ,16)!=0||Math.floorMod(maxX+1,16)!=0||Math.floorMod(maxZ+1,16)!=0)throw new IllegalArgumentException("window bounds must be unique inclusive whole chunks");out.add(new Window(id,minX,maxX,minZ,maxZ));}return out; }
        @SuppressWarnings("unchecked") static List<Explicit> explicit(Object raw,String name){if(raw==null)return new ArrayList<>();if(!(raw instanceof List<?> rows))throw new IllegalArgumentException(name+" must be an array");List<Explicit> out=new ArrayList<>();for(Object row:rows){if(!(row instanceof Map<?,?> map))throw new IllegalArgumentException(name+" entry must object");out.add(new Explicit(text(map.get("id"),name+" id"),num(map.get("x"),"x"),num(map.get("z"),"z")));}return out;}
        @SuppressWarnings("unchecked") static List<InspectionRequest> inspectionRequests(Object raw, List<Window> windows){if(raw==null)return List.of();if(!(raw instanceof List<?> rows))throw new IllegalArgumentException("inspectionRequests must be an array");List<InspectionRequest> out=new ArrayList<>();Set<String> ids=new LinkedHashSet<>();for(Object row:rows){if(!(row instanceof Map<?,?> map))throw new IllegalArgumentException("inspection request must object");String id=text(map.get("id"),"inspection request id"),kind=text(map.get("kind"),"inspection request kind");if(!Set.of("root","bank","underwater","boundary").contains(kind))throw new IllegalArgumentException("unsupported inspection request kind "+kind);int x=num(map.get("x"),"inspection request x"),z=num(map.get("z"),"inspection request z");if(!ids.add(id)||!windows.stream().anyMatch(w->x>=w.minX&&x<=w.maxX&&z>=w.minZ&&z<=w.maxZ))throw new IllegalArgumentException("inspection request id must be unique and inside a window");out.add(new InspectionRequest(id,x,z,kind));}return out;}
        static String text(Object raw,String name){if(!(raw instanceof String value)||value.isBlank())throw new IllegalArgumentException(name+" must be nonblank");return value;} static int num(Object raw,String name){if(!(raw instanceof Number value))throw new IllegalArgumentException(name+" must be number");return value.intValue();}
    }
}
