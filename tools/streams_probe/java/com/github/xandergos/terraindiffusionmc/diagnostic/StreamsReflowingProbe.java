package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.ShoreClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeClassifier;
import com.mojang.serialization.JsonOps;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyShowcaseProfile;
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
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.BiomeTags;
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
        if (Boolean.getBoolean("td.streamsProbe.bankGeometryAudit")) {
            try { BankGeometryAudit.run(); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("Bank geometry audit failed", e); }
        }
        if (Boolean.getBoolean("td.streamsProbe.containmentAudit")) server.tickRateManager().setFrozen(true);
        Thread worker = new Thread(() -> run(server), "td-streams-probe");
        worker.setDaemon(true);
        worker.start();
    }

    private static void run(MinecraftServer server) {
        List<String> receipts = new ArrayList<>();
        Config config;
        try { config = Config.read(); }
        catch (IllegalArgumentException error) {
            emit(receipts, json("fatal", "error", "probe_configuration", "message", safe(error.getMessage())));
            writeReceipts(server, receipts);
            server.submit(() -> server.halt(false));
            return;
        }
        List<ChunkPos> tickAuditForcedChunks = new ArrayList<>();
        long started = System.nanoTime();
        String terminal = "success";
        try {
            ServerLevel level = server.overworld();
            int expectedNaturalProfile = Integer.getInteger("td.streamsProbe.expectedNaturalProfile", 0);
            if (expectedNaturalProfile > 0 && (!com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState.enabled(level)
                    || com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState.version(level) != expectedNaturalProfile)) {
                throw new IllegalStateException("Expected enabled natural river profile " + expectedNaturalProfile
                        + "; check the fresh template's initialized flag and saved profile before generating chunks");
            }
            auditCustomBiomes(level, receipts);
            if (Boolean.getBoolean("td.streamsProbe.previewSessionAudit")) emit(receipts, auditPreviewSession());
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
            if (Boolean.getBoolean("td.streamsProbe.naturalRiverAudit")) NaturalRiverAudit.export(level);
            if (Boolean.getBoolean("td.streamsProbe.naturalPlanOnly")) {
                if (!Boolean.getBoolean("td.streamsProbe.naturalRiverAudit"))
                    throw new IllegalArgumentException("naturalPlanOnly requires naturalRiverAudit");
                emit(receipts, json("natural_plan_export", "status", "exported", "physicalChunksChecked", 0));
                return;
            }
            List<RouteCandidate> candidates = new ArrayList<>(config.fixedCandidates.isEmpty()
                    ? (riverPreview ? discoverRiverPreviewCandidates(level, config, receipts)
                    : (Boolean.getBoolean("td.streamsProbe.streamsTopologyAudit") ? discoverTopologyCandidates(level, config, receipts) : discoverRoutes(level, config, receipts)))
                    : config.fixedCandidates);
            if (!config.fixedCandidates.isEmpty() && Boolean.getBoolean("td.streamsProbe.streamsTopologyAudit")) {
                // Every audited source/junction/outlet/flow chunk must join the
                // generated and tick-forced cohort, even for long fixed paths.
                for (RouteCandidate role : discoverTopologyCandidates(level, config, receipts)) {
                    if (candidates.stream().noneMatch(c -> c.chunkX == role.chunkX && c.chunkZ == role.chunkZ)) candidates.add(role);
                }
            }
            if (Boolean.getBoolean("td.streamsProbe.shoreAudit")) {
                ShoreAudit shore = discoverExactShoreAudit();
                emit(receipts, shore.json());
                for (ChunkPos pos : shore.representativeChunks()) {
                    if (candidates.stream().noneMatch(c -> c.chunkX == pos.x && c.chunkZ == pos.z))
                        candidates.add(new RouteCandidate(pos.x, pos.z, -1, Integer.MIN_VALUE, "exact_ecology_shore"));
                }
            }
            if (!config.fixedCandidates.isEmpty()) {
                emit(receipts, json("route_discovery", "status", Boolean.getBoolean("td.streamsProbe.streamsTopologyAudit")
                                ? "fixed_candidates_with_native_roles" : "fixed_candidates_no_streams_route_query",
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
                        if (Boolean.getBoolean("td.streamsProbe.surfaceAudit")) {
                            writeSurfaceAuditCsv(server, receipt);
                            emit(receipts, receipt.surfaceAuditJson());
                        }
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
            if (Boolean.getBoolean("td.streamsProbe.ecologyOverview")) {
                emit(receipts, writeEcologyOverview(server));
            }

            if (Boolean.getBoolean("td.streamsProbe.containmentAudit") && !completed.isEmpty()) {
                tickAuditForcedChunks.addAll(server.submit(() -> forceTickAuditChunks(level, completed))
                        .get(config.timeoutSeconds, TimeUnit.SECONDS));
                Tickability ready = awaitTickableChunks(server, level, completed, 60_000L);
                if (ready.tickableCount != completed.size()) throw new IllegalStateException("Containment cohort is not tickable");
                int minX = integerProperty("td.streamsProbe.containmentMinX", -4056);
                int maxX = integerProperty("td.streamsProbe.containmentMaxX", -3961);
                int minZ = integerProperty("td.streamsProbe.containmentMinZ", -168);
                int maxZ = integerProperty("td.streamsProbe.containmentMaxZ", -73);
                int minY = integerProperty("td.streamsProbe.containmentMinY", 140);
                int maxY = integerProperty("td.streamsProbe.containmentMaxY", 215);
                var beforeWater = server.submit(() -> RiverContainmentAudit.capture(level, minX, maxX, minY, maxY, minZ, maxZ))
                        .get(config.timeoutSeconds, TimeUnit.SECONDS);
                var root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                RiverContainmentAudit.write(root.resolve("td-containment-before.json"), beforeWater);
                int initialOutsideGrass = server.submit(() -> {
                    try { return RiverContainmentAudit.writePlans(level, root.resolve("td-containment-plans-before.csv"), beforeWater); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }).get(config.timeoutSeconds, TimeUnit.SECONDS);
                int scheduled = server.submit(() -> RiverContainmentAudit.scheduleWaterTicks(level, beforeWater))
                        .get(config.timeoutSeconds, TimeUnit.SECONDS);
                long startGameTime = server.submit(level::getGameTime).get(config.timeoutSeconds, TimeUnit.SECONDS);
                long startWaterTick = server.submit(() -> {
                    server.tickRateManager().setFrozen(false);
                    return (long) server.getTickCount();
                }).get(config.timeoutSeconds, TimeUnit.SECONDS);
                Tickability advanced = awaitServerTicks(server, level, completed, startWaterTick + 200, 90_000L);
                var afterWater = server.submit(() -> RiverContainmentAudit.capture(level, minX, maxX, minY, maxY, minZ, maxZ))
                        .get(config.timeoutSeconds, TimeUnit.SECONDS);
                RiverContainmentAudit.write(root.resolve("td-containment-after.json"), afterWater);
                int finalOutsideGrass = server.submit(() -> {
                    try { return RiverContainmentAudit.writePlans(level, root.resolve("td-containment-plans-after.csv"), afterWater); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }).get(config.timeoutSeconds, TimeUnit.SECONDS);
                long gameTicks = server.submit(level::getGameTime).get(config.timeoutSeconds, TimeUnit.SECONDS) - startGameTime;
                var spread = RiverContainmentAudit.compare(beforeWater, afterWater);
                java.nio.file.Files.writeString(root.resolve("td-containment-comparison.json"), spread.toJson());
                boolean passed = scheduled > 0 && gameTicks >= 200 && advanced.tick >= startWaterTick + 200
                        && advanced.tickableCount == completed.size()
                        && spread.newlyInundatedOriginalDryGrass() == 0 && finalOutsideGrass == 0;
                emit(receipts, json("river_containment_audit", "status", passed ? "passed" : "failed",
                        "scheduledWaterCells", scheduled, "startTick", startWaterTick, "endTick", advanced.tick, "gameTicks", gameTicks,
                        "newlyInundatedGrass", spread.newlyInundatedOriginalDryGrass(), "initialWetGrass", beforeWater.wetGrassCount(),
                        "finalWetGrass", afterWater.wetGrassCount(), "initialOutsideChannelGrass", initialOutsideGrass,
                        "finalOutsideChannelGrass", finalOutsideGrass, "addedWater", spread.addedWater(), "removedWater", spread.removedWater()));
                if (!passed) terminal = "incomplete";
            }
            String corridorBanksFile = System.getProperty("td.streamsProbe.corridorBanksFile", "").trim();
            if (!corridorBanksFile.isEmpty()) {
                int boxes = 0, outsideGrass = 0;
                for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(corridorBanksFile))) {
                    if (line.isBlank()) continue;
                    String[] fields = line.trim().split(",");
                    if (fields.length != 6) throw new IllegalArgumentException("Corridor bank box needs six coordinates");
                    int[] bounds = java.util.Arrays.stream(fields).mapToInt(Integer::parseInt).toArray();
                    final int box = boxes++;
                    outsideGrass += server.submit(() -> {
                        try {
                            var snapshot = RiverContainmentAudit.capture(level,bounds[0],bounds[1],bounds[2],bounds[3],bounds[4],bounds[5]);
                            java.nio.file.Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                            return RiverContainmentAudit.writePlans(level,root.resolve("td-corridor-banks-"+box+".csv"),snapshot);
                        } catch (Exception e) { throw new IllegalStateException(e); }
                    }).get(config.timeoutSeconds, TimeUnit.SECONDS);
                }
                boolean passed = boxes > 0 && outsideGrass == 0;
                emit(receipts,json("river_corridor_banks", "status",passed ? "passed" : "failed", "sampleBoxes",boxes,"outsideChannelWetGrass",outsideGrass));
                if (!passed) terminal = "incomplete";
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
                emit(receipts, server.submit(() -> auditNativeEndpoint(level, topology.outlet)).get(config.timeoutSeconds, TimeUnit.SECONDS));
                if (!topology.passed || !ticksPassed || "mismatch".equals(topology.restartStatus) || topology.restartStatus.startsWith("io_error")) terminal = "incomplete";
            }
            if (Boolean.getBoolean("td.streamsProbe.marineConnectivityAudit")) {
                final Config marineConfig = config;
                MarineConnectivityAudit marine = server.submit(() -> auditMarineConnectivity(level, marineConfig)).get(config.timeoutSeconds, TimeUnit.SECONDS);
                emit(receipts, marine.planJson());
                if (Boolean.getBoolean("td.streamsProbe.marinePrepareChunks") && marine.preparable()) {
                    for (ChunkPos pos : marine.requiredChunks) {
                        server.submit(() -> inspectChunk(level, pos.x, pos.z)).get(config.timeoutSeconds, TimeUnit.SECONDS);
                        if (server.submit(() -> level.setChunkForced(pos.x, pos.z, true)).get(config.timeoutSeconds, TimeUnit.SECONDS))
                            tickAuditForcedChunks.add(pos);
                    }
                    final MarineConnectivityAudit plannedMarine = marine;
                    marine = server.submit(() -> evaluateMarineConnectivity(level, plannedMarine)).get(config.timeoutSeconds, TimeUnit.SECONDS);
                }
                emit(receipts, marine.json());
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
            if (Boolean.getBoolean("td.streamsProbe.containmentAudit")) {
                try {
                    server.submit(() -> server.tickRateManager().setFrozen(false)).get(15, TimeUnit.SECONDS);
                } catch (Exception e) {
                    terminal = "incomplete";
                    emit(receipts, json("river_containment_audit", "status", "unfreeze_error", "error", e.getClass().getSimpleName()));
                }
            }
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
            if (Boolean.getBoolean("td.streamsProbe.previewSessionAudit")) {
                emit(receipts, json("streams_bank_material_audit", "status", "observed", "counters", streamsBankCounters()));
            }
            if (Boolean.getBoolean("td.streamsProbe.naturalBankShapeAudit")) {
                emit(receipts, json("natural_bank_shape", "status", "observed",
                        "shapedColumns", com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalBanks.shapedColumns(),
                        "removedBlocks", com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalBanks.removedBlocks()));
                emit(receipts, json("shallow_bank_retention", "status", "observed",
                        "retainedWrites", com.github.xandergos.terraindiffusionmc.compat.streams.StreamsShallowBanks.retainedWrites()));
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
        if (Boolean.getBoolean("td.streamsProbe.surfaceAudit")) auditSurface(level, chunk, generator, result);
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

    /** Exact, tile-bounded ecology shore scan. No coarse climate or generated-block inference is used here. */
    private static ShoreAudit discoverExactShoreAudit() {
        ShoreAudit audit = new ShoreAudit();
        try {
            ShoreMapping mapping = resolveShoreMapping();
            audit.mapping = mapping.report();
            if (!mapping.valid()) { audit.error = "shore_mapping_invalid"; return audit; }
            int tile = TerrainDiffusionConfig.tileSize();
            int minX = System.getProperty("td.streamsProbe.shoreCenterX") == null ? Math.floorDiv(-1536, tile) * tile
                    : Math.floorDiv(integerProperty("td.streamsProbe.shoreCenterX", -1280), tile) * tile;
            int minZ = System.getProperty("td.streamsProbe.shoreCenterZ") == null ? Math.floorDiv(0, tile) * tile
                    : Math.floorDiv(integerProperty("td.streamsProbe.shoreCenterZ", 128), tile) * tile;
            // Default is exactly two adjacent TD tiles by one tile; explicit centers remain tile-aligned and equally bounded.
            audit.minX=minX; audit.minZ=minZ; audit.maxX=minX + 2*tile; audit.maxZ=minZ + tile; audit.tileSize=tile;
            for (int tileZ=minZ; tileZ<audit.maxZ; tileZ+=tile) for (int tileX=minX; tileX<audit.maxX; tileX+=tile) {
                var data=LocalTerrainProvider.getInstance().fetchHeightmap(tileZ,tileX,tileZ+tile,tileX+tile);
                if (data == null || data.ecology == null) { audit.error="ecology_tile_unavailable"; continue; }
                audit.tiles++;
                for (int z=0; z<data.height; z++) for (int x=0; x<data.width; x++) {
                    short terrain=data.ecology.terrainBiomeId(x,z); int worldX=tileX+x, worldZ=tileZ+z;
                    String type=shoreType(terrain, mapping);
                    if (type != null) { audit.add(type,worldX,worldZ,data.heightmap[z][x]); continue; }
                    if (terrain == 0 && touchesShore(data.ecology,x,z, mapping)) audit.addLand(worldX,worldZ);
                }
            }
            if (audit.tiles == 0 && audit.error.isEmpty()) audit.error="no_tiles";
        } catch (Throwable t) { audit.error=t.getClass().getSimpleName()+":"+safe(t.getMessage()); }
        return audit;
    }

    /** Pure ID/name mapping: only the named Terralith gravel beach is positive shore terrain. */
    private static ShoreMapping resolveShoreMapping() {
        int index = TerralithBiomeClassifier.biomeNames().indexOf("terralith:gravel_beach");
        short gravelId = (short) (index + 1);
        String resolved = index < 0 ? null : TerralithBiomeClassifier.biomeName(gravelId);
        return new ShoreMapping(gravelId, index >= 0 && "terralith:gravel_beach".equals(resolved)
                && ShoreClassifier.BEACH == -1 && ShoreClassifier.SNOWY_BEACH == -2 && ShoreClassifier.STONY_SHORE == -3, resolved);
    }

    private static String shoreType(short terrain, ShoreMapping mapping) {
        return switch (terrain) {
            case ShoreClassifier.BEACH -> "beach";
            case ShoreClassifier.SNOWY_BEACH -> "snowy_beach";
            case ShoreClassifier.STONY_SHORE -> "stony_shore";
            default -> terrain == mapping.gravelBeachId ? "terralith_gravel_beach" : null;
        };
    }
    private static boolean touchesShore(com.github.xandergos.terraindiffusionmc.pipeline.EcologyData ecology, int x, int z, ShoreMapping mapping) {
        return (x>0 && shoreType(ecology.terrainBiomeId(x-1,z), mapping)!=null) || (x+1<ecology.width && shoreType(ecology.terrainBiomeId(x+1,z), mapping)!=null)
                || (z>0 && shoreType(ecology.terrainBiomeId(x,z-1), mapping)!=null) || (z+1<ecology.height && shoreType(ecology.terrainBiomeId(x,z+1), mapping)!=null);
    }

    /** Bounded API audit: three 8x8 heightmap samples, no world mutation or cache clearing. */
    private static String auditPreviewSession() {
        final int x = integerProperty("td.streamsProbe.previewSessionX", 0);
        final int z = integerProperty("td.streamsProbe.previewSessionZ", 0);
        final int side = 8;
        try {
            LocalTerrainProvider globalProvider = LocalTerrainProvider.getInstance();
            long globalSeed = LocalTerrainProvider.getSeed();
            int globalScale = WorldScaleManager.getCurrentScale();
            String globalData = heightmapFingerprint(globalProvider.fetchHeightmap(z, x, z + side, x + side));
            PreviewAudit audit = new PreviewAudit(globalProvider, globalSeed, globalScale, globalData);

            try (LocalTerrainProvider.PreviewSession same = LocalTerrainProvider.createPreviewSession(globalSeed, 2)) {
                same.run(() -> {
                    audit.sameProviderDistinct = LocalTerrainProvider.getInstance() != audit.globalProvider;
                    audit.sameScopedSeed = LocalTerrainProvider.getSeed();
                    audit.sameScopedScale = WorldScaleManager.getCurrentScale();
                    audit.sameData = heightmapFingerprint(LocalTerrainProvider.getInstance().fetchHeightmap(z, x, z + side, x + side));
                    try (LocalTerrainProvider.PreviewSession nested = LocalTerrainProvider.createPreviewSession(globalSeed ^ 0x5EED5EEDL, 1)) {
                        nested.run(() -> {
                            audit.nestedProviderDistinct = LocalTerrainProvider.getInstance() != audit.globalProvider;
                            audit.nestedSeed = LocalTerrainProvider.getSeed();
                            audit.nestedScale = WorldScaleManager.getCurrentScale();
                            audit.differentData = heightmapFingerprint(LocalTerrainProvider.getInstance().fetchHeightmap(z, x, z + side, x + side));
                        });
                        try { nested.run(() -> { throw new IllegalStateException("expected_preview_exception"); }); }
                        catch (IllegalStateException expected) { audit.exceptionObserved = true; }
                    }
                    audit.nestedRestored = LocalTerrainProvider.getInstance() != audit.globalProvider
                            && LocalTerrainProvider.getSeed() == globalSeed && WorldScaleManager.getCurrentScale() == 2;
                });
            }
            LocalTerrainProvider.PreviewSession closed = LocalTerrainProvider.createPreviewSession(globalSeed, 2);
            closed.close();
            try { closed.run(() -> {}); } catch (java.util.concurrent.CancellationException expected) { audit.closedRejected = true; }

            audit.globalRestored = LocalTerrainProvider.getInstance() == globalProvider && LocalTerrainProvider.getSeed() == globalSeed
                    && LocalTerrainProvider.previewScale() == null && WorldScaleManager.getCurrentScale() == globalScale;
            audit.sameDataMatchesGlobal = globalScale == 2 && globalData.equals(audit.sameData);
            audit.differentDataDiffers = !globalData.equals(audit.differentData);
            boolean passed = audit.sameProviderDistinct && audit.sameScopedSeed == globalSeed && audit.sameScopedScale == 2
                    && audit.sameDataMatchesGlobal && audit.nestedProviderDistinct && audit.nestedSeed != globalSeed && audit.nestedScale == 1
                    && audit.differentDataDiffers && audit.exceptionObserved && audit.nestedRestored && audit.closedRejected && audit.globalRestored;
            return json("preview_session_audit", "status", passed ? "passed" : "failed", "sample", x+","+z+"+"+side,
                    "globalSeed", globalSeed, "globalScale", globalScale, "sameSeedScale2MatchesGlobal", audit.sameDataMatchesGlobal,
                    "sameProviderDistinct", audit.sameProviderDistinct, "differentSeedScale", audit.nestedSeed+"/"+audit.nestedScale,
                    "differentDataDiffers", audit.differentDataDiffers, "nestedRestoredAfterException", audit.nestedRestored,
                    "closedRejectsWork", audit.closedRejected, "globalUnchanged", audit.globalRestored);
        } catch (Throwable t) {
            return json("preview_session_audit", "status", "failed", "error", t.getClass().getSimpleName(), "message", safe(t.getMessage()));
        }
    }

    private static String heightmapFingerprint(LocalTerrainProvider.HeightmapData data) {
        if (data == null) return "null";
        long hash = 0xcbf29ce484222325L;
        hash = fingerprint(hash, data.width); hash = fingerprint(hash, data.height);
        for (int row = 0; row < data.height; row++) for (int column = 0; column < data.width; column++) {
            hash = fingerprint(hash, data.heightmap[row][column]);
            hash = fingerprint(hash, data.biomeIds[row][column]);
            if (data.terralithBiomeIds != null) hash = fingerprint(hash, data.terralithBiomeIds[row][column]);
            if (data.ecology != null) {
                hash = fingerprint(hash, data.ecology.biomeId(column, row));
                hash = fingerprint(hash, data.ecology.terrainBiomeId(column, row));
                var sample = data.ecology.sample(column, row);
                hash = fingerprint(hash, Float.floatToIntBits(sample.temperature()));
                hash = fingerprint(hash, Float.floatToIntBits(sample.moisture()));
                hash = fingerprint(hash, Float.floatToIntBits(sample.treeCover()));
            }
        }
        return data.width+"x"+data.height+":"+Long.toUnsignedString(hash, 16);
    }

    private static long fingerprint(long hash, int value) { return (hash ^ value) * 0x100000001b3L; }

    private static String streamsBankCounters() {
        try {
            Class<?> policy = Class.forName("com.github.xandergos.terraindiffusionmc.compat.streams.StreamsBankMaterialPolicy");
            return "gravel="+policy.getMethod("gravelSubstitutions").invoke(null)+";stone="+policy.getMethod("stoneSubstitutions").invoke(null)
                    +";coherentPointBars="+policy.getMethod("coherentPointBarEvaluations").invoke(null);
        } catch (Throwable unavailable) { return "unavailable:"+unavailable.getClass().getSimpleName(); }
    }

    private static final class PreviewAudit {
        final LocalTerrainProvider globalProvider; final long globalSeed; final int globalScale; final String globalData;
        boolean sameProviderDistinct, nestedProviderDistinct, exceptionObserved, nestedRestored, closedRejected, globalRestored, sameDataMatchesGlobal, differentDataDiffers;
        long sameScopedSeed, nestedSeed; int sameScopedScale, nestedScale; String sameData = "", differentData = "";
        PreviewAudit(LocalTerrainProvider globalProvider, long globalSeed, int globalScale, String globalData) {
            this.globalProvider=globalProvider; this.globalSeed=globalSeed; this.globalScale=globalScale; this.globalData=globalData;
        }
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
        } catch (Throwable t) {
            LOG.error("Native topology discovery failed", t);
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            emit(receipts, json("streams_topology_discovery", "status", "unavailable", "error", cause.getClass().getSimpleName(), "message", safe(cause.getMessage())));
        }
        return result;
    }

    /** Opt-in bounded proof that a native terminal is physically connected to an ocean, not just wet locally. */
    private static String auditNativeEndpoint(ServerLevel level, NativePoint point) {
        if (point == null) return json("native_water_endpoint", "status", "missing");
        try {
            Object engine = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                    .getMethod("engineIfPresent", ServerLevel.class).invoke(null, level);
            Object lake = engine.getClass().getMethod("lakeAt", int.class, int.class).invoke(engine, point.x, point.z);
            Object lakeY = engine.getClass().getMethod("lakeSurfaceAt", int.class, int.class).invoke(engine, point.x, point.z);
            Object diag = engine.getClass().getMethod("planDiag", int.class, int.class).invoke(engine, point.x, point.z);
            var biome = level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(point.x >> 2,
                    point.waterY >> 2, point.z >> 2, level.getChunkSource().randomState().sampler());
            return json("native_water_endpoint", "status", "observed", "x", point.x, "z", point.z,
                    "waterY", point.waterY, "nativeLake", lake != null, "lakeSurfaceY", lakeY,
                    "sourceBiome", biome.unwrapKey().map(ResourceKey::location).orElse(null), "ocean", biome.is(BiomeTags.IS_OCEAN), "nativePlan", diag);
        } catch (Throwable e) { return json("native_water_endpoint", "status", "unavailable", "error", e.toString()); }
    }

    private static MarineConnectivityAudit auditMarineConnectivity(ServerLevel level, Config config) {
        MarineConnectivityAudit audit = new MarineConnectivityAudit();
        try {
            int centerX = integerProperty("td.streamsProbe.marineCenterX", integerProperty("td.streamsProbe.topologyCenterX", config.centerX * 16 + 8));
            int centerZ = integerProperty("td.streamsProbe.marineCenterZ", integerProperty("td.streamsProbe.topologyCenterZ", config.centerZ * 16 + 8));
            int radius = Math.max(64, Math.min(1024, integerProperty("td.streamsProbe.marineRadius", 512)));
            Class<?> worldgen = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen");
            Object engine = worldgen.getMethod("engineIfPresent", ServerLevel.class).invoke(null, level);
            if (engine == null) { audit.status="unresolved"; audit.error="engine_absent"; return audit; }
            Method platesNear = engine.getClass().getMethod("platesNear", double.class, double.class, double.class);
            Method networkFor = null, networkAt = engine.getClass().getMethod("networkAt", int.class, int.class);
            Map<String, MarineNode> nodes = new LinkedHashMap<>(); Map<String, List<MarineSegment>> outgoing = new LinkedHashMap<>();
            java.util.HashSet<String> networks = new java.util.HashSet<>();
            Object[] plates = (Object[]) platesNear.invoke(engine, (double) centerX, (double) centerZ, (double) radius);
            for (int i=0; i<Math.min(64, plates.length); i++) {
                if (networkFor == null) networkFor = engine.getClass().getMethod("networkFor", plates[i].getClass());
                addMarineNetwork(networkFor.invoke(engine, plates[i]), networks, nodes, outgoing, audit);
            }
            audit.networks = networks.size();
            MarineNode source = nodes.values().stream().filter(n -> n.in == 0 && n.out > 0)
                    .min(java.util.Comparator.comparingDouble(n -> Math.hypot(n.x-centerX, n.z-centerZ))).orElse(null);
            if (source == null) { audit.status="unresolved"; audit.error="no_bounded_source"; return audit; }
            String cursor = source.key; java.util.HashSet<String> visited = new java.util.HashSet<>();
            Method plan = engine.getClass().getMethod("plan", int.class, int.class);
            Class<?> cp = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
            Method inChannel=cp.getMethod("inChannel"), waterY=cp.getMethod("waterSurfaceY"), terminal=cp.getMethod("terminal");
            NativePoint sourcePoint = nativePlanNear(engine, new GraphNode(source.x,source.z), "source", "marine_source");
            if (sourcePoint != null) addMarineSample(audit,sourcePoint.x,sourcePoint.waterY,sourcePoint.z,true);
            double distanceSinceSample = 32;
            for (int step=0; step<8192; step++) {
                List<MarineSegment> nexts = outgoing.getOrDefault(cursor, List.of());
                if (nexts.isEmpty()) { audit.truncated=true; audit.error="no_successor_in_bounded_networks"; break; }
                MarineSegment next = nexts.stream().max(java.util.Comparator.comparingDouble(s -> s.w2)).orElseThrow();
                if (!visited.add(next.id())) { audit.truncated=true; audit.error="cycle_in_directed_segments"; break; }
                audit.segmentCount++;
                if (audit.segmentCount % 32 == 1) audit.widthObservations.add(String.format(java.util.Locale.ROOT, "%.2f>%.2f", next.w1, next.w2));
                MarineNode start=nodes.get(next.from), end=nodes.get(next.to);
                double segmentLength = Math.hypot(end.x-start.x,end.z-start.z);
                int samples=Math.max(1, (int)Math.ceil(segmentLength/32.0));
                for (int i=1; i<=samples; i++) {
                    distanceSinceSample += segmentLength / samples;
                    if (distanceSinceSample < 32) continue;
                    if (audit.samples.size() >= 256) { audit.truncated=true; audit.error="sample_budget_256"; break; }
                    int x=(int)Math.round(start.x+(end.x-start.x)*i/samples), z=(int)Math.round(start.z+(end.z-start.z)*i/samples);
                    NativePoint aligned = nativePlanNear(engine, new GraphNode(x,z), "any", "marine_sample");
                    if (aligned != null) addMarineSample(audit,aligned.x,aligned.waterY,aligned.z,true);
                    else { Object sampled=plan.invoke(engine,x,z); addMarineSample(audit,x,((Number)waterY.invoke(sampled)).intValue(),z,(Boolean)inChannel.invoke(sampled)); }
                    distanceSinceSample = 0;
                }
                if (audit.truncated) break;
                cursor = next.to;
                MarineNode endpoint = nodes.get(cursor);
                Object extra = networkAt.invoke(engine, (int)Math.round(endpoint.x), (int)Math.round(endpoint.z));
                if (extra != null && networks.size() < 64) addMarineNetwork(extra, networks, nodes, outgoing, audit);
                Object column = plan.invoke(engine, (int)Math.round(endpoint.x), (int)Math.round(endpoint.z));
                boolean channel=(Boolean)inChannel.invoke(column); int y=((Number)waterY.invoke(column)).intValue(); boolean isTerminal=(Boolean)terminal.invoke(column);
                if (isTerminal) {
                    audit.terminalX=(int)Math.round(endpoint.x); audit.terminalZ=(int)Math.round(endpoint.z); audit.terminalY=y; audit.terminalSeen=true;
                    addMarineSample(audit,audit.terminalX,y,audit.terminalZ,channel);
                    break;
                }
            }
            audit.networks = networks.size();
            if (audit.segmentCount >= 8192 && !audit.terminalSeen) { audit.truncated=true; audit.error="segment_budget_8192"; }
            if (audit.truncated || !audit.terminalSeen) { audit.status="truncated"; return audit; }
            if (audit.requiredChunks.size() > 32) { audit.status="truncated"; audit.error="route_chunk_budget_32"; return audit; }
            audit.status = "planned";
        } catch (Throwable t) { audit.status="unresolved"; audit.error=t.getClass().getSimpleName()+":"+safe(t.getMessage()); }
        return audit;
    }

    private static void addMarineNetwork(Object network, java.util.Set<String> seen, Map<String, MarineNode> nodes,
                                         Map<String, List<MarineSegment>> outgoing, MarineConnectivityAudit audit) throws Exception {
        if (network == null) return;
        String key=String.valueOf(network.getClass().getMethod("key").invoke(network)); if (!seen.add(key)) return;
        for (Object segment : (List<?>) network.getClass().getMethod("segments").invoke(network)) {
            double x1=endpoint(segment,"x1"), z1=endpoint(segment,"z1"), x2=endpoint(segment,"x2"), z2=endpoint(segment,"z2");
            MarineNode from=marineNode(nodes,x1,z1), to=marineNode(nodes,x2,z2); from.out++; to.in++;
            double w1=endpoint(segment,"w1"), w2=endpoint(segment,"w2"); MarineSegment edge=new MarineSegment(from.key,to.key,w1,w2);
            outgoing.computeIfAbsent(from.key, ignored -> new ArrayList<>()).add(edge);
        }
    }
    private static MarineNode marineNode(Map<String, MarineNode> nodes, double x, double z) {
        String key=nodeKey(x,z); return nodes.computeIfAbsent(key, ignored -> new MarineNode(key,x,z));
    }
    private static void addMarineSample(MarineConnectivityAudit audit, int x, int y, int z, boolean channel) {
        MarineSample sample=new MarineSample(x,y,z,channel); if (!audit.samples.contains(sample)) audit.samples.add(sample);
        audit.requiredChunks.add(new ChunkPos(x>>4,z>>4));
    }
    /** Called only after the background worker has requested the bounded planned chunks. */
    private static MarineConnectivityAudit evaluateMarineConnectivity(ServerLevel level, MarineConnectivityAudit audit) {
        if (!"planned".equals(audit.status)) return audit;
        // These are sparse plan samples, so a dry point is an observation rather than a
        // verdict about the unsampled channel between it and the terminal.
        for (MarineSample sample : audit.samples) loadedChannelWater(level,sample.x,sample.y,sample.z,sample.channel,audit);
        audit.sampledCorridorStatus = audit.unloaded ? "samples_unloaded"
                : !audit.dryPlannedSamples.isEmpty() || audit.unaligned ? "inconclusive_dry_or_unaligned_samples"
                : "all_sampled_planned_centers_wet";
        BlockPos terminalPos = new BlockPos(audit.terminalX, audit.terminalY, audit.terminalZ);
        // Saved biomes may be rebiomed to river by Streams; use the generator's direct source for marine classification.
        var sourceBiome = level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(terminalPos.getX() >> 2,
                terminalPos.getY() >> 2, terminalPos.getZ() >> 2, level.getChunkSource().randomState().sampler());
        audit.oceanSourceBiome = sourceBiome.unwrapKey().map(ResourceKey::location).map(String::valueOf).orElse("unkeyed");
        audit.oceanBiome = sourceBiome.is(BiomeTags.IS_OCEAN);
        // A native endpoint can be one grid cell inland while its channel joins nearby ocean water.
        // Do not turn a bounded negative search into an inland-sink claim.
        audit.oceanWaterConnected = boundedOceanWaterConnection(level, terminalPos, audit);
        // Terminal/mouth evidence is deliberately independent from sparse corridor samples.
        // `sampledCorridorStatus` tells whether those observations are complete or inconclusive.
        if (audit.oceanWaterConnected) audit.status = audit.oceanBiome ? "marine_outlet" : "shore_adjacent_ocean_connection";
        else if (audit.tdMarineWaterConnected) audit.status = "td_marine_mask_connection";
        else if (audit.unloaded) audit.status = "unloaded";
        else audit.status = "unresolved_bounded_connection";
        return audit;
    }
    private static boolean loadedChannelWater(ServerLevel level, int x, int y, int z, boolean channel, MarineConnectivityAudit audit) {
        LevelChunk chunk=level.getChunkSource().getChunkNow(x>>4,z>>4);
        if (chunk == null) { audit.unloaded=true; audit.unloadedChunks.add((x>>4)+","+(z>>4)); return false; }
        audit.sampledLoaded++;
        if (!channel) { audit.unaligned=true; audit.alignmentSamples.add(x+","+y+","+z); return false; }
        audit.plannedChannelSamples++;
        NativePoint physical = new NativePoint(x,z,y,y-1,0,0,"marine_physical");
        physical.readRole(level);
        boolean physicalWater=physical.valid;
        if (!physicalWater) {
            try {
                Object engine=Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen").getMethod("engineIfPresent",ServerLevel.class).invoke(null,level);
                Method plan=engine.getClass().getMethod("plan",int.class,int.class);
                Object center=plan.invoke(engine,x,z), cfg=engine.getClass().getMethod("config").invoke(engine);
                Method pointBar=Class.forName("dev.streamsreflowing.worldgen.ChannelBed").getDeclaredMethod("onPointBar",center.getClass(),cfg.getClass());
                pointBar.setAccessible(true);
                if ((Boolean)pointBar.invoke(null,center,cfg)) {
                    double lateral=Math.abs(endpoint(center,"lateral"));
                    for (int radius=1; radius<=2 && !physicalWater; radius++) for (int dz=-radius;dz<=radius && !physicalWater;dz++) for (int dx=-radius;dx<=radius && !physicalWater;dx++) {
                        if (Math.max(Math.abs(dx),Math.abs(dz))!=radius || level.getChunkSource().getChunkNow((x+dx)>>4,(z+dz)>>4)==null) continue;
                        Object adjacent=plan.invoke(engine,x+dx,z+dz);
                        if (!(Boolean)adjacent.getClass().getMethod("inChannel").invoke(adjacent) || Math.abs(endpoint(adjacent,"lateral"))>=lateral) continue;
                        int adjacentY=((Number)adjacent.getClass().getMethod("waterSurfaceY").invoke(adjacent)).intValue();
                        NativePoint lane=new NativePoint(x+dx,z+dz,adjacentY,adjacentY-1,0,0,"point_bar_wet_lane"); lane.readRole(level);
                        if (lane.valid) { physicalWater=true; audit.alignmentSamples.add("native_point_bar:"+x+","+z+"->"+(x+dx)+","+(z+dz)+"@"+lane.observedWaterY); }
                    }
                }
            } catch (Exception e) { audit.error="point_bar_check:"+e; }
        }
        if (physicalWater) audit.physicalWaterSamples++;
        if (!physicalWater) audit.dryPlannedSamples.add(x+","+y+","+z);
        return physicalWater;
    }
    /**
     * Bounded physical-water search from the traced terminal.  Direct generator biomes are
     * intentional: Streams may persist a minecraft:river biome over a source-ocean column.
     * A negative result is unresolved, because this is deliberately not a coastline scan.
     */
    private static boolean boundedOceanWaterConnection(ServerLevel level, BlockPos start, MarineConnectivityAudit audit) {
        final int horizontalRadius = 64, verticalRadius = 64, waterNodeBudget = 8192, checkedPositionBudget = 32768;
        final int seaLevel = level.getChunkSource().getGenerator().getSeaLevel();
        final int minY = Math.max(level.getMinBuildHeight(), start.getY() - verticalRadius);
        final int maxY = Math.min(level.getMaxBuildHeight() - 1, start.getY() + verticalRadius);
        final Map<Long, LocalTerrainProvider.HeightmapData> tdTiles = new LinkedHashMap<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        queue.add(start);
        while (!queue.isEmpty() && seen.size() < checkedPositionBudget && audit.waterSearchVisited < waterNodeBudget) {
            BlockPos p = queue.removeFirst();
            if (p.getY() < minY || p.getY() > maxY || Math.abs(p.getX() - start.getX()) > horizontalRadius
                    || Math.abs(p.getZ() - start.getZ()) > horizontalRadius) continue;
            long key = BlockPos.asLong(p.getX(), p.getY(), p.getZ());
            if (!seen.add(key)) continue;
            LevelChunk chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
            if (chunk == null) {
                // A missing neighbour makes this bounded negative inconclusive, but must not
                // discard already loaded frontiers that can still reach direct ocean water.
                audit.unloaded = true;
                String chunkKey = (p.getX() >> 4) + "," + (p.getZ() >> 4);
                if (!audit.unloadedChunks.contains(chunkKey)) audit.unloadedChunks.add(chunkKey);
                continue;
            }
            if (!chunk.getBlockState(p).getFluidState().is(FluidTags.WATER)) continue;
            audit.waterSearchVisited++;
            var directBiome = level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(p.getX() >> 2,
                    p.getY() >> 2, p.getZ() >> 2, level.getChunkSource().randomState().sampler());
            String directBiomeId = directBiome.unwrapKey().map(ResourceKey::location).map(String::valueOf).orElse("unkeyed");
            if (p.getY() >= seaLevel - 1 && directBiome.is(BiomeTags.IS_OCEAN) && !audit.oceanWaterConnected) {
                audit.oceanWaterConnected = true;
                audit.oceanConnectionSourceBiome = directBiomeId;
                audit.oceanConnectionPosition = p.getX() + "," + p.getY() + "," + p.getZ();
            }
            if (p.getY() >= seaLevel - 1 && !audit.tdMarineWaterConnected
                    && level.getChunkSource().getGenerator().getBiomeSource()
                    instanceof com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource) {
                TdMarineMask mask = tdMarineMaskAt(p.getX(), p.getZ(), tdTiles);
                if (mask != null && mask.modelElevation < 0 && EcologyShowcaseProfile.isVanillaOcean(mask.vanillaBiomeId)) {
                    audit.tdMarineWaterConnected = true;
                    audit.tdMarineMaskConnectionPosition = p.getX() + "," + p.getY() + "," + p.getZ();
                    audit.tdMarineMaskDirectBiome = directBiomeId;
                    audit.tdMarineMaskModelElevation = Short.toString(mask.modelElevation);
                    audit.tdMarineMaskVanillaOceanId = Short.toString(mask.vanillaBiomeId);
                }
            }
            if (audit.oceanWaterConnected && audit.tdMarineWaterConnected) return true;
            for (int[] d : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
                queue.add(p.offset(d[0], d[1], d[2]));
            }
        }
        audit.waterSearchBudgetHit = audit.waterSearchVisited >= waterNodeBudget;
        audit.waterSearchPositionBudgetHit = seen.size() >= checkedPositionBudget;
        return audit.oceanWaterConnected;
    }

    /** TD data is tile-cached for a single bounded BFS; it never changes generation state. */
    private static TdMarineMask tdMarineMaskAt(int x, int z, Map<Long, LocalTerrainProvider.HeightmapData> tiles) {
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
        return new TdMarineMask(data.heightmap[localZ][localX], data.biomeIds[localZ][localX]);
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

    /** Reads saved chunk blocks/biomes and the generator's direct biome-source prediction; no Streams types are referenced. */
    private static void auditSurface(ServerLevel level, LevelChunk chunk, ChunkGenerator generator, ChunkReceipt receipt) {
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        receipt.surfaceRuleTrace = surfaceRuleTrace(level, generator);
        for (int localZ=0; localZ<16; localZ++) for (int localX=0; localX<16; localX++) {
            int x=(receipt.chunkX<<4)+localX, z=(receipt.chunkZ<<4)+localZ, topmost=minY-1, topSolid=minY-1, topWater=minY-1;
            String topmostMaterial="minecraft:air";
            for (int y=maxY-1; y>=minY; y--) {
                var state=chunk.getBlockState(new BlockPos(x,y,z));
                if (topmost < minY && !state.isAir()) { topmost=y; topmostMaterial=String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())); }
                if (topWater < minY && state.getFluidState().is(FluidTags.WATER)) topWater=y;
                // Terrain-surface approximation: ignore foliage/log decoration and require a solid ground block.
                if (topSolid < minY && state.isSolid() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)) topSolid=y;
                if (topmost >= minY && topSolid >= minY && topWater >= minY) break;
            }
            BlockPos pos=new BlockPos(x, Math.max(minY, topSolid), z);
            String material=String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock()));
            String actual=level.getBiome(pos).unwrapKey().map(ResourceKey::location)
                    .map(id -> id.getNamespace()+":"+id.getPath()).orElse("unkeyed");
            String predicted=generator.getBiomeSource().getNoiseBiome(x>>2, Math.max(minY,topSolid)>>2,z>>2,
                    level.getChunkSource().randomState().sampler()).unwrapKey().map(ResourceKey::location)
                    .map(id -> id.getNamespace()+":"+id.getPath()).orElse("unkeyed");
            boolean mismatch=!actual.equals(predicted), grassBeach="minecraft:grass_block".equals(material) && "terralith:gravel_beach".equals(actual);
            if (mismatch) receipt.surfaceBiomeMismatchColumns++;
            if (grassBeach) receipt.grassInGravelBeachColumns++;
            receipt.surfaceMaterialCounts.merge(material,1,Integer::sum);
            String ecology = "";
            if (Boolean.getBoolean("td.streamsProbe.ecologyAudit")) {
                int originalGround = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
                        level, level.getChunkSource().randomState()) - 1;
                ecology = ecologyAuditFields(level, chunk, generator, x, z, originalGround, maxY, receipt);
            }
            receipt.surfaceAuditRows.add(x+","+z+","+topmost+","+csv(topmostMaterial)+","+topSolid+","+csv(material)+","+csv(actual)+","+csv(predicted)+","+topWater+","+mismatch+","+grassBeach+ecology);
        }
    }

    /** Opt-in ecology evidence uses the same natural-ecology lookup used by EcoTreesFeature. */
    private static String ecologyAuditFields(ServerLevel level, LevelChunk chunk, ChunkGenerator generator, int x, int z,
                                             int originalGroundY, int maxY, ChunkReceipt receipt) {
        int logs = 0;
        for (int y=Math.max(level.getMinBuildHeight(), originalGroundY + 1); y<maxY; y++)
            if (chunk.getBlockState(new BlockPos(x,y,z)).is(BlockTags.LOGS)) logs++;
        boolean logAboveOriginalGround = logs > 0;
        if (logAboveOriginalGround) receipt.ecologyLogColumns++;
        receipt.ecologyObservedLogBlocks += logs;
        try {
            EcologyClassifier.Sample sample = NaturalEcology.sample(generator, x, z);
            if (sample == null) { receipt.ecologyUnavailableColumns++; return unavailableEcologyFields(originalGroundY, logAboveOriginalGround, logs); }
            receipt.ecologySamples++;
            receipt.ecologyBiomeCounts.merge(Integer.toString(sample.biomeId()), 1, Integer::sum);
            return ","+sample.biomeId()+","+sample.treeCover()+","+sample.temperature()+","+sample.moisture()+","+sample.slope()
                    +","+originalGroundY+","+logAboveOriginalGround+","+logs;
        } catch (RuntimeException unavailable) {
            receipt.ecologyUnavailableColumns++;
            return unavailableEcologyFields(originalGroundY, logAboveOriginalGround, logs);
        }
    }

    /** Exactly eight opt-in ecology CSV fields, including four unavailable climate/control placeholders. */
    private static String unavailableEcologyFields(int originalGroundY, boolean logAboveOriginalGround, int logs) {
        return ",unavailable,,,,," + originalGroundY + "," + logAboveOriginalGround + "," + logs;
    }

    private static String surfaceRuleTrace(ServerLevel level, ChunkGenerator generator) {
        try {
            String settingsId = "unknown";
            try {
                Object holder = generator.getClass().getMethod("generatorSettings").invoke(generator);
                Object optional = holder.getClass().getMethod("unwrapKey").invoke(holder);
                if (optional instanceof java.util.Optional<?> key && key.isPresent()) {
                    Object location = key.get().getClass().getMethod("location").invoke(key.get());
                    settingsId = String.valueOf(location);
                }
            } catch (ReflectiveOperationException ignored) { }
            if ("unknown".equals(settingsId) || !settingsId.contains(":")) return "settings=unknown;resource_unresolved;"+runtimeSurfaceRuleTrace(level, generator);
            String[] id = settingsId.split(":", 2);
            String expected = id[0] + ":worldgen/noise_settings/" + id[1] + ".json";
            var resources=level.getServer().getResourceManager().listResources("worldgen/noise_settings", path -> path.toString().equals(expected));
            var resource=resources.get(net.minecraft.resources.ResourceLocation.parse(expected));
            if (resource == null) return "settings="+settingsId+";expected="+expected+";resource_unresolved;"+runtimeSurfaceRuleTrace(level, generator);
            try (var stream=resource.open()) {
                String text=new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                return "settings="+settingsId+";resource="+expected+";pack="+resource.sourcePackId()
                        +";resource_contains_gravel_beach="+text.contains("terralith:gravel_beach")+";"+runtimeSurfaceRuleTrace(level, generator);
            }
        } catch (Exception e) { return "surface_rule_trace_unavailable:"+e.getClass().getSimpleName(); }
    }

    /** Runtime object trace detects modifications applied after a datapack resource was loaded. */
    private static String runtimeSurfaceRuleTrace(ServerLevel level, ChunkGenerator generator) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return "runtime_surface_rule=unavailable:not_noise_based";
        try {
            NoiseGeneratorSettings settings = noise.generatorSettings().value();
            var result = SurfaceRules.RuleSource.CODEC.encodeStart(JsonOps.INSTANCE, settings.surfaceRule());
            var json = result.getOrThrow();
            String encoded = json.toString();
            Path output = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("td-streams-probe-runtime-surface-rule.json");
            Files.writeString(output, encoded, java.nio.charset.StandardCharsets.UTF_8);
            return "runtime_surface_rule_json="+output.getFileName()+";runtime_contains_gravel_beach="+encoded.contains("terralith:gravel_beach");
        } catch (Throwable t) { return "runtime_surface_rule=unavailable:"+t.getClass().getSimpleName(); }
    }

    private static void writeSurfaceAuditCsv(MinecraftServer server, ChunkReceipt receipt) {
        Path output=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("td-streams-probe-surface-audit-"+receipt.chunkX+"-"+receipt.chunkZ+".csv");
        List<String> lines=new ArrayList<>(receipt.surfaceAuditRows.size()+1);
        lines.add("x,z,actualTopmostY,actualTopmostBlock,terrainTopSolidY,terrainTopSolidBlock,actualSavedTerrainBiome,directBiomeSourceTerrainBiome,actualTopWaterY,predictedActualMismatch,grassBlockInGravelBeach"
                + (Boolean.getBoolean("td.streamsProbe.ecologyAudit") ? ",ecoBiomeId,ecoTreeCover,ecoTemperature,ecoMoisture,ecoSlope,originalGroundY,actualLogAboveOriginalGround,actualLogBlocksAboveOriginalGround" : ""));
        lines.addAll(receipt.surfaceAuditRows);
        try { Files.write(output,lines); receipt.surfaceAuditCsvFile=output.getFileName().toString(); }
        catch (IOException e) { receipt.surfaceAuditCsvFile="write_error:"+e.getClass().getSimpleName(); }
    }

    /** Bounded coarse-only overview; never fetches an overview-sized native heightmap. */
    private static String writeEcologyOverview(MinecraftServer server) {
        final int radius = Math.max(512, Math.min(32768, Integer.getInteger("td.streamsProbe.ecologyOverviewRadius", 4096)));
        final int min = -radius, max = radius;
        final int step = Math.max((2 * radius + 511) / 512,
                Math.max(32, Integer.getInteger("td.streamsProbe.ecologyOverviewStep", 64)));
        Path output = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("td-streams-probe-ecology-overview.csv");
        try {
            var field = LocalTerrainProvider.getBroadEcologyClimate(min, min, max, max,
                    com.github.xandergos.terraindiffusionmc.world.WorldScaleManager.getCurrentScale());
            List<String> lines = new ArrayList<>(((max-min)/step) * ((max-min)/step) + 1);
            lines.add("x,z,coarseTemperature,coarsePrecipitation,coarseElevation,ecoBiomeId,ecoTreeCover,ecoMoisture,classification");
            for (int z=min; z<max; z+=step) for (int x=min; x<max; x+=step) {
                var climate = field.sample(x, z);
                // Coarse approximation only: no exact local terrain slope or coast state is fetched for this overview.
                var ecology = EcologyClassifier.select(x, z, climate.elevation(), 0.0F, false,
                        climate.temperature(), climate.precipitation(), climate.elevation()).sample();
                lines.add(x+","+z+","+climate.temperature()+","+climate.precipitation()+","+climate.elevation()+","+
                        ecology.biomeId()+","+ecology.treeCover()+","+ecology.moisture()+",coarse_slope0_coastfalse");
            }
            Files.write(output, lines);
            return json("ecology_overview", "status", "written", "csv", output.getFileName(), "minX", min, "minZ", min,
                    "maxXExclusive", max, "maxZExclusive", max, "step", step, "samples", lines.size() - 1,
                    "method", "coarse_only;EcologyClassifier.select(elevation,slope=0,coast=false)");
        } catch (Exception e) {
            return json("ecology_overview", "status", "unavailable", "error", e.getClass().getSimpleName(), "message", safe(e.getMessage()),
                    "method", "coarse_only;no_heightmap_region_fetch");
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
            String file = System.getProperty("td.streamsProbe.chunksFile", "").trim();
            if (!file.isEmpty()) {
                if (!value.isEmpty()) throw new IllegalArgumentException("Use chunks or chunksFile, not both");
                try { value = Files.readString(java.nio.file.Path.of(file)).trim().replace('\n', ';').replace("\r", ""); }
                catch (IOException e) { throw new IllegalArgumentException("Cannot read fixed chunk file: " + file, e); }
            }
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
        String surfaceAuditCsvFile = "", surfaceRuleTrace = "";
        int surfaceBiomeMismatchColumns, grassInGravelBeachColumns;
        int ecologySamples, ecologyUnavailableColumns, ecologyLogColumns, ecologyObservedLogBlocks;
        final Map<String, Integer> ecologyBiomeCounts = new LinkedHashMap<>();
        final List<String> surfaceAuditRows = new ArrayList<>();
        final Map<String, Integer> surfaceMaterialCounts = new LinkedHashMap<>();
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
        String surfaceAuditJson() { return StreamsReflowingProbe.json("surface_audit", "chunk", chunkX+","+chunkZ,
                "grassInGravelBeachColumns", grassInGravelBeachColumns, "predictedActualMismatchColumns", surfaceBiomeMismatchColumns,
                "materialCounts", map(surfaceMaterialCounts), "csv", surfaceAuditCsvFile, "surfaceRuleTrace", surfaceRuleTrace,
                "ecologyAuditEnabled", Boolean.getBoolean("td.streamsProbe.ecologyAudit"), "ecologySamples", ecologySamples,
                "ecologyUnavailableColumns", ecologyUnavailableColumns, "ecologyBiomeCounts", map(ecologyBiomeCounts),
                "actualLogColumnsAboveOriginalGround", ecologyLogColumns, "actualLogBlocksAboveOriginalGround", ecologyObservedLogBlocks,
                "method", "actual_chunk_block+saved_level_biome+direct_generator_biome_source"); }
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

    private static final class ShoreAudit {
        int minX,minZ,maxX,maxZ,tileSize,tiles; String error="";
        String mapping = "unresolved";
        final Map<String,Integer> counts=new LinkedHashMap<>(); final Map<String,List<ChunkPos>> chunks=new LinkedHashMap<>();
        final Map<String,String> representatives=new LinkedHashMap<>(); int landNearShore;
        final Map<String,Short> highest = new LinkedHashMap<>();
        void add(String type,int x,int z,short elevation) {
            counts.merge(type,1,Integer::sum); representatives.putIfAbsent(type,x+","+z);
            List<ChunkPos> selected=chunks.computeIfAbsent(type, ignored -> new ArrayList<>()); ChunkPos chunk=new ChunkPos(new BlockPos(x,0,z));
            if (selected.isEmpty()) selected.add(chunk);
            if (!highest.containsKey(type) || elevation > highest.get(type)) {
                highest.put(type, elevation);
                representatives.put(type + "_highest", x + "," + z);
                if (!selected.getFirst().equals(chunk)) {
                    if (selected.size() == 1) selected.add(chunk);
                    else selected.set(1, chunk);
                }
            }
        }
        void addLand(int x,int z) { landNearShore++; representatives.putIfAbsent("land_near_shore",x+","+z); }
        List<ChunkPos> representativeChunks() { return chunks.values().stream().flatMap(List::stream).toList(); }
        String json() { return StreamsReflowingProbe.json("shore_audit", "status", error.isEmpty()?"exact_tile_ecology":"unavailable", "error", error,
                "bounds", minX+","+minZ+".."+maxX+","+maxZ, "tileSize", tileSize, "tiles", tiles, "shoreCounts", map(counts),
                "representatives", representatives.toString(), "landNearShoreColumns", landNearShore, "representativeChunkCount", representativeChunks().size(),
                "mapping", mapping, "method", "exact_HeightmapData.ecology.terrainBiomeId;two_by_one_tile_bound"); }
    }

    private record ShoreMapping(short gravelBeachId, boolean valid, String resolvedName) {
        String report() { return "pure_id_mapping;gravelBeachId="+gravelBeachId+";resolved="+resolvedName+";valid="+valid; }
    }

    private static final class GraphNode {
        final double x, z; int inDegree, outDegree;
        GraphNode(double x, double z) { this.x=x; this.z=z; }
    }
    private static final class MarineNode {
        final String key; final double x, z; int in, out;
        MarineNode(String key, double x, double z) { this.key=key; this.x=x; this.z=z; }
    }
    private record MarineSegment(String from, String to, double w1, double w2) {
        String id() { return from+">"+to+":"+w1+":"+w2; }
    }
    private record MarineSample(int x, int y, int z, boolean channel) { }
    private record TdMarineMask(short modelElevation, short vanillaBiomeId) { }
    private static final class MarineConnectivityAudit {
        String status="unresolved", error="", oceanSourceBiome="", oceanConnectionSourceBiome="", oceanConnectionPosition="", tdMarineMaskConnectionPosition="", tdMarineMaskDirectBiome="", tdMarineMaskModelElevation="", tdMarineMaskVanillaOceanId="", sampledCorridorStatus="not_evaluated"; int networks, segmentCount, sampledLoaded, plannedChannelSamples, physicalWaterSamples, terminalX, terminalY, terminalZ, waterSearchVisited;
        boolean terminalSeen, oceanBiome, oceanWaterConnected, tdMarineWaterConnected, waterSearchBudgetHit, waterSearchPositionBudgetHit, truncated, unloaded, unaligned;
        final List<String> widthObservations=new ArrayList<>(), unloadedChunks=new ArrayList<>(), dryPlannedSamples=new ArrayList<>(), alignmentSamples=new ArrayList<>();
        final List<MarineSample> samples=new ArrayList<>(); final java.util.LinkedHashSet<ChunkPos> requiredChunks=new java.util.LinkedHashSet<>();
        boolean preparable() { return "planned".equals(status) && requiredChunks.size() <= 32; }
        String planJson() { return StreamsReflowingProbe.json("marine_connectivity_plan", "status", status, "error", error,
                "requiredChunkCount", requiredChunks.size(), "requiredChunks", requiredChunks.toString(), "sampleCount", samples.size(),
                "prepareEligible", preparable(), "procedure", "rerun_with_marinePrepareChunks=true_to_generate_at_most_32_planned_chunks_then_recheck_physical_water"); }
        String json() { return StreamsReflowingProbe.json("marine_connectivity_audit", "status", status, "error", error,
                "networks", networks, "segments", segmentCount, "terminal", terminalSeen ? terminalX+","+terminalY+","+terminalZ : "missing",
                "terminalOceanBiome", oceanBiome, "oceanSourceBiome", oceanSourceBiome, "oceanConnectionSourceBiome", oceanConnectionSourceBiome, "oceanConnectionPosition", oceanConnectionPosition,
                "oceanWaterConnected3d", oceanWaterConnected, "tdMarineWaterConnected3d", tdMarineWaterConnected, "tdMarineMaskConnectionPosition", tdMarineMaskConnectionPosition,
                "tdMarineMaskDirectBiome", tdMarineMaskDirectBiome, "tdMarineMaskModelElevation", tdMarineMaskModelElevation, "tdMarineMaskVanillaOceanId", tdMarineMaskVanillaOceanId,
                "waterSearchVisited", waterSearchVisited, "waterSearchBudgetHit", waterSearchBudgetHit, "waterSearchPositionBudgetHit", waterSearchPositionBudgetHit, "loadedSamples", sampledLoaded,
                "plannedChannelSamples", plannedChannelSamples, "physicalWaterSamples", physicalWaterSamples, "sampledCorridorStatus", sampledCorridorStatus,
                "unloadedChunks", String.join(";", unloadedChunks), "dryPlannedSamples", String.join(";", dryPlannedSamples), "alignmentSamples", String.join(";", alignmentSamples),
                "widthObservationsEvery32Segments", String.join(";", widthObservations), "bounds", "networks<=64;segments<=8192;samples<=256;prepareChunks<=32;sampled_trunk_continuity;waterBfsHorizontal<=64;waterBfsVertical<=64;waterBfsWaterNodes<=8192;waterBfsCheckedPositions<=32768;marineRequiresDirectOceanBiome+waterAtSeaLevelMinus1"); }
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
        int physicalBedY = Integer.MIN_VALUE, waterColumnDepth, observedWaterY;
        String waterBlock = "", bedBlock = "", physicalBedBlock = "";
        NativePoint(int x, int z, int waterY, int bedY, double flowX, double flowZ, String provenance) {
            this.x=x; this.z=z; this.waterY=waterY; this.bedY=bedY; this.flowX=flowX; this.flowZ=flowZ; this.provenance=provenance;
        }
        /** Exact native-plan water/bed evidence for source, confluence and terminal roles. */
        void readRole(ServerLevel level) {
            observedWaterY = waterY;
            var waterState = level.getBlockState(new BlockPos(x, observedWaterY, z));
            if (waterState.isAir() && level.getFluidState(new BlockPos(x, waterY - 1, z)).is(FluidTags.WATER)) {
                observedWaterY--; waterState = level.getBlockState(new BlockPos(x, observedWaterY, z));
            }
            water = waterState.getFluidState().is(FluidTags.WATER);
            flowNonZero = Math.abs(flowX)+Math.abs(flowZ)>1.0e-6;
            waterBlock = String.valueOf(waterState);
            bedSolid = false; physicalBedSolid = false; physicalBedY = Integer.MIN_VALUE; waterColumnDepth = water ? 1 : 0;
            for (int depth=1; water && depth<=64; depth++) {
                int y=observedWaterY-depth;
                if (y < level.getMinBuildHeight()) break;
                var state=level.getBlockState(new BlockPos(x,y,z));
                if (state.getFluidState().is(FluidTags.WATER)) { waterColumnDepth++; continue; }
                physicalBedY=y; bedSolid=physicalBedSolid=state.isSolid(); bedBlock=physicalBedBlock=String.valueOf(state); break;
            }
            waterColumnContinuous = water && bedSolid;
            valid = waterColumnContinuous;
        }
        /** Flow channels may be carved below ColumnPlan.bedY by native features; prove a bounded, hole-free physical column instead. */
        void readFlowColumn(ServerLevel level, int maximumDepth) {
            readRole(level); // Retain planned-bed evidence separately from the physical bed.
            flowNonZero = Double.isFinite(flowX) && Double.isFinite(flowZ) && Math.abs(flowX)+Math.abs(flowZ)>1.0e-6;
            physicalBedY = Integer.MIN_VALUE; physicalBedSolid = false; physicalBedBlock = "";
            waterColumnDepth = water ? 1 : 0;
            waterColumnContinuous = water;
            for (int depth = 1; waterColumnContinuous && depth <= maximumDepth; depth++) {
                int y = observedWaterY - depth; if (y < level.getMinBuildHeight()) { waterColumnContinuous = false; break; }
                var state = level.getBlockState(new BlockPos(x, y, z));
                if (state.getFluidState().is(FluidTags.WATER)) { waterColumnDepth = depth + 1; continue; }
                physicalBedY = y; physicalBedSolid = state.isSolid(); physicalBedBlock = String.valueOf(state);
                waterColumnContinuous = physicalBedSolid; break;
            }
            valid = water && flowNonZero && waterColumnContinuous && physicalBedSolid;
        }
        String canonical() { return x+","+z+","+waterY+","+bedY+","+water+","+bedSolid+","+flowNonZero+","+flowX+","+flowZ+","+provenance+","+waterBlock+","+bedBlock
                + ",physicalBed="+physicalBedY+","+physicalBedSolid+","+waterColumnDepth+","+waterColumnContinuous+","+physicalBedBlock+",observedWaterY="+observedWaterY; }
        public String toString() { return canonical(); }
    }

    private record RouteCandidate(int chunkX, int chunkZ, int channelColumns, int waterSurfaceY, String samples) {}
    private record ColumnSnapshot(int x, int z, int baseHeight, int surfaceY, int topWaterY, int groundY, String naturalBiome) {}
    private record Tickability(long tick, int tickableCount) {}
}
