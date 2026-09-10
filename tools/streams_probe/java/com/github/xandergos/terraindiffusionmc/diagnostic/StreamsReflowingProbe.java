package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.tags.FluidTags;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Temporary opt-in harness compiled only through tools/streams_probe/probe.init.gradle.
 * It deliberately has no compile-time Streams Reflowing dependency.
 */
@EventBusSubscriber(modid = "terrain_diffusion_mc", bus = EventBusSubscriber.Bus.GAME)
public final class StreamsReflowingProbe {
    private static final Logger LOG = LoggerFactory.getLogger("TerrainDiffusionStreamsProbe");
    private static final String PREFIX = "[TD-SR-PROBE] ";
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private StreamsReflowingProbe() {}

    /** Runs during level load, before the server's initial spawn preparation. */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!Boolean.getBoolean("td.streamsProbe") || !(event.getLevel() instanceof ServerLevel level)) return;
        level.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        LOG.info(PREFIX + json("level_controls", "spawnChunkRadius", 0, "doMobSpawning", false));
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("td.streamsProbe") || !STARTED.compareAndSet(false, true)) return;
        MinecraftServer server = event.getServer();
        Thread worker = new Thread(() -> run(server), "td-streams-probe");
        worker.setDaemon(true);
        worker.start();
    }

    private static void run(MinecraftServer server) {
        Config config = Config.read();
        List<String> receipts = new ArrayList<>();
        long started = System.nanoTime();
        String terminal = "success";
        try {
            ServerLevel level = server.overworld();
            auditCustomBiomes(level, receipts);
            if (Boolean.getBoolean("td.streamsProbe.registryOnly")) {
                if (Boolean.getBoolean("td.streamsProbe.nonTdShieldCheck")) {
                    emit(receipts, server.submit(() -> auditNonTdShield(level)).get(config.timeoutSeconds, TimeUnit.SECONDS));
                }
                return;
            }
            if (!config.centerExplicit) {
                BlockPos spawn = level.getSharedSpawnPos();
                config = config.withCenter(spawn.getX() >> 4, spawn.getZ() >> 4);
            }
            emit(receipts, json("start", "center", config.centerX + "," + config.centerZ,
                    "centerSource", config.centerExplicit ? "property" : "shared_spawn", "maxChunks", config.maxChunks,
                    "requestTimeoutSeconds", config.timeoutSeconds, "routeSearchRadius", config.routeSearchRadius));
            List<RouteCandidate> candidates = config.fixedCandidates.isEmpty()
                    ? discoverRoutes(level, config, receipts) : config.fixedCandidates;
            if (!config.fixedCandidates.isEmpty()) {
                emit(receipts, json("route_discovery", "status", "fixed_candidates_no_streams_route_query",
                        "candidates", candidates.size(), "candidateSummary", candidates.toString()));
            }
            if (candidates.isEmpty()) {
                terminal = "incomplete";
                emit(receipts, json("route_discovery", "status", "no_route_candidates;no_chunks_generated"));
            }
            for (RouteCandidate candidate : candidates) {
                    final int chunkX = candidate.chunkX;
                    final int chunkZ = candidate.chunkZ;
                    CompletableFuture<ChunkReceipt> future = server.submit(() -> inspectChunk(level, chunkX, chunkZ));
                    try {
                        ChunkReceipt receipt = future.get(config.timeoutSeconds, TimeUnit.SECONDS);
                        // Fetching TD pipeline data occurs after the server task returns, so no server-thread lock is held.
                        receipt.preCarve = preCarveHeight(receipt.sampleX, receipt.sampleZ);
                        receipt.baseMinusPreCarve = receipt.baseHeight - receipt.preCarve;
                        receipt.plannedChannelColumns = candidate.channelColumns;
                        receipt.plannedWaterSurfaceY = candidate.waterSurfaceY;
                        receipt.plannedChannelSamples = candidate.samples;
                        if (Boolean.getBoolean("td.streamsProbe.planAudit")) {
                            auditPlanAfterGeneration(level, receipt);
                            writePlanAuditCsv(server, receipt);
                        }
                        writeColumnCsv(server, receipt);
                        emit(receipts, receipt.json());
                        if (Boolean.getBoolean("td.streamsProbe.shieldAudit")) {
                            emit(receipts, server.submit(() -> auditShield(level, chunkX, chunkZ))
                                    .get(config.timeoutSeconds, TimeUnit.SECONDS));
                        }
                    } catch (Exception e) {
                        terminal = "incomplete";
                        emit(receipts, json("chunk_error", "chunk", chunkX + "," + chunkZ,
                                "error", e.getClass().getSimpleName(), "message", safe(e.getMessage())));
                        if (!future.isDone()) future.cancel(true);
                        // A timed-out generation task can still occupy the server executor: do not queue more work.
                        break;
                    }
            }
        } catch (Throwable t) {
            terminal = "error";
            emit(receipts, json("fatal", "error", t.getClass().getName(), "message", safe(t.getMessage())));
        } finally {
            emit(receipts, json("complete", "status", terminal, "elapsedMs",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    "surfaceConvention", "tdPreCarveIsDensityZeroBoundary;expectedTopSolid=preCarve-1;baseHeightIsComparedToBoundary"));
            writeReceipts(server, receipts);
            if (config.stopWhenDone) {
                server.submit(() -> server.halt(false));
            }
        }
    }

    private static void auditCustomBiomes(ServerLevel level, List<String> receipts) {
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        biomes.holders().filter(b -> b.key().location().getNamespace().equals("terrain-diffusion-mc"))
                .forEach(b -> emit(receipts, json("biome_registry", "biome", b.key().location(),
                        "tags", b.tags().map(t -> t.location().toString()).sorted().toList(),
                        "eligibleStructures", structures.holders().filter(s -> s.value().biomes().contains(b))
                                .map(s -> s.key().location().toString()).sorted().toList(),
                        "streamsFeatures", b.value().getGenerationSettings().features().stream()
                                .flatMap(set -> set.stream()).flatMap(f -> f.unwrapKey().stream())
                                .map(k -> k.location().toString()).filter(k -> k.startsWith("streamsreflowing:"))
                                .sorted().toList())));
    }

    private static String auditNonTdShield(ServerLevel level) {
        try {
            if (level.getChunkSource().getGenerator().getBiomeSource()
                    instanceof com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource) {
                throw new IllegalStateException("Non-TD audit requires a vanilla biome source");
            }
            Class<?> carver = Class.forName("dev.streamsreflowing.worldgen.RiverCarverFeature");
            Method handler = java.util.Arrays.stream(carver.getDeclaredMethods())
                    .filter(m -> m.getName().contains("terrainDiffusion$replaceMineshaftAggregateShield")
                            && m.getParameterCount() == 3).findFirst().orElseThrow();
            handler.setAccessible(true);
            int[] sentinel = {1, 2, 3, 4, 5};
            var callback = new org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<int[]>("probe", true, sentinel);
            handler.invoke(null, level, new net.minecraft.world.level.ChunkPos(0, 0), callback);
            if (callback.getReturnValue() != sentinel || callback.isCancelled()) {
                throw new IllegalStateException("Non-TD callback altered upstream bounds");
            }
            return json("non_td_shield", "status", "unchanged", "actualInjectedHandler", handler.getName());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Non-TD injected handler audit failed", e);
        }
    }

    private static String auditShield(ServerLevel level, int chunkX, int chunkZ) {
        try {
            Class<?> carver = Class.forName("dev.streamsreflowing.worldgen.RiverCarverFeature");
            Method collector = carver.getDeclaredMethod("a", net.minecraft.world.level.WorldGenLevel.class,
                    net.minecraft.world.level.ChunkPos.class);
            collector.setAccessible(true);
            int[] bounds = (int[]) collector.invoke(null, level, new net.minecraft.world.level.ChunkPos(chunkX, chunkZ));
            int x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
            int y = level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
                    level, level.getChunkSource().randomState()) - 1;
            boolean shielded = false;
            for (int i = 0; i + 4 < bounds.length; i += 5) {
                if (x >= bounds[i] && z >= bounds[i + 1] && x <= bounds[i + 2]
                        && z <= bounds[i + 3] && bounds[i + 4] >= y - 6) shielded = true;
            }
            return json("structure_shield", "chunk", chunkX + "," + chunkZ, "sample", x + "," + y + "," + z,
                    "tupleCount", bounds.length / 5, "shielded", shielded);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Supported Streams shield collector audit failed", e);
        }
    }

    private static ChunkReceipt inspectChunk(ServerLevel level, int chunkX, int chunkZ) {
        // ServerLevel#getChunk returns a FULL LevelChunk and drives normal generation if it is absent.
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        int sea = generator.getSeaLevel();
        ChunkReceipt result = new ChunkReceipt(chunkX, chunkZ, sea);
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int x = (chunkX << 4) + localX;
                int z = (chunkZ << 4) + localZ;
                int baseHeight = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
                        level, level.getChunkSource().randomState());
                String naturalBiome = generator.getBiomeSource().getNoiseBiome(x >> 2, baseHeight >> 2, z >> 2,
                        level.getChunkSource().randomState().sampler()).unwrapKey().map(ResourceKey::location)
                        .map(id -> id.getNamespace() + ":" + id.getPath()).orElse("unkeyed");
                result.naturalBiomeColumns.merge(naturalBiome, 1, Integer::sum);
                // ChunkAccess#getHeight returns the highest occupied Y for retained runtime heightmaps.
                int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
                BlockPos surfacePos = new BlockPos(x, Math.max(minY, surface), z);
                String biome = level.getBiome(surfacePos).unwrapKey().map(ResourceKey::location)
                        .map(id -> id.getNamespace() + ":" + id.getPath()).orElse("unkeyed");
                if (biome.endsWith(":desert")) result.desertColumns++;
                else if (biome.endsWith(":savanna")) result.savannaColumns++;
                else result.otherBiomeColumns++;
                if (surface >= minY && chunk.getBlockState(surfacePos).getFluidState().is(FluidTags.WATER)) result.surfaceWaterColumns++;
                int topWaterY = minY - 1;
                int groundY = minY - 1;
                long replacingSolid = 0;
                for (int y = minY; y < maxY; y++) {
                    if (chunk.getBlockState(new BlockPos(x, y, z)).getFluidState().is(FluidTags.WATER)) {
                        result.waterBlocks++;
                        if (y > sea) result.waterAboveSeaBlocks++;
                        topWaterY = y;
                        if (y < baseHeight) {
                            replacingSolid++;
                            result.waterReplacingSolid++;
                            result.waterReplacingSolidByNaturalBiome.merge(naturalBiome, 1L, Long::sum);
                            if (result.representativeReplacingSolid == null) {
                                result.representativeReplacingSolid = x + "," + y + "," + z + ";base=" + baseHeight;
                            }
                        }
                    }
                }
                for (int y = surface; y >= minY; y--) {
                    if (!chunk.getBlockState(new BlockPos(x, y, z)).getFluidState().is(FluidTags.WATER)) {
                        groundY = y;
                        break;
                    }
                }
                result.csvRows.add(x + "," + z + "," + baseHeight + "," + surface + "," + topWaterY + ","
                        + csv(naturalBiome) + "," + replacingSolid);
                result.columns.add(new ColumnSnapshot(x, z, baseHeight, surface, topWaterY, groundY, naturalBiome));
            }
        }
        result.sampleX = (chunkX << 4) + 8;
        result.sampleZ = (chunkZ << 4) + 8;
        result.baseHeight = generator.getBaseHeight(result.sampleX, result.sampleZ, Heightmap.Types.OCEAN_FLOOR_WG,
                level, level.getChunkSource().randomState());
        return result;
    }

    private static int preCarveHeight(int x, int z) {
        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileX = x >> Integer.numberOfTrailingZeros(tileSize);
        int tileZ = z >> Integer.numberOfTrailingZeros(tileSize);
        int blockStartX = tileX << Integer.numberOfTrailingZeros(tileSize);
        int blockStartZ = tileZ << Integer.numberOfTrailingZeros(tileSize);
        LocalTerrainProvider.HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(
                blockStartZ, blockStartX, blockStartZ + tileSize, blockStartX + tileSize);
        if (data == null || data.heightmap == null || data.height == 0 || data.width == 0) {
            throw new IllegalStateException("Terrain Diffusion heightmap unavailable");
        }
        return HeightConverter.convertToMinecraftHeight(data.heightmap[z - blockStartZ][x - blockStartX]);
    }

    private static List<RouteCandidate> discoverRoutes(ServerLevel level, Config config, List<String> receipts) {
        List<RouteCandidate> result = new ArrayList<>();
        try {
            Class<?> type = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen", false,
                    StreamsReflowingProbe.class.getClassLoader());
            Object engine = type.getMethod("engineIfPresent", ServerLevel.class).invoke(null, level);
            if (engine == null) {
                // With spawn radius zero, no terrain feature has initialized Streams yet.
                // Use its normal lazy factory; absence at ServerStarted is not incompatibility.
                engine = level.getServer().submit(() -> {
                    try {
                        return type.getMethod("engine", net.minecraft.world.level.WorldGenLevel.class).invoke(null, level);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("Streams engine initialization failed", e);
                    }
                }).get(config.timeoutSeconds, TimeUnit.SECONDS);
                emit(receipts, json("engine_initialized", "present", engine != null));
            }
            Method near = engine.getClass().getMethod("anyStreamsNear", double.class, double.class, double.class);
            Method plan = engine.getClass().getMethod("plan", int.class, int.class);
            Method inChannel = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan", false,
                    StreamsReflowingProbe.class.getClassLoader()).getMethod("inChannel");
            Method waterSurfaceY = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan", false,
                    StreamsReflowingProbe.class.getClassLoader()).getMethod("waterSurfaceY");
            int examined = 0;
            for (int dz = -config.routeSearchRadius; dz <= config.routeSearchRadius && result.size() < config.maxChunks; dz++) {
                for (int dx = -config.routeSearchRadius; dx <= config.routeSearchRadius && result.size() < config.maxChunks; dx++) {
                    int cx = config.centerX + dx, cz = config.centerZ + dz;
                    examined++;
                    if (!((Boolean) near.invoke(engine, cx * 16.0 + 8.0, cz * 16.0 + 8.0, 16.0))) continue;
                    int channels = 0, waterY = Integer.MIN_VALUE;
                    for (int z = 0; z < 16; z += 4) for (int x = 0; x < 16; x += 4) {
                        Object column = plan.invoke(engine, cx * 16 + x, cz * 16 + z);
                        if ((Boolean) inChannel.invoke(column)) { channels++; waterY = Math.max(waterY, (Integer) waterSurfaceY.invoke(column)); }
                    }
                    if (channels > 0) {
                        List<String> samples = new ArrayList<>();
                        for (int z = 0; z < 16; z += 4) for (int x = 0; x < 16; x += 4) {
                            Object column = plan.invoke(engine, cx * 16 + x, cz * 16 + z);
                            if ((Boolean) inChannel.invoke(column)) samples.add((cx * 16 + x) + "," + (cz * 16 + z));
                        }
                        result.add(new RouteCandidate(cx, cz, channels, waterY, String.join(";", samples)));
                    }
                }
            }
            emit(receipts, json("route_discovery", "status", "engine_route_api", "chunksExamined", examined,
                    "candidates", result.size(), "candidateSummary", result.toString()));
        } catch (Throwable t) {
            emit(receipts, json("route_discovery", "status", "unavailable", "error", t.getClass().getSimpleName(), "message", safe(t.getMessage())));
        }
        return result;
    }

    private static void emit(List<String> receipts, String receipt) { LOG.info(PREFIX + receipt); receipts.add(receipt); }

    private static void writeReceipts(MinecraftServer server, List<String> receipts) {
        Path output = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("td-streams-probe.jsonl");
        try {
            Files.write(output, receipts);
            LOG.info(PREFIX + json("file", "path", output.toString(), "records", receipts.size()));
        } catch (IOException e) {
            LOG.error(PREFIX + json("file_error", "error", e.getClass().getSimpleName(), "message", safe(e.getMessage())));
        }
    }

    /** CSV is assembled on the server task but written by the probe worker after the chunk request completes. */
    private static void writeColumnCsv(MinecraftServer server, ChunkReceipt receipt) {
        Path output = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("td-streams-probe-columns-" + receipt.chunkX + "-" + receipt.chunkZ + ".csv");
        List<String> lines = new ArrayList<>(receipt.csvRows.size() + 1);
        lines.add("x,z,baseHeightOceanFloorWG,surfaceY,topWaterY,naturalBiome,waterReplacingSolid");
        lines.addAll(receipt.csvRows);
        try {
            Files.write(output, lines);
            receipt.csvFile = output.getFileName().toString();
        } catch (IOException e) {
            receipt.csvFile = "write_error:" + e.getClass().getSimpleName();
        }
    }

    /**
     * This runs only after the requested FULL chunk future completes. It asks an already initialized
     * Streams engine for its per-column plans; it never initializes an engine or requests a chunk.
     */
    private static void auditPlanAfterGeneration(ServerLevel level, ChunkReceipt receipt) {
        try {
            ClassLoader loader = StreamsReflowingProbe.class.getClassLoader();
            Class<?> worldgen = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen", false, loader);
            Object engine = worldgen.getMethod("engineIfPresent", ServerLevel.class).invoke(null, level);
            if (engine == null) {
                receipt.planAuditStatus = "engine_absent_after_full_chunk";
                return;
            }
            Method plan = engine.getClass().getMethod("plan", int.class, int.class);
            Class<?> columnPlan = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan", false, loader);
            Method inChannel = columnPlan.getMethod("inChannel");
            Method waterSurfaceY = columnPlan.getMethod("waterSurfaceY");
            Method width = columnPlan.getMethod("width");
            Method distance = columnPlan.getMethod("distance");
            Method slope = columnPlan.getMethod("slope");
            Method bankBlend = columnPlan.getMethod("bankBlend");
            int channelColumns = 0;
            Integer minNaturalGroundMinusWater = null, maxNaturalGroundMinusWater = null;
            for (ColumnSnapshot column : receipt.columns) {
                Object value = plan.invoke(engine, column.x, column.z);
                boolean channel = (Boolean) inChannel.invoke(value);
                int plannedWater = ((Number) waterSurfaceY.invoke(value)).intValue();
                double plannedWidth = ((Number) width.invoke(value)).doubleValue();
                double plannedDistance = ((Number) distance.invoke(value)).doubleValue();
                double plannedSlope = ((Number) slope.invoke(value)).doubleValue();
                double plannedBankBlend = ((Number) bankBlend.invoke(value)).doubleValue();
                // OCEAN_FLOOR_WG is the first-air boundary; the TD natural top solid is baseHeight - 1.
                int naturalGroundMinusWater = (column.baseHeight - 1) - plannedWater;
                int actualWaterMinusPlanned = column.topWaterY - plannedWater;
                if (channel) {
                    channelColumns++;
                    minNaturalGroundMinusWater = minNaturalGroundMinusWater == null ? naturalGroundMinusWater : Math.min(minNaturalGroundMinusWater, naturalGroundMinusWater);
                    maxNaturalGroundMinusWater = maxNaturalGroundMinusWater == null ? naturalGroundMinusWater : Math.max(maxNaturalGroundMinusWater, naturalGroundMinusWater);
                }
                receipt.planAuditRows.add(column.x + "," + column.z + "," + column.baseHeight + "," + column.surfaceY + ","
                        + column.groundY + "," + column.topWaterY + "," + csv(column.naturalBiome) + "," + channel + ","
                        + plannedWater + "," + plannedWidth + "," + plannedDistance + "," + plannedSlope + "," + plannedBankBlend + ","
                        + naturalGroundMinusWater + "," + actualWaterMinusPlanned);
            }
            receipt.planAuditStatus = "engine_plan_after_full_chunk";
            receipt.planAuditChannelColumns = channelColumns;
            receipt.planAuditNaturalGroundMinusWaterRange = channelColumns == 0 ? "no_channel_columns" : minNaturalGroundMinusWater + ".." + maxNaturalGroundMinusWater;
        } catch (Throwable t) {
            receipt.planAuditStatus = "unavailable:" + t.getClass().getSimpleName();
            receipt.planAuditError = safe(t.getMessage());
        }
    }

    private static void writePlanAuditCsv(MinecraftServer server, ChunkReceipt receipt) {
        Path output = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("td-streams-probe-plan-audit-" + receipt.chunkX + "-" + receipt.chunkZ + ".csv");
        List<String> lines = new ArrayList<>(receipt.planAuditRows.size() + 1);
        lines.add("x,z,naturalPreCarveY,actualSurfaceY,actualGroundY,actualTopWaterY,naturalBiome,inChannel,planWaterSurfaceY,planWidth,planDistance,planSlope,planBankBlend,naturalGroundMinusPlanWater,actualWaterMinusPlanWater");
        lines.addAll(receipt.planAuditRows);
        try {
            Files.write(output, lines);
            receipt.planAuditCsvFile = output.getFileName().toString();
        } catch (IOException e) {
            receipt.planAuditCsvFile = "write_error:" + e.getClass().getSimpleName();
        }
    }

    private static String json(String event, Object... kv) {
        StringBuilder b = new StringBuilder("{\"event\":\"").append(safe(event)).append('\"');
        for (int i = 0; i < kv.length; i += 2) b.append(",\"").append(safe(String.valueOf(kv[i])))
                .append("\":\"").append(safe(String.valueOf(kv[i + 1]))).append('\"');
        return b.append('}').toString();
    }
    private static String safe(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
    private static String csv(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    private static String map(Map<String, ? extends Number> values) { return values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).reduce((a, b) -> a + ";" + b).orElse(""); }

    private record Config(int centerX, int centerZ, boolean centerExplicit, int maxChunks, int timeoutSeconds,
                          int routeSearchRadius, boolean stopWhenDone, List<RouteCandidate> fixedCandidates) {
        static Config read() {
            boolean specified = System.getProperty("td.streamsProbe.centerChunkX") != null && System.getProperty("td.streamsProbe.centerChunkZ") != null;
            return new Config(integer("td.streamsProbe.centerChunkX", 0), integer("td.streamsProbe.centerChunkZ", 0), specified,
                    bounded("td.streamsProbe.maxChunks", 4, 1, 16), bounded("td.streamsProbe.timeoutSeconds", 900, 10, 900),
                    bounded("td.streamsProbe.routeSearchRadius", 32, 1, 128),
                    Boolean.parseBoolean(System.getProperty("td.streamsProbe.stop", "true")), parseFixedCandidates());
        }
        Config withCenter(int x, int z) { return new Config(x, z, centerExplicit, maxChunks, timeoutSeconds, routeSearchRadius, stopWhenDone, fixedCandidates); }
        private static List<RouteCandidate> parseFixedCandidates() {
            String value = System.getProperty("td.streamsProbe.chunks", "").trim();
            if (value.isEmpty()) return List.of();
            List<RouteCandidate> result = new ArrayList<>();
            for (String token : value.split(";")) {
                String[] xy = token.trim().split(",");
                if (xy.length != 2) continue;
                try { result.add(new RouteCandidate(Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim()), -1, Integer.MIN_VALUE, "fixed_no_route_query")); }
                catch (NumberFormatException ignored) { }
            }
            return List.copyOf(result);
        }
        private static int integer(String key, int fallback) { try { return Integer.parseInt(System.getProperty(key, String.valueOf(fallback))); } catch (NumberFormatException ignored) { return fallback; } }
        private static int bounded(String key, int fallback, int min, int max) { return Math.max(min, Math.min(max, integer(key, fallback))); }
    }

    private static final class ChunkReceipt {
        final int chunkX, chunkZ, seaLevel;
        int sampleX, sampleZ, baseHeight, preCarve, baseMinusPreCarve, plannedChannelColumns, plannedWaterSurfaceY;
        long waterBlocks, waterAboveSeaBlocks, waterReplacingSolid;
        int surfaceWaterColumns, desertColumns, savannaColumns, otherBiomeColumns;
        String plannedChannelSamples = "", representativeReplacingSolid, csvFile = "";
        String planAuditStatus = "disabled", planAuditError = "", planAuditNaturalGroundMinusWaterRange = "", planAuditCsvFile = "";
        int planAuditChannelColumns;
        final List<String> csvRows = new ArrayList<>();
        final List<String> planAuditRows = new ArrayList<>();
        final List<ColumnSnapshot> columns = new ArrayList<>();
        final Map<String, Integer> naturalBiomeColumns = new LinkedHashMap<>();
        final Map<String, Long> waterReplacingSolidByNaturalBiome = new LinkedHashMap<>();
        ChunkReceipt(int chunkX, int chunkZ, int seaLevel) { this.chunkX = chunkX; this.chunkZ = chunkZ; this.seaLevel = seaLevel; }
        String json() { return StreamsReflowingProbe.json("chunk", "chunk", chunkX + "," + chunkZ, "sample", sampleX + "," + sampleZ,
                "seaLevel", seaLevel, "waterBlocks", waterBlocks, "waterAboveSeaBlocks", waterAboveSeaBlocks,
                "surfaceWaterColumns", surfaceWaterColumns, "biomes", "desert=" + desertColumns + ";savanna=" + savannaColumns + ";other=" + otherBiomeColumns,
                "naturalBiomeColumns", map(naturalBiomeColumns), "plannedChannelSamples", plannedChannelSamples,
                "plannedChannelSampleCount", plannedChannelColumns, "plannedWaterSurfaceY", plannedWaterSurfaceY,
                "waterReplacingSolid", waterReplacingSolid, "waterReplacingSolidByNaturalBiome", map(waterReplacingSolidByNaturalBiome),
                "representativeReplacingSolid", representativeReplacingSolid, "columnCsv", csvFile,
                "planAuditStatus", planAuditStatus, "planAuditChannelColumns", planAuditChannelColumns,
                "planAuditNaturalGroundMinusWaterRange", planAuditNaturalGroundMinusWaterRange, "planAuditCsv", planAuditCsvFile,
                "planAuditError", planAuditError,
                "baseHeightOceanFloorWG", baseHeight, "tdPreCarveBoundary", preCarve, "baseMinusPreCarve", baseMinusPreCarve,
                "tdExpectedTopSolid", preCarve - 1); }
    }

    private record RouteCandidate(int chunkX, int chunkZ, int channelColumns, int waterSurfaceY, String samples) {}
    private record ColumnSnapshot(int x, int z, int baseHeight, int surfaceY, int topWaterY, int groundY, String naturalBiome) {}
}
