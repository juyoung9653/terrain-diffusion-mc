package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.river.RiverPreviewService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.Fluids;
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
        List<ChunkPos> tickAuditForcedChunks = new ArrayList<>();
        long started = System.nanoTime();
        String terminal = "success";
        try {
            ServerLevel level = server.overworld();
            auditCustomBiomes(level, receipts);
            if (Boolean.getBoolean("td.streamsProbe.playerPrefetchAudit")) {
                if ("enabled".equalsIgnoreCase(System.getProperty("td.streamsProbe.playerPrefetchMode", "disabled"))) {
                    auditEnabledPlayerPrefetch(server, level, receipts);
                } else {
                    auditDisabledPlayerPrefetch(server, level, receipts);
                }
            }
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
            boolean riverPreview = Boolean.getBoolean("td.streamsProbe.riverPreview");
            List<RouteCandidate> candidates = config.fixedCandidates.isEmpty()
                    ? (riverPreview ? discoverRiverPreviewCandidates(level, config, receipts)
                    : (Boolean.getBoolean("td.streamsProbe.streamsTopologyAudit") ? discoverTopologyCandidates(level, config, receipts) : discoverRoutes(level, config, receipts)))
                    : config.fixedCandidates;
            if (!config.fixedCandidates.isEmpty()) {
                emit(receipts, json("route_discovery", "status", "fixed_candidates_no_streams_route_query",
                        "candidates", candidates.size(), "candidateSummary", candidates.toString()));
            }
            if (candidates.isEmpty()) {
                terminal = "incomplete";
                emit(receipts, json("route_discovery", "status", "no_route_candidates;no_chunks_generated"));
                if (Boolean.getBoolean("td.streamsProbe.streamsTopologyAudit")) {
                    NativeTopologyAudit missing = new NativeTopologyAudit();
                    missing.error = "native_roles_missing_in_bounded_region";
                    writeNativeTopologyReceipt(server, missing);
                    emit(receipts, missing.json(false, -1, -1));
                    emit(receipts, json("streams_tick_audit", "status", "failed", "startTick", -1, "targetTick", -1, "endTick", -1, "selectedChunkCount", 0, "tickableChunkCount", 0));
                }
            }
            List<ChunkReceipt> completed = new ArrayList<>();
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
                        completed.add(receipt);
                        if (!riverPreview) emit(receipts, receipt.json());
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
            if (Boolean.getBoolean("td.streamsProbe.streamsTopologyAudit") && !completed.isEmpty()) {
                List<ChunkPos> newlyForced = server.submit(() -> forceTickAuditChunks(level, completed))
                        .get(config.timeoutSeconds, TimeUnit.SECONDS);
                tickAuditForcedChunks.addAll(newlyForced);
                Tickability tickability = awaitTickableChunks(server, level, completed, 60_000L);
                long startTick = tickability.tick;
                NativeTopologyAudit before = server.submit(() -> auditNativeStreamsTopology(level, completed)).get(config.timeoutSeconds, TimeUnit.SECONDS);
                Tickability afterTicks = tickability.tickableCount == completed.size()
                        ? awaitServerTicks(server, level, completed, startTick + 100, 30_000L) : tickability;
                NativeTopologyAudit topology = server.submit(() -> auditNativeStreamsTopology(level, completed)).get(config.timeoutSeconds, TimeUnit.SECONDS);
                topology.pre100Snapshot = before.canonical();
                writeNativeTopologyReceipt(server, topology);
                boolean ticksPassed = tickability.tickableCount == completed.size() && afterTicks.tick >= startTick + 100;
                emit(receipts, json("streams_tick_audit", "status", ticksPassed ? "passed" : "failed", "startTick", startTick,
                        "targetTick", startTick + 100, "endTick", afterTicks.tick, "selectedChunkCount", completed.size(),
                        "tickableChunkCount", tickability.tickableCount));
                emit(receipts, topology.json(ticksPassed, startTick, afterTicks.tick));
                if (!topology.passed || !ticksPassed || "mismatch".equals(topology.restartStatus) || topology.restartStatus.startsWith("io_error")) terminal = "incomplete";
            }
            if (riverPreview) {
                // Run only after every requested FULL chunk is complete, so neighboring generation order is settled.
                for (ChunkReceipt receipt : completed) {
                    server.submit(() -> auditRiverPreview(level, receipt)).get(config.timeoutSeconds, TimeUnit.SECONDS);
                    writeRiverPreviewCsv(server, receipt);
                    emit(receipts, receipt.json());
                }
                if (Boolean.getBoolean("td.streamsProbe.riverTickAudit") && !completed.isEmpty()) {
                    List<ChunkPos> newlyForced = server.submit(() -> forceTickAuditChunks(level, completed))
                            .get(config.timeoutSeconds, TimeUnit.SECONDS);
                    tickAuditForcedChunks.addAll(newlyForced);
                    Tickability tickability = awaitTickableChunks(server, level, completed, 60_000L);
                    emit(receipts, json("river_tick_audit", "status", tickability.tickableCount == completed.size() ? "tickable" : "not_tickable",
                            "tickableChunkCount", tickability.tickableCount, "selectedChunkCount", completed.size(), "forcedAdded", newlyForced.size(),
                            "observedTick", tickability.tick));
                    if (tickability.tickableCount != completed.size()) {
                        terminal = "incomplete";
                    } else {
                        long startTick = server.submit(() -> scheduleRiverPreviewWaterTicks(level, completed)).get(config.timeoutSeconds, TimeUnit.SECONDS);
                        emit(receipts, json("river_tick_audit", "status", "scheduled", "startTick", startTick,
                                "targetTick", startTick + 100, "chunks", completed.size()));
                        Tickability afterTicks = awaitServerTicks(server, level, completed, startTick + 100, 30_000L);
                        if (afterTicks.tick < startTick + 100 || afterTicks.tickableCount != completed.size()) {
                            terminal = "incomplete";
                            emit(receipts, json("river_tick_audit", "status", "timeout", "startTick", startTick,
                                    "targetTick", startTick + 100, "observedTick", afterTicks.tick, "tickableChunkCount", afterTicks.tickableCount));
                        } else {
                            long endTick = server.submit(() -> {
                                for (ChunkReceipt receipt : completed) auditRiverPreview(level, receipt);
                                return (long) server.getTickCount();
                            }).get(config.timeoutSeconds, TimeUnit.SECONDS);
                            boolean failedExpectation = false;
                            for (ChunkReceipt receipt : completed) {
                                receipt.riverTickAuditStartTick = startTick;
                                receipt.riverTickAuditEndTick = endTick;
                                receipt.riverTickAuditStatus = "re_audited_after_100_ticks";
                                writeRiverPreviewCsv(server, receipt);
                                emit(receipts, receipt.json());
                                failedExpectation |= receipt.riverPreviewMissingWater > 0 || receipt.riverPreviewMissingBedSolid > 0
                                        || receipt.riverPreviewLeakedBankColumns > 0 || receipt.riverPreviewObstructedWaterColumns > 0;
                            }
                            emit(receipts, json("river_tick_audit", "status", failedExpectation ? "expectation_missing" : "passed",
                                    "startTick", startTick, "endTick", endTick));
                            if (failedExpectation) terminal = "incomplete";
                        }
                    }
                }
            }
        } catch (Throwable t) {
            terminal = "error";
            emit(receipts, json("fatal", "error", t.getClass().getName(), "message", safe(t.getMessage())));
        } finally {
            if (!tickAuditForcedChunks.isEmpty()) {
                try {
                    int released = server.submit(() -> unforceTickAuditChunks(server.overworld(), tickAuditForcedChunks))
                            .get(15, TimeUnit.SECONDS);
                    emit(receipts, json("river_tick_audit", "status", "unforced", "released", released));
                } catch (Exception e) {
                    terminal = "incomplete";
                    emit(receipts, json("river_tick_audit", "status", "unforce_error", "error", e.getClass().getSimpleName(), "message", safe(e.getMessage())));
                }
            }
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

    /** Exercise Streams' real player-triggered tick path, absent in a headless chunk-only probe. */
    private static void auditDisabledPlayerPrefetch(MinecraftServer server, ServerLevel level,
                                                   List<String> receipts) throws Exception {
        Class<?> worldgen = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen");
        Method engine = worldgen.getMethod("engineIfPresent", ServerLevel.class);
        Method tick = Class.forName("dev.streamsreflowing.worldgen.StreamPrefetcher")
                .getMethod("onServerTick", MinecraftServer.class);
        var player = server.submit(() -> {
            var result = new net.minecraft.server.level.ServerPlayer(server, level,
                    new com.mojang.authlib.GameProfile(java.util.UUID.fromString(
                            "00000000-0000-0000-0000-000000000123"), "TDPrefetchProbe"),
                    net.minecraft.server.level.ClientInformation.createDefault());
            result.setPos(-540.5, 64, -95.5);
            return result;
        }).get(30, TimeUnit.SECONDS);
        int firstTick = server.submit(server::getTickCount).get(30, TimeUnit.SECONDS);
        int previousTick = -1;
        int checks = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (checks < 40 && System.nanoTime() < deadline) {
            final int last = previousTick;
            int observed = server.submit(() -> {
                int now = server.getTickCount();
                if (now == last) return -1;
                try {
                    if (engine.invoke(null, level) != null) throw new IllegalStateException("Streams engine already initialized");
                    // No network connection or persistent player registration is needed:
                    // this entry point reads only the level player list and position.
                    level.players().add(player);
                    try {
                        tick.invoke(null, server);
                    } finally {
                        level.players().remove(player);
                    }
                    if (engine.invoke(null, level) != null) throw new IllegalStateException("Disabled player flow initialized Streams engine");
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Could not exercise Streams player tick", failure);
                }
                return now;
            }).get(60, TimeUnit.SECONDS);
            if (observed >= 0) {
                previousTick = observed;
                checks++;
            }
            Thread.sleep(50);
        }
        if (checks != 40 || previousTick <= firstTick) throw new IllegalStateException("Player prefetch audit did not advance ticks");
        emit(receipts, json("player_prefetch_audit", "status", "passed", "checks", checks,
                "firstTick", firstTick, "lastTick", previousTick, "engineInitialized", false));
    }

    /** Enabled-mode counterpart: invoke Streams' real player tick without assuming engine state. */
    private static void auditEnabledPlayerPrefetch(MinecraftServer server, ServerLevel level,
                                                   List<String> receipts) throws Exception {
        Class<?> worldgen = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen");
        Method engineIfPresent = worldgen.getMethod("engineIfPresent", ServerLevel.class);
        Class<?> prefetcher = Class.forName("dev.streamsreflowing.worldgen.StreamPrefetcher");
        Method tick = prefetcher.getMethod("onServerTick", MinecraftServer.class);
        Method laneDiag = prefetcher.getMethod("laneDiag");
        long originBefore = policyCounter("skippedOriginWarmups"), playerBefore = policyCounter("skippedPlayerPrefetches"),
                unloadedFlowBefore = policyCounter("skippedUnloadedFlowGrids");
        boolean engineBefore = engineIfPresent.invoke(null, level) != null;
        long initStart = System.nanoTime();
        // Cold calibration can take minutes. Deliberately initialize from the diagnostic worker before measuring player ticks.
        if (!engineBefore) {
            Method create = worldgen.getMethod("engine", net.minecraft.world.level.WorldGenLevel.class);
            create.invoke(null, level);
        }
        long initMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - initStart);
        int playerX = integerProperty("td.streamsProbe.topologyCenterX", level.getSharedSpawnPos().getX());
        int playerZ = integerProperty("td.streamsProbe.topologyCenterZ", level.getSharedSpawnPos().getZ());
        var player = server.submit(() -> {
            var result = new net.minecraft.server.level.ServerPlayer(server, level,
                    new com.mojang.authlib.GameProfile(java.util.UUID.fromString("00000000-0000-0000-0000-000000000123"), "TDPrefetchProbe"),
                    net.minecraft.server.level.ClientInformation.createDefault());
            result.setPos(playerX + 0.5, 64, playerZ + 0.5);
            return result;
        }).get(30, TimeUnit.SECONDS);
        int firstTick = server.submit(server::getTickCount).get(2, TimeUnit.SECONDS);
        int previous = -1, checks = 0, steadyChecks = 0;
        long maxLatencyMs = 0, deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        // Keep the temporary player visible to the registered handler across real ticks. The first 60 ticks
        // intentionally exercise Streams' cold-join guard; the following 40 are the steady player path.
        server.submit(() -> { level.players().add(player); return null; }).get(2, TimeUnit.SECONDS);
        try {
            while (checks < 100 && System.nanoTime() < deadline) {
                final int last = previous;
                long begin = System.nanoTime();
                int observed = server.submit(() -> {
                    int now = server.getTickCount();
                    if (now == last) return -1;
                    player.tickCount++; // no connection/player-list registration: only the native cold-join age gate.
                    try { tick.invoke(null, server); } // covers a second legitimate handler phase; never alters Streams' J.
                    catch (ReflectiveOperationException e) { throw new IllegalStateException("Could not exercise Streams player tick", e); }
                    return now;
                }).get(2, TimeUnit.SECONDS);
                maxLatencyMs = Math.max(maxLatencyMs, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
                if (observed >= 0) {
                    previous = observed;
                    checks++;
                    if (player.tickCount >= 60) steadyChecks++;
                }
                Thread.sleep(50);
            }
        } finally {
            server.submit(() -> { level.players().remove(player); return null; }).get(2, TimeUnit.SECONDS);
        }
        boolean engineAfter = engineIfPresent.invoke(null, level) != null;
        String lanes;
        try { lanes = String.valueOf(laneDiag.invoke(null)); } catch (ReflectiveOperationException e) { lanes = "unavailable:" + e.getClass().getSimpleName(); }
        long originAfter = policyCounter("skippedOriginWarmups"), playerAfter = policyCounter("skippedPlayerPrefetches"),
                unloadedFlowAfter = policyCounter("skippedUnloadedFlowGrids");
        boolean policyAvailable = originBefore >= 0 && playerBefore >= 0 && originAfter >= 0 && playerAfter >= 0;
        boolean passed = checks == 100 && steadyChecks >= 40 && previous > firstTick && engineAfter && policyAvailable
                && originAfter > 0 && playerAfter > playerBefore;
        emit(receipts, json("player_prefetch_audit", "mode", "enabled", "status", passed ? "passed" : "failed",
                "checks", checks, "startupChecks", checks - steadyChecks, "steadyChecks", steadyChecks, "playerTickCount", player.tickCount,
                "firstTick", firstTick, "lastTick", previous, "engineBefore", engineBefore,
                "engineAfter", engineAfter, "nativeEngineInitMs", initMs, "maxServerTaskLatencyMs", maxLatencyMs, "laneDiag", lanes,
                "policyCountersAvailable", policyAvailable,
                "skippedOriginWarmupsDelta", counterDelta(originBefore, originAfter), "skippedPlayerPrefetchesDelta", counterDelta(playerBefore, playerAfter),
                "skippedUnloadedFlowGridsBefore", unloadedFlowBefore, "skippedUnloadedFlowGridsAfter", unloadedFlowAfter,
                "skippedUnloadedFlowGridsDelta", counterDelta(unloadedFlowBefore, unloadedFlowAfter)));
        if (!passed) throw new IllegalStateException("Enabled player prefetch audit did not complete 60 startup and 40 steady ticks");
    }

    private static long counterDelta(long before, long after) { return before >= 0 && after >= 0 ? after - before : -1; }

    /** Optional compatibility policy: diagnostics work before the policy class is compiled. */
    private static long policyCounter(String methodName) {
        try {
            Class<?> policy = Class.forName("com.github.xandergos.terraindiffusionmc.compat.streams.StreamsTerrainPolicy", false,
                    StreamsReflowingProbe.class.getClassLoader());
            return ((Number) policy.getMethod(methodName).invoke(null)).longValue();
        } catch (Throwable ignored) { return -1; }
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

    /** Bounded native graph search. ColumnPlan distance fields are deliberately not used as graph roles. */
    private static List<RouteCandidate> discoverTopologyCandidates(ServerLevel level, Config config, List<String> receipts) {
        List<RouteCandidate> result = new ArrayList<>();
        try {
            NativeGraphSelection selected = selectNativeGraph(level, config);
            for (NativePoint point : selected.points()) if (point != null) {
                ChunkPos pos = new ChunkPos(new BlockPos(point.x, 0, point.z));
                if (result.stream().noneMatch(c -> c.chunkX == pos.x && c.chunkZ == pos.z))
                    result.add(new RouteCandidate(pos.x, pos.z, -1, Integer.MIN_VALUE, "native_graph:" + point.provenance));
            }
            emit(receipts, json("streams_topology_discovery", "status", selected.complete() ? "bounded_native_graph" : "roles_missing",
                    "center", selected.centerX + "," + selected.centerZ, "radius", selected.radius, "networks", selected.networks,
                    "nodes", selected.nodes, "source", selected.value(selected.source), "junction", selected.value(selected.junction),
                    "outlet", selected.value(selected.outlet), "flow", selected.value(selected.flow), "chunks", result.size(), "error", selected.error));
        } catch (Throwable t) { emit(receipts, json("streams_topology_discovery", "status", "unavailable", "error", t.getClass().getSimpleName(), "message", safe(t.getMessage()))); }
        return result;
    }

    private static NativeGraphSelection selectNativeGraph(ServerLevel level, Config config) throws Exception {
        int centerX = integerProperty("td.streamsProbe.topologyCenterX", config.centerX * 16 + 8);
        int centerZ = integerProperty("td.streamsProbe.topologyCenterZ", config.centerZ * 16 + 8);
        int radius = Math.max(16, Math.min(1024, integerProperty("td.streamsProbe.topologyRadius", 512)));
        NativeGraphSelection out = new NativeGraphSelection(centerX, centerZ, radius);
        Class<?> worldgen = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen");
        Object engine = worldgen.getMethod("engineIfPresent", ServerLevel.class).invoke(null, level);
        if (engine == null) engine = worldgen.getMethod("engine", net.minecraft.world.level.WorldGenLevel.class).invoke(null, level);
        Method platesNear = engine.getClass().getMethod("platesNear", double.class, double.class, double.class);
        Object[] plates = (Object[]) platesNear.invoke(engine, (double) centerX, (double) centerZ, (double) radius);
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        java.util.HashSet<String> seenSegments = new java.util.HashSet<>();
        // `platesNear` is bounded by radius; retain at most 64 networks so this diagnostic cannot expand into a world scan.
        for (int plateIndex = 0; plateIndex < Math.min(64, plates.length); plateIndex++) {
            Object plate = plates[plateIndex];
            Object network = engine.getClass().getMethod("networkFor", plate.getClass()).invoke(engine, plate);
            if (network == null) continue;
            out.networks++;
            for (Object segment : (List<?>) network.getClass().getMethod("segments").invoke(network)) {
                double x1=endpoint(segment,"x1"), z1=endpoint(segment,"z1"), x2=endpoint(segment,"x2"), z2=endpoint(segment,"z2");
                String segmentKey = nodeKey(x1,z1) + ">" + nodeKey(x2,z2);
                if (!seenSegments.add(segmentKey)) continue; // overlapping plate networks expose the same native segment.
                // Streams' RiverSegment x1/z1 -> x2/z2 is treated as its native upstream-to-downstream direction;
                // source/terminal ColumnPlan flags below verify the two directed endpoint roles.
                addGraphNode(nodes, x1, z1, true); addGraphNode(nodes, x2, z2, false);
            }
        }
        out.nodes = nodes.size();
        List<GraphNode> orderedNodes = nodes.values().stream().sorted(java.util.Comparator
                .comparingDouble((GraphNode n) -> Math.hypot(n.x - centerX, n.z - centerZ))
                .thenComparingDouble(n -> n.x).thenComparingDouble(n -> n.z)).toList();
        for (GraphNode node : orderedNodes) {
            if (node.inDegree == 0 && node.outDegree > 0 && out.source == null)
                out.source = nativePlanNear(engine, node, "source", "native_graph_indegree0_source");
            if (node.outDegree == 0 && node.inDegree > 0 && out.outlet == null)
                out.outlet = nativePlanNear(engine, node, "terminal", "native_graph_outdegree0_terminal");
            if (node.inDegree >= 2 && node.outDegree > 0 && out.junction == null)
                out.junction = nativePlanNear(engine, node, "any", "native_graph_indegree" + node.inDegree + "_outdegree" + node.outDegree + "_junction");
        }
        // A wet interior plan with fluid direction validates native flow independently of a headwater endpoint.
        for (GraphNode node : orderedNodes) if (out.flow == null) out.flow = nativePlanNear(engine, node, "flow", "native_graph_flow_sample");
        if (!out.complete()) out.error = "verified_native_roles_missing;degree1/degree3 graph nodes are retained but never relabelled";
        return out;
    }

    private static double endpoint(Object segment, String accessor) throws Exception {
        return ((Number) segment.getClass().getMethod(accessor).invoke(segment)).doubleValue();
    }
    private static String nodeKey(double x, double z) { return Math.round(x * 16.0) + "," + Math.round(z * 16.0); } // 1/16-block epsilon.
    private static void addGraphNode(Map<String, GraphNode> nodes, double x, double z, boolean outgoing) {
        GraphNode node = nodes.computeIfAbsent(nodeKey(x, z), ignored -> new GraphNode(x, z));
        if (outgoing) node.outDegree++; else node.inDegree++;
    }
    private static NativePoint nativePlanNear(Object engine, GraphNode node, String required, String provenance) throws Exception {
        Method plan = engine.getClass().getMethod("plan", int.class, int.class);
        Class<?> cp = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
        Method inChannel = cp.getMethod("inChannel"), waterY = cp.getMethod("waterSurfaceY"), bedY = cp.getMethod("bedY");
        Method source = cp.getMethod("source"), terminal = cp.getMethod("terminal"), flowX = cp.getMethod("fluidFlowDirX"), flowZ = cp.getMethod("fluidFlowDirZ");
        int cx = (int) Math.round(node.x), cz = (int) Math.round(node.z);
        for (int r = 0; r <= 16; r++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            Object value = plan.invoke(engine, cx + dx, cz + dz);
            if (!(Boolean) inChannel.invoke(value)) continue;
            boolean isSource = (Boolean) source.invoke(value), isTerminal = (Boolean) terminal.invoke(value);
            double fx = ((Number) flowX.invoke(value)).doubleValue(), fz = ((Number) flowZ.invoke(value)).doubleValue();
            if (("source".equals(required) && !isSource) || ("terminal".equals(required) && !isTerminal)
                    || ("flow".equals(required) && Math.abs(fx) + Math.abs(fz) <= 1.0e-6)) continue;
            return new NativePoint(cx + dx, cz + dz, ((Number) waterY.invoke(value)).intValue(), ((Number) bedY.invoke(value)).intValue(), fx, fz,
                    provenance + ";node=" + node.x + "," + node.z + ";in=" + node.inDegree + ";out=" + node.outDegree);
        }
        return null;
    }

    private static int integerProperty(String key, int fallback) { try { return Integer.parseInt(System.getProperty(key, String.valueOf(fallback))); } catch (NumberFormatException ignored) { return fallback; } }

    private static List<RouteCandidate> discoverRiverPreviewCandidates(ServerLevel level, Config config, List<String> receipts) {
        List<ChunkPos> chunks = RiverPreviewService.diagnosticChunks(level, config.maxChunks);
        List<RouteCandidate> candidates = chunks.stream()
                .map(pos -> new RouteCandidate(pos.x, pos.z, -1, Integer.MIN_VALUE, "river_preview"))
                .toList();
        emit(receipts, json("river_preview_candidates", "status", candidates.isEmpty() ? "empty" : "ready",
                "tdRiversPreview", Boolean.getBoolean("td.rivers.preview"), "maximum", config.maxChunks,
                "candidates", candidates.size(), "candidateSummary", candidates.toString()));
        return candidates;
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

    /** Re-select from the same bounded native graph after FULL generation, then read only its selected blocks. */
    private static NativeTopologyAudit auditNativeStreamsTopology(ServerLevel level, List<ChunkReceipt> receipts) {
        NativeTopologyAudit audit = new NativeTopologyAudit();
        try {
            Config config = Config.read();
            NativeGraphSelection selected = selectNativeGraph(level, config);
            audit.source = selected.source; audit.junction = selected.junction; audit.outlet = selected.outlet; audit.flow = selected.flow;
            audit.graphProvenance = "networks=" + selected.networks + ";nodes=" + selected.nodes + ";radius=" + selected.radius;
            audit.error = selected.error;
            audit.validate(level);
        } catch (Throwable t) { audit.error = t.getClass().getSimpleName() + ":" + safe(t.getMessage()); }
        return audit;
    }

    private static void writeNativeTopologyReceipt(MinecraftServer server, NativeTopologyAudit audit) {
        Path path = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("td-streams-native-topology-audit.txt");
        String current = audit.canonical();
        try {
            if (Files.exists(path)) {
                String previous = Files.readString(path);
                audit.restartStatus = previous.equals(current) ? "matched" : "mismatch";
                return; // Preserve the first successful baseline for diagnosis.
            }
            if (!audit.passed) { audit.restartStatus = "not_saved_failed"; return; }
            Files.writeString(path, current);
            audit.restartStatus = "saved";
        } catch (IOException e) { audit.restartStatus = "io_error:" + e.getClass().getSimpleName(); }
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

    /** Server-thread read after all FULL requests; no further generation or preview planning occurs here. */
    private static void auditRiverPreview(ServerLevel level, ChunkReceipt receipt) {
        receipt.clearRiverPreviewAudit();
        int expectedWater = 0, actualWater = 0, bedSolid = 0, expectedBedSolid = 0, missingBedSolid = 0, active = 0, leakedBankColumns = 0;
        int obstructedWaterColumns = 0, obstructedWaterBlockCount = 0;
        List<String> missingCoordinates = new ArrayList<>();
        List<String> leakedBankCoordinates = new ArrayList<>();
        List<String> obstructedWaterCoordinates = new ArrayList<>();
        for (ColumnSnapshot snapshot : receipt.columns) {
            RiverPreviewService.Column expected = RiverPreviewService.column(level, snapshot.x, snapshot.z);
            boolean expectedActive = expected.active();
            int groundY = expected.groundY();
            int waterY = expected.waterY();
            boolean expectsWater = expectedActive && waterY > groundY;
            boolean actualFluid = false;
            if (expectedActive) active++;
            if (expectsWater) {
                expectedWater++;
                actualFluid = level.getBlockState(new BlockPos(snapshot.x, waterY, snapshot.z)).getFluidState().is(FluidTags.WATER);
                if (actualFluid) actualWater++;
                else if (missingCoordinates.size() < 16) missingCoordinates.add(snapshot.x + "," + waterY + "," + snapshot.z);
            }
            boolean actualBedSolid = groundY >= level.getMinBuildHeight() && groundY < level.getMaxBuildHeight()
                    && level.getBlockState(new BlockPos(snapshot.x, groundY, snapshot.z)).isSolid();
            if (expectsWater) {
                expectedBedSolid++;
                if (actualBedSolid) bedSolid++;
                else missingBedSolid++;
            }
            boolean obstructedWater = false;
            if (expectsWater) {
                // Bound the roof check to the planned water's immediate pre-feature terrain interval;
                // this deliberately excludes canopy above the original ground, though vegetation in this interval can still be a false positive.
                int highestCheckY = Math.min(level.getMaxBuildHeight() - 1, Math.max(expected.originalGroundY(), waterY + 1));
                for (int y = Math.max(level.getMinBuildHeight(), waterY + 1); y <= highestCheckY; y++) {
                    if (level.getBlockState(new BlockPos(snapshot.x, y, snapshot.z)).blocksMotion()) {
                        obstructedWater = true;
                        obstructedWaterBlockCount++;
                    }
                }
                if (obstructedWater) {
                    obstructedWaterColumns++;
                    if (obstructedWaterCoordinates.size() < 16) obstructedWaterCoordinates.add(snapshot.x + "," + waterY + "," + snapshot.z);
                }
            }
            boolean leakedBankFluid = false;
            if (expectedActive && waterY == Integer.MIN_VALUE && groundY < expected.originalGroundY()) {
                int highestCheckY = Math.min(level.getMaxBuildHeight() - 1, expected.originalGroundY() + 1);
                for (int y = Math.max(level.getMinBuildHeight(), groundY + 1); y <= highestCheckY; y++) {
                    if (!level.getBlockState(new BlockPos(snapshot.x, y, snapshot.z)).getFluidState().isEmpty()) {
                        leakedBankFluid = true;
                        break;
                    }
                }
                if (leakedBankFluid) {
                    leakedBankColumns++;
                    if (leakedBankCoordinates.size() < 16) leakedBankCoordinates.add(snapshot.x + "," + snapshot.z);
                }
            }
            receipt.riverPreviewRows.add(snapshot.x + "," + snapshot.z + "," + expectedActive + "," + groundY + "," + waterY + ","
                    + expected.originalGroundY() + "," + expected.halfWidth() + "," + actualFluid + "," + actualBedSolid + "," + leakedBankFluid + "," + obstructedWater);
        }
        receipt.riverPreviewStatus = "post_full_expectation_audit";
        receipt.riverPreviewActiveColumns = active;
        receipt.riverPreviewExpectedWater = expectedWater;
        receipt.riverPreviewActualWater = actualWater;
        receipt.riverPreviewMissingWater = expectedWater - actualWater;
        receipt.riverPreviewBedSolid = bedSolid;
        receipt.riverPreviewExpectedBedSolid = expectedBedSolid;
        receipt.riverPreviewMissingBedSolid = missingBedSolid;
        receipt.riverPreviewMissingCoordinates = String.join(";", missingCoordinates);
        receipt.riverPreviewLeakedBankColumns = leakedBankColumns;
        receipt.riverPreviewLeakedBankCoordinates = String.join(";", leakedBankCoordinates);
        receipt.riverPreviewObstructedWaterColumns = obstructedWaterColumns;
        receipt.riverPreviewObstructedWaterBlockCount = obstructedWaterBlockCount;
        receipt.riverPreviewObstructedWaterCoordinates = String.join(";", obstructedWaterCoordinates);
    }

    /** Called on the server thread after the first preview audit; it does not load chunks. */
    private static List<ChunkPos> forceTickAuditChunks(ServerLevel level, List<ChunkReceipt> receipts) {
        List<ChunkPos> added = new ArrayList<>();
        for (ChunkReceipt receipt : receipts) {
            if (level.setChunkForced(receipt.chunkX, receipt.chunkZ, true)) added.add(new ChunkPos(receipt.chunkX, receipt.chunkZ));
        }
        return added;
    }

    private static int unforceTickAuditChunks(ServerLevel level, List<ChunkPos> chunks) {
        int released = 0;
        for (ChunkPos chunk : chunks) if (level.setChunkForced(chunk.x, chunk.z, false)) released++;
        return released;
    }

    private static long scheduleRiverPreviewWaterTicks(ServerLevel level, List<ChunkReceipt> receipts) {
        for (ChunkReceipt receipt : receipts) for (ColumnSnapshot snapshot : receipt.columns) {
            RiverPreviewService.Column expected = RiverPreviewService.column(level, snapshot.x, snapshot.z);
            if (expected.active() && expected.waterY() > expected.groundY()) {
                level.scheduleTick(new BlockPos(snapshot.x, expected.waterY(), snapshot.z), Fluids.WATER, 1);
            }
        }
        return level.getServer().getTickCount();
    }

    /** The probe worker waits here; every tickability read is a tiny server submission. */
    private static Tickability awaitTickableChunks(MinecraftServer server, ServerLevel level, List<ChunkReceipt> receipts,
                                                     long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            Tickability status = server.submit(() -> tickability(level, receipts)).get(10, TimeUnit.SECONDS);
            if (status.tickableCount == receipts.size()) return status;
            Thread.sleep(50L);
        }
        return server.submit(() -> tickability(level, receipts)).get(10, TimeUnit.SECONDS);
    }

    private static Tickability awaitServerTicks(MinecraftServer server, ServerLevel level, List<ChunkReceipt> receipts,
                                                 long targetTick, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            Tickability status = server.submit(() -> tickability(level, receipts)).get(10, TimeUnit.SECONDS);
            if (status.tick >= targetTick && status.tickableCount == receipts.size()) return status;
            Thread.sleep(50L);
        }
        return server.submit(() -> tickability(level, receipts)).get(10, TimeUnit.SECONDS);
    }

    private static Tickability tickability(ServerLevel level, List<ChunkReceipt> receipts) {
        int tickable = 0;
        for (ChunkReceipt receipt : receipts) {
            BlockPos center = new BlockPos((receipt.chunkX << 4) + 8, level.getMinBuildHeight(), (receipt.chunkZ << 4) + 8);
            if (level.isPositionEntityTicking(center) && level.getChunkSource().isPositionTicking(ChunkPos.asLong(receipt.chunkX, receipt.chunkZ))) tickable++;
        }
        return new Tickability(level.getServer().getTickCount(), tickable);
    }

    private static void writeRiverPreviewCsv(MinecraftServer server, ChunkReceipt receipt) {
        Path output = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("td-river-preview-audit-" + receipt.chunkX + "-" + receipt.chunkZ + ".csv");
        List<String> lines = new ArrayList<>(receipt.riverPreviewRows.size() + 1);
        lines.add("x,z,expectedActive,expectedGroundY,expectedWaterY,expectedOriginalGroundY,expectedHalfWidth,actualFluidAtExpectedWater,actualBedSolidAtExpectedGround,leakedBankFluid,obstructedWater");
        lines.addAll(receipt.riverPreviewRows);
        try {
            Files.write(output, lines);
            receipt.riverPreviewCsvFile = output.getFileName().toString();
        } catch (IOException e) {
            receipt.riverPreviewCsvFile = "write_error:" + e.getClass().getSimpleName();
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
        String riverPreviewStatus = "disabled", riverPreviewMissingCoordinates = "", riverPreviewCsvFile = "", riverPreviewLeakedBankCoordinates = "", riverPreviewObstructedWaterCoordinates = "";
        String riverTickAuditStatus = "disabled";
        int planAuditChannelColumns;
        int riverPreviewActiveColumns, riverPreviewExpectedWater, riverPreviewActualWater, riverPreviewMissingWater, riverPreviewBedSolid,
                riverPreviewExpectedBedSolid, riverPreviewMissingBedSolid;
        int riverPreviewLeakedBankColumns;
        int riverPreviewObstructedWaterColumns, riverPreviewObstructedWaterBlockCount;
        long riverTickAuditStartTick = -1, riverTickAuditEndTick = -1;
        final List<String> csvRows = new ArrayList<>();
        final List<String> planAuditRows = new ArrayList<>();
        final List<ColumnSnapshot> columns = new ArrayList<>();
        final List<String> riverPreviewRows = new ArrayList<>();
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
                "riverPreviewStatus", riverPreviewStatus, "riverPreviewActiveColumns", riverPreviewActiveColumns,
                "riverPreviewExpectedWater", riverPreviewExpectedWater, "riverPreviewActualWater", riverPreviewActualWater,
                "riverPreviewMissingWater", riverPreviewMissingWater, "riverPreviewBedSolid", riverPreviewBedSolid,
                "riverPreviewExpectedBedSolid", riverPreviewExpectedBedSolid, "riverPreviewMissingBedSolid", riverPreviewMissingBedSolid,
                "riverPreviewMissingCoordinates", riverPreviewMissingCoordinates, "riverPreviewLeakedBankColumns", riverPreviewLeakedBankColumns,
                "riverPreviewLeakedBankCoordinates", riverPreviewLeakedBankCoordinates, "riverPreviewCsv", riverPreviewCsvFile,
                "riverPreviewObstructedWaterColumns", riverPreviewObstructedWaterColumns,
                "riverPreviewObstructedWaterBlockCount", riverPreviewObstructedWaterBlockCount,
                "riverPreviewObstructedWaterCoordinates", riverPreviewObstructedWaterCoordinates,
                "riverPreviewObstructionConstraint", "motion_blocking_from_water_plus_1_through_original_ground;vegetation_in_interval_may_be_false_positive",
                "riverTickAuditStatus", riverTickAuditStatus, "riverTickAuditStartTick", riverTickAuditStartTick,
                "riverTickAuditEndTick", riverTickAuditEndTick,
                "baseHeightOceanFloorWG", baseHeight, "tdPreCarveBoundary", preCarve, "baseMinusPreCarve", baseMinusPreCarve,
                "tdExpectedTopSolid", preCarve - 1); }
        void clearRiverPreviewAudit() {
            riverPreviewRows.clear();
            riverPreviewStatus = "disabled";
            riverPreviewMissingCoordinates = "";
            riverPreviewActiveColumns = riverPreviewExpectedWater = riverPreviewActualWater = riverPreviewMissingWater = 0;
            riverPreviewBedSolid = riverPreviewExpectedBedSolid = riverPreviewMissingBedSolid = 0;
            riverPreviewLeakedBankColumns = 0;
            riverPreviewLeakedBankCoordinates = "";
            riverPreviewObstructedWaterColumns = riverPreviewObstructedWaterBlockCount = 0;
            riverPreviewObstructedWaterCoordinates = "";
        }
    }

    private static final class GraphNode {
        final double x, z; int inDegree, outDegree;
        GraphNode(double x, double z) { this.x=x; this.z=z; }
    }
    private static final class NativeGraphSelection {
        final int centerX, centerZ, radius; int networks, nodes; String error = "";
        NativePoint source, junction, outlet, flow;
        NativeGraphSelection(int centerX, int centerZ, int radius) { this.centerX=centerX; this.centerZ=centerZ; this.radius=radius; }
        boolean complete() { return source != null && junction != null && outlet != null && flow != null; }
        NativePoint[] points() { return new NativePoint[] { source, junction, outlet, flow }; }
        String value(NativePoint p) { return p == null ? "missing" : p.canonical(); }
    }
    private static final class NativeTopologyAudit {
        NativePoint source, junction, outlet, flow;
        String error = "", restartStatus = "not_written", pre100Snapshot = "", graphProvenance = "";
        boolean passed;
        void validate(ServerLevel level) {
            if (source != null) source.readRole(level); if (junction != null) junction.readRole(level);
            if (outlet != null) outlet.readRole(level); if (flow != null) flow.readFlowColumn(level, 64);
            passed = source != null && junction != null && outlet != null && flow != null
                    && source.valid && junction.valid && outlet.valid && flow.valid && flow.flowNonZero;
        }
        String canonical() { return "source=" + value(source) + "\njunction=" + value(junction) + "\noutlet=" + value(outlet)
                + "\nflow=" + value(flow) + "\ngraph=" + graphProvenance + "\n"; }
        private static String value(NativePoint p) { return p == null ? "missing" : p.canonical(); }
        String json(boolean ticksAdvanced, long start, long end) { return StreamsReflowingProbe.json("streams_topology_audit", "status", passed && ticksAdvanced && !"mismatch".equals(restartStatus) && !restartStatus.startsWith("io_error") ? "passed" : "failed", "error", error,
                "source", value(source), "junction", value(junction), "outlet", value(outlet), "flow", value(flow), "graphProvenance", graphProvenance,
                "pre100Snapshot", pre100Snapshot, "restartStatus", restartStatus, "post100TicksAdvanced", ticksAdvanced, "startTick", start, "endTick", end); }
    }
    private static final class NativePoint {
        final int x, z, waterY, bedY; final double flowX, flowZ; final String provenance;
        boolean water, bedSolid, flowNonZero, valid, waterColumnContinuous, physicalBedSolid;
        int physicalBedY = Integer.MIN_VALUE, waterColumnDepth;
        String waterBlock = "", bedBlock = "", physicalBedBlock = "";
        NativePoint(int x, int z, int waterY, int bedY, double flowX, double flowZ, String provenance) {
            this.x=x; this.z=z; this.waterY=waterY; this.bedY=bedY; this.flowX=flowX; this.flowZ=flowZ; this.provenance=provenance;
        }
        /** Exact native-plan water/bed evidence for source, confluence and terminal roles. */
        void readRole(ServerLevel level) {
            var waterState = level.getBlockState(new BlockPos(x, waterY, z)); var bedState = level.getBlockState(new BlockPos(x, bedY, z));
            water = waterState.getFluidState().is(FluidTags.WATER); bedSolid = bedState.isSolid(); flowNonZero = Math.abs(flowX)+Math.abs(flowZ)>1.0e-6;
            valid = water && bedSolid; waterBlock = String.valueOf(waterState); bedBlock = String.valueOf(bedState);
        }
        /** Flow channels may be carved below ColumnPlan.bedY by native features; prove a bounded, hole-free physical column instead. */
        void readFlowColumn(ServerLevel level, int maximumDepth) {
            readRole(level); // Retain planned-bed evidence separately from the physical bed.
            flowNonZero = Double.isFinite(flowX) && Double.isFinite(flowZ) && Math.abs(flowX)+Math.abs(flowZ)>1.0e-6;
            physicalBedY = Integer.MIN_VALUE; physicalBedSolid = false; physicalBedBlock = "";
            waterColumnDepth = water ? 1 : 0;
            waterColumnContinuous = water;
            for (int depth = 1; waterColumnContinuous && depth <= maximumDepth; depth++) {
                int y = waterY - depth; if (y < level.getMinBuildHeight()) { waterColumnContinuous = false; break; }
                var state = level.getBlockState(new BlockPos(x, y, z));
                if (state.getFluidState().is(FluidTags.WATER)) { waterColumnDepth = depth + 1; continue; }
                physicalBedY = y; physicalBedSolid = state.isSolid(); physicalBedBlock = String.valueOf(state);
                waterColumnContinuous = physicalBedSolid; break;
            }
            valid = water && flowNonZero && waterColumnContinuous && physicalBedSolid;
        }
        String canonical() { return x+","+z+","+waterY+","+bedY+","+water+","+bedSolid+","+flowNonZero+","+flowX+","+flowZ+","+provenance+","+waterBlock+","+bedBlock
                + ",physicalBed="+physicalBedY+","+physicalBedSolid+","+waterColumnDepth+","+waterColumnContinuous+","+physicalBedBlock; }
        public String toString() { return canonical(); }
    }

    private record RouteCandidate(int chunkX, int chunkZ, int channelColumns, int waterSurfaceY, String samples) {}
    private record ColumnSnapshot(int x, int z, int baseHeight, int surfaceY, int topWaterY, int groundY, String naturalBiome) {}
    private record Tickability(long tick, int tickableCount) {}
}
