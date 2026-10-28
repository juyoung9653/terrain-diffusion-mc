package com.terrain.chunkprofile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** A deliberately bounded, client-integrated-server-only fresh chunk profiler. */
public final class ChunkProfileClient {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final TicketType<ChunkPos> TICKET = TicketType.create("td-chunk-profile", Comparator.comparingLong(ChunkPos::toLong));
    private static ChunkProfileClient INSTANCE;
    private final String world = System.getProperty("chunkprofile.world", "chunk-profile");
    private final Path output = Path.of(System.getProperty("chunkprofile.output", "chunk-profile-receipt.json"));
    private final int firstX = integer("chunkprofile.chunkX", 1024, -1875000, 1875000);
    private final int firstZ = integer("chunkprofile.chunkZ", 1024, -1875000, 1875000);
    private final int side = integer("chunkprofile.side", 8, 1, 16);
    private final int prefetch = integer("chunkprofile.prefetch", 8, 1, 32);
    private final List<Region> regions = parseRegions(System.getProperty("chunkprofile.regions", ""));
    private final long settleNanos = integer("chunkprofile.settleSeconds", 30, 0, 300) * 1_000_000_000L;
    private final long streamsSettleNanos = integer("chunkprofile.streamsSettleSeconds", 0, 0, 900) * 1_000_000_000L;
    private final long maxSettleNanos = integer("chunkprofile.maxSettleSeconds", 300, 30, 600) * 1_000_000_000L;
    private final long timeoutSeconds = integer("chunkprofile.timeoutSeconds", 900, 30, 1800);
    private final boolean streamsGroundMarineAudit = Boolean.getBoolean("td.streams.groundMarineAudit");
    private boolean requested, configured, finished, warningHandled, launched;
    private long readyNanos, nextReadinessCheckNanos, idleSinceNanos = -1L;
    private long observedStreamsTraceEvents = -1L;
    private Map<String,Object> lastIdleSnapshot = Map.of();

    static void install() {
        if (INSTANCE != null) return;
        INSTANCE = new ChunkProfileClient();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> INSTANCE.tick());
    }

    private static int integer(String key, int fallback, int min, int max) {
        try { int result = Integer.parseInt(System.getProperty(key, Integer.toString(fallback))); return result < min || result > max ? fallback : result; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (finished) return;
        if (mc.screen instanceof net.neoforged.neoforge.client.gui.LoadingErrorScreen && !warningHandled) {
            warningHandled = true;
            var issues = net.neoforged.fml.ModLoader.getLoadingIssues();
            if (issues.isEmpty() || issues.stream().anyMatch(i -> i.severity() == net.neoforged.fml.ModLoadingIssue.Severity.ERROR)) { fail("mod_loading_error"); return; }
            String label = net.neoforged.fml.i18n.FMLTranslations.parseMessage("fml.button.continue.launch");
            for (var child : mc.screen.children()) if (child instanceof Button b && b.active && b.getMessage().getString().equals(label)) { b.onPress(); return; }
            fail("loading_warning_continue_unavailable"); return;
        }
        if (!requested && mc.level == null && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            if (!mc.getLevelSource().levelExists(world)) { fail("clone_world_missing:" + world); return; }
            requested = true; mc.options.pauseOnLostFocus = false;
            LOG.info("Chunk profile opening cloned world {}", world);
            mc.createWorldOpenFlows().openWorld(world, () -> fail("open_world_failed"));
            return;
        }
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        if (!configured) {
            configured = true; readyNanos = System.nanoTime();
            LOG.info("CHUNK_PROFILE_SETTLE_START");
            lifecycle("settling", 0L, Map.of());
        }
        long now = System.nanoTime();
        if (!launched && now - readyNanos > maxSettleNanos) {
            fail(streamsSettleNanos > 0 ? "td_or_streams_not_quiescent_before_maxSettleSeconds" : "td_inference_not_idle_before_maxSettleSeconds"); return;
        }
        if (!launched && now - readyNanos >= settleNanos && now >= nextReadinessCheckNanos) {
            nextReadinessCheckNanos = now + 1_000_000_000L;
            lastIdleSnapshot = readinessSnapshot();
            boolean idle = readinessIdle(lastIdleSnapshot);
            if (idle) { if (idleSinceNanos < 0L) idleSinceNanos = now; }
            else idleSinceNanos = -1L;
            lifecycle(idle ? "awaiting_idle_confirmation" : streamsSettleNanos > 0 ? "awaiting_td_streams_quiescence" : "awaiting_td_idle", now - readyNanos, lastIdleSnapshot);
        }
        if (!launched && idleSinceNanos >= 0L && now - idleSinceNanos >= requiredIdleNanos()) {
            launched = true;
            MinecraftServer server = mc.getSingleplayerServer();
            long readinessWait = now - readyNanos;
            Thread worker = new Thread(() -> run(server, readinessWait, lastIdleSnapshot), "td-chunk-profile-worker");
            worker.setDaemon(true); worker.start();
        }
    }

    private void lifecycle(String status, long elapsedNanos, Map<String,Object> inference) {
        try { atomic(output.resolveSibling("progress.json"), Map.of("status",status,"settleElapsedSeconds",seconds(elapsedNanos),"requiredIdleSeconds",seconds(requiredIdleNanos()),"inference",inference,"updatedUtc",Instant.now().toString())); }
        catch (IOException failure) { fail("progress_write_failed:" + failure.getClass().getSimpleName()); }
    }
    private static long asLong(Object value) { return value instanceof Number number ? number.longValue() : -1L; }
    private long requiredIdleNanos() { return Math.max(3_000_000_000L, streamsSettleNanos); }
    private void resetStreamsTraceObservation() { observedStreamsTraceEvents = -1L; }
    /** A trace-stable interval is observed quiescence only; it cannot prove no future native work will be submitted. */
    private Map<String,Object> readinessSnapshot() {
        Map<String,Object> result = new LinkedHashMap<>(inferenceReadiness());
        if (streamsSettleNanos > 0) result.put("streamsBuildTrace", streamsBuildTrace());
        return result;
    }
    private boolean readinessIdle(Map<String,Object> snapshot) {
        if (asLong(snapshot.get("active")) != 0L || asLong(snapshot.get("queued")) != 0L) return false;
        if (streamsSettleNanos <= 0) return true;
        Object rawTrace = snapshot.get("streamsBuildTrace");
        if (!(rawTrace instanceof Map<?,?> trace) || !Boolean.TRUE.equals(trace.get("available"))) return false;
        Object rawSnapshot = trace.get("snapshot");
        if (!(rawSnapshot instanceof Map<?,?> values) || !Boolean.TRUE.equals(values.get("enabled"))) return false;
        long events = asLong(values.get("eventCount"));
        if (events < 0L || asLong(values.get("inflightCount")) != 0L
                || asLong(values.get("historyDrops")) != 0L || asLong(values.get("reflectionFailures")) != 0L
                || traceHasErrors(values.get("events")) || traceHasErrors(values.get("inflight"))) return false;
        boolean changed = observedStreamsTraceEvents >= 0L && observedStreamsTraceEvents != events;
        observedStreamsTraceEvents = events;
        return !changed;
    }
    private static boolean traceHasErrors(Object raw) {
        if (!(raw instanceof Iterable<?> rows)) return true;
        for (Object row : rows) {
            if (!(row instanceof Map<?,?> values)) return true;
            Object error = values.get("error");
            if (error != null && !String.valueOf(error).isBlank()) return true;
        }
        return false;
    }

    private void run(MinecraftServer server, long readinessWait, Map<String,Object> idleSnapshot) {
        List<Region> requested = regions.isEmpty() ? List.of(new Region(firstX, firstZ)) : regions;
        Map<String,Object> root = new LinkedHashMap<>(); List<Map<String,Object>> phases = new ArrayList<>();
        root.put("phases", phases); root.put("world", world); root.put("requestedRegions", requested.stream().map(r -> List.of(r.x,r.z)).toList());
        try {
            if (streamsSettleNanos > 0) {
                Map<String,Object> recheck = readinessSnapshot();
                root.put("initialStreamsQuiescenceRevalidation", recheck);
                if (!readinessIdle(recheck)) {
                    root.put("status", "failed"); root.put("error", "td_or_streams_not_quiescent_at_initial_measurement_boundary");
                    return;
                }
            }
            for (int index=0; index<requested.size(); index++) {
                Region region = requested.get(index);
                if (index > 0) {
                    resetStreamsTraceObservation();
                    Map<String,Object> boundary = awaitIdleBoundary(index, requested.size());
                    if (!Boolean.TRUE.equals(boundary.get("idleVerified"))) {
                        Map<String,Object> failed = new LinkedHashMap<>(); failed.put("status","failed"); failed.put("error",streamsSettleNanos > 0 ? "td_or_streams_not_quiescent_between_phases" : "td_inference_not_idle_between_phases"); failed.put("boundary",boundary); phases.add(failed); break;
                    }
                    readinessWait = (long)(((Number)boundary.get("waitSeconds")).doubleValue() * 1_000_000_000L);
                    idleSnapshot = (Map<String,Object>)boundary.get("lastInferenceSnapshot");
                }
                phases.add(measureRegion(server, readinessWait, idleSnapshot, region, index, requested.size()));
                if (!"success".equals(phases.getLast().get("status"))) break;
            }
            root.put("status", phases.size()==requested.size() && phases.stream().allMatch(p -> "success".equals(p.get("status"))) ? "success" : "failed");
        } catch (Throwable failure) { root.put("status","failed"); root.put("error",failure.toString()); LOG.error("Chunk profile phases failed", failure); }
        finally {
            if (streamsGroundMarineAudit) {
                Map<String,Object> audit = streamsGroundMarineAudit();
                root.put("streamsGroundMarineAuditFinal", audit);
                if (!streamsGroundMarineAuditPassed(audit)) {
                    root.put("status", "failed");
                    root.put("streamsGroundMarineAuditError", "unavailable, zero eligible samples, or nonzero mismatches");
                }
            }
            try { atomic(output,root); atomic(output.resolveSibling("progress.json"),Map.of("status",root.get("status"),"completedPhases",phases.size(),"totalPhases",requested.size())); } catch(IOException e){LOG.error("Chunk profile root receipt write failed",e);} finished=true; Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
        }
    }

    /** A phase boundary requires TD idleness, and optionally observed native Streams quiescence. */
    private Map<String,Object> awaitIdleBoundary(int phaseIndex, int phaseCount) {
        long began = System.nanoTime(), idleSince = -1L; Map<String,Object> snapshot = Map.of();
        while (System.nanoTime()-began <= maxSettleNanos) {
            snapshot = readinessSnapshot(); boolean idle=readinessIdle(snapshot);
            long now=System.nanoTime(); if(idle){if(idleSince<0L)idleSince=now;if(now-idleSince>=requiredIdleNanos())return Map.of("idleVerified",true,"waitSeconds",seconds(now-began),"requiredIdleSeconds",seconds(requiredIdleNanos()),"lastInferenceSnapshot",snapshot);}else idleSince=-1L;
            try { atomic(output.resolveSibling("progress.json"),Map.of("status",streamsSettleNanos > 0 ? "awaiting_phase_td_streams_quiescence" : "awaiting_phase_idle","phaseIndex",phaseIndex,"phaseCount",phaseCount,"waitSeconds",seconds(now-began),"requiredIdleSeconds",seconds(requiredIdleNanos()),"inference",snapshot)); Thread.sleep(1_000L); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } catch (IOException ignored) { }
        }
        return Map.of("idleVerified",false,"waitSeconds",seconds(System.nanoTime()-began),"requiredIdleSeconds",seconds(requiredIdleNanos()),"lastInferenceSnapshot",snapshot);
    }

    private Map<String,Object> measureRegion(MinecraftServer server, long readinessWait, Map<String,Object> idleSnapshot, Region region, int phaseIndex, int phaseCount) {
        Map<String,Object> report = new LinkedHashMap<>();
        long started = -1L;
        long processBefore = -1L;
        CpuSampler sampler = new CpuSampler();
        List<Map<String,Object>> batches = new ArrayList<>();
        List<Map<String,Object>> rows = new ArrayList<>();
        report.put("batches", batches);
        report.put("chunks", rows);
        try {
            ServerLevel level = server.overworld();
            List<ChunkPos> chunks = positions(region.x, region.z);
            report.put("status", "running"); report.put("startedUtc", Instant.now().toString());
            report.put("world", world); report.put("phaseIndex",phaseIndex); report.put("phaseCount",phaseCount); report.put("target", Map.of("firstChunk", List.of(region.x, region.z), "side", side, "count", chunks.size(), "prefetch", prefetch));
            report.put("readiness", Map.of("settleSecondsRequested", seconds(settleNanos), "streamsSettleSecondsRequested", seconds(streamsSettleNanos), "maxSettleSeconds",seconds(maxSettleNanos), "waitBeforeFirstTicketSeconds",seconds(readinessWait), "idleConfirmationSeconds",seconds(requiredIdleNanos()),"lastInferenceSnapshot",idleSnapshot));
            report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            report.put("timingScope", "loaded-chunk precheck through all FULL futures and ticket cleanup; baseline and cache-key snapshots are outside this interval; normal integrated-server activity continues");
            report.put("processCpuScope", "whole JVM process CPU delta, including all mod and native work during the measurement window");
            report.put("tdBefore", tdMetrics()); report.put("onnxBefore", onnxMetrics());
            if (streamsGroundMarineAudit) report.put("streamsGroundMarineAuditBefore", streamsGroundMarineAudit());
            long cacheKeysBeforeStarted = System.nanoTime();
            report.put("cacheKeysBefore", cacheKeys());
            report.put("cacheKeysBeforeSeconds", seconds(System.nanoTime()-cacheKeysBeforeStarted));
            long streamsTraceBeforeStarted = System.nanoTime();
            report.put("streamsBuildTraceBefore", streamsBuildTrace());
            report.put("streamsBuildTraceBeforeSeconds", seconds(System.nanoTime()-streamsTraceBeforeStarted));
            // Cache-key reflection is deliberately outside the measured phase.
            started = System.nanoTime();
            processBefore = processCpuNanos();
            List<List<Integer>> alreadyFull = server.submit(() -> {
                List<List<Integer>> result = new ArrayList<>();
                for (ChunkPos p : chunks) if (level.getChunkSource().getChunkNow(p.x, p.z) != null) result.add(List.of(p.x,p.z));
                return result;
            }).get(180, TimeUnit.SECONDS);
            report.put("alreadyLoadedBefore", alreadyFull);
            if (!alreadyFull.isEmpty()) throw new IllegalStateException("fresh target violation: chunks already loaded " + alreadyFull);
            atomic(output.resolveSibling("progress.json"), Map.of("status","running","phaseIndex",phaseIndex,"phaseCount",phaseCount,"completed",0,"total",chunks.size(),"startedUtc",Instant.now().toString()));
            sampler.start();
            int distance = 33 - ChunkLevel.byStatus(ChunkStatus.FULL);
            for (int offset=0; offset<chunks.size(); offset += prefetch) {
                if ((System.nanoTime()-started) / 1_000_000_000L > timeoutSeconds) throw new IllegalStateException("global timeout");
                List<ChunkPos> batch = chunks.subList(offset, Math.min(chunks.size(), offset + prefetch));
                long batchStart = System.nanoTime(), ticketsStart = System.nanoTime();
                long ticketsDone = ticketsStart, submitted = ticketsStart;
                try {
                    server.submit(() -> batch.forEach(p -> level.getChunkSource().addRegionTicket(TICKET,p,distance,p))).get(180, TimeUnit.SECONDS);
                    ticketsDone = System.nanoTime();
                    // Deliberately off the server thread: calling here lets C2ME/native admission remain asynchronous.
                    var futures = batch.stream().map(p -> level.getChunkSource().getChunkFuture(p.x,p.z,ChunkStatus.FULL,true)).toList();
                    submitted = System.nanoTime();
                    for (int index=0; index<batch.size(); index++) {
                        ChunkPos p=batch.get(index); long waitStart=System.nanoTime();
                        ChunkAccess result=futures.get(index).get(Math.max(1, timeoutSeconds-(System.nanoTime()-started)/1_000_000_000L), TimeUnit.SECONDS).orElseThrow(() -> new IllegalStateException("FULL chunk unavailable: "+p));
                        long done=System.nanoTime();
                        Map<String,Object> sample = server.submit(() -> chunkSample(level, p, result)).get(30, TimeUnit.SECONDS);
                        sample.put("waitSeconds",seconds(done-waitStart));
                        sample.put("sinceStartSeconds",seconds(done-started));
                        sample.put("status","full");
                        rows.add(sample);
                    }
                } finally {
                    long cleanupStart=System.nanoTime();
                    server.submit(() -> batch.forEach(p -> level.getChunkSource().removeRegionTicket(TICKET,p,distance,p))).get(180, TimeUnit.SECONDS);
                    long cleanupDone=System.nanoTime();
                    batches.add(Map.of("first",List.of(batch.getFirst().x,batch.getFirst().z),"count",batch.size(),"ticketSeconds",seconds(ticketsDone-ticketsStart),"submissionSeconds",seconds(submitted-ticketsDone),"waitAndCleanupSeconds",seconds(cleanupDone-submitted),"totalSeconds",seconds(cleanupDone-batchStart)));
                }
                atomic(output.resolveSibling("progress.json"), Map.of("status","running","phaseIndex",phaseIndex,"phaseCount",phaseCount,"completed",rows.size(),"total",chunks.size(),"lastBatch",batches.getLast()));
            }
            report.put("terrainStructureFingerprint", terrainStructureFingerprint(rows));
            report.put("status","success");
        } catch (Throwable failure) {
            report.put("status","failed"); report.put("error",failure.toString()); LOG.error("Chunk profile failed",failure);
        } finally {
            long ended = System.nanoTime();
            long processAfter = processCpuNanos();
            sampler.stop();
            report.put("elapsedSeconds",started < 0 ? null : seconds(ended-started));
            report.put("processCpuSeconds", processBefore < 0 || processAfter < 0 ? null : seconds(Math.max(0,processAfter-processBefore)));
            report.put("tdAfter",tdMetrics()); report.put("onnxAfter",onnxMetrics());
            if (streamsGroundMarineAudit) report.put("streamsGroundMarineAuditAfter", streamsGroundMarineAudit());
            long cacheKeysAfterStarted = System.nanoTime();
            report.put("cacheKeysAfter",cacheKeys());
            report.put("cacheKeysAfterSeconds",seconds(System.nanoTime()-cacheKeysAfterStarted));
            long streamsTraceAfterStarted = System.nanoTime();
            report.put("streamsBuildTraceAfter", streamsBuildTrace());
            report.put("streamsBuildTraceAfterSeconds", seconds(System.nanoTime()-streamsTraceAfterStarted));
            report.put("cpuProfile",sampler.result());
            report.put("limitations", List.of("Thread CPU sampling has 200 ms resolution and can miss short/native work.","Aggregate queue and executor times overlap between requests; do not add them to chunk wall time.","This measures the installed cloned modpack and one fresh chunk region, not player rendering FPS."));
            return report;
        }
    }

    private static List<Region> parseRegions(String raw) { if(raw==null||raw.isBlank())return List.of(); List<Region> out=new ArrayList<>(); for(String pair:raw.split(";")){String[] p=pair.trim().split(",",-1);if(p.length!=2)throw new IllegalArgumentException("chunkprofile.regions must be X,Z;X,Z");try{out.add(new Region(Integer.parseInt(p[0].trim()),Integer.parseInt(p[1].trim())));}catch(NumberFormatException e){throw new IllegalArgumentException("invalid chunkprofile.regions",e);}} return List.copyOf(out); }
    private record Region(int x,int z) { }
    private List<ChunkPos> positions(int startX,int startZ) { List<ChunkPos> r=new ArrayList<>(); for(int x=0;x<side;x++) for(int z=0;z<side;z++) r.add(new ChunkPos(startX+x,startZ+z)); return r; }
    /** Executes on the server thread and reads only the loaded FULL chunk plus its already-available biome holder. */
    private static Map<String,Object> chunkSample(ServerLevel level, ChunkPos pos, ChunkAccess chunk) {
        int x = pos.getMiddleBlockX(), z = pos.getMiddleBlockZ();
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        String biome = level.getBiome(new net.minecraft.core.BlockPos(x, y, z)).unwrapKey().map(k -> k.location().toString()).orElse("unkeyed");
        List<String> structures = new ArrayList<>();
        chunk.getAllStarts().forEach((structure,start) -> {
            if (start.isValid()) level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).getResourceKey(structure).ifPresent(k -> structures.add(k.location().toString()));
        });
        structures.sort(String::compareTo);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("chunk",List.of(pos.x,pos.z)); result.put("center",List.of(x,y,z)); result.put("worldSurfaceY",y); result.put("biome",biome); result.put("structures",structures);
        return result;
    }
    private static double seconds(long nanos) { return nanos / 1_000_000_000.0; }
    /** Stable summary of the requested chunks' observed surface, biome and valid starts; it is not block equivalence. */
    private static String terrainStructureFingerprint(List<Map<String,Object>> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map<String,Object> row : rows) {
                digest.update((String.valueOf(row.get("chunk"))+"|"+row.get("worldSurfaceY")+"|"+row.get("biome")+"|"+row.get("structures")+"\n").getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder hex = new StringBuilder(); for (byte value : digest.digest()) hex.append(String.format("%02x", value)); return hex.toString();
        } catch (Exception failure) { return "unavailable:" + failure.getClass().getSimpleName(); }
    }
    private static long processCpuNanos() { try { return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os ? os.getProcessCpuTime() : -1L; } catch(Throwable ignored) { return -1; } }
    private void fail(String why) { Map<String,Object> r=new LinkedHashMap<>();r.put("status","failed");r.put("error",why);r.put("lastInferenceSnapshot",lastIdleSnapshot);if(readyNanos>0L)r.put("settleElapsedSeconds",seconds(System.nanoTime()-readyNanos));try{atomic(output,r);}catch(IOException ignored){}finished=true;LOG.error("Chunk profile failed: {}",why);Minecraft.getInstance().stop(); }

    private static Map<String,Object> tdMetrics() { return invokeRecord("com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider", "getInstance", "performanceSnapshot"); }
    /**
     * Read-only diagnostic snapshot of TD's outer tile caches.  The provider
     * publishes and evicts these maps under cacheLock, so taking that same lock
     * gives a stable key set without modifying cache recency.  Cache-hit LRU
     * clocks are AtomicLongs updated outside that lock; their observed ordering
     * is therefore advisory and key-set equality is the comparable signal.
     *
     * MemoryTileStore's access-order LinkedHashMaps have no externally shared
     * lock: production confines them to the inference lane.  This diagnostic
     * runs on its own worker and must not iterate them concurrently, so it
     * deliberately reports them unavailable rather than risking a racy read.
     */
    @SuppressWarnings("unchecked")
    private static Map<String,Object> cacheKeys() {
        if (!telemetryEnabled()) {
            return Map.of("available", false, "reason", "disabled; enable -Dtd.inference.requestTelemetry=true");
        }
        Map<String,Object> unavailableInternal = Map.of("available", false,
                "reason", "MemoryTileStore windowCaches are confined to the inference lane; no safe external lock");
        try {
            Class<?> providerType = Class.forName("com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider");
            Object provider = providerType.getMethod("getInstance").invoke(null);
            Field lockField = providerType.getDeclaredField("cacheLock");
            Field fullField = providerType.getDeclaredField("CACHE");
            Field groundField = providerType.getDeclaredField("GROUND_CACHE");
            Field generationField = providerType.getDeclaredField("cacheGeneration");
            lockField.setAccessible(true); fullField.setAccessible(true); groundField.setAccessible(true); generationField.setAccessible(true);
            Object lock = lockField.get(provider);
            synchronized (lock) {
                return Map.of("available", true,
                        "keySetSynchronization", "cacheLock",
                        "recencyAtomicSnapshot", false,
                        "ordering", "observed CacheEntry.lastAccessed ascending; ties use key text; concurrent hit updates can reorder",
                        "cacheGeneration", generationField.get(provider),
                        "full", orderedOuterKeys((Map<?,?>)fullField.get(provider)),
                        "ground", orderedOuterKeys((Map<?,?>)groundField.get(provider)),
                        "internalWindowCaches", unavailableInternal);
            }
        } catch (Throwable failure) {
            return Map.of("available", false, "error", failure.getClass().getSimpleName(),
                    "internalWindowCaches", unavailableInternal);
        }
    }
    private static List<Map<String,Object>> orderedOuterKeys(Map<?,?> cache) throws ReflectiveOperationException {
        List<Map<String,Object>> keys = new ArrayList<>();
        for (Map.Entry<?,?> entry : cache.entrySet()) {
            Field lastAccessed = entry.getValue().getClass().getDeclaredField("lastAccessed");
            lastAccessed.setAccessible(true);
            Object clock = lastAccessed.get(entry.getValue());
            if (!(clock instanceof AtomicLong value)) throw new ReflectiveOperationException("unexpected cache clock");
            keys.add(new LinkedHashMap<>(Map.of("key", String.valueOf(entry.getKey()), "lastAccess", value.get())));
        }
        keys.sort(Comparator.<Map<String,Object>>comparingLong(row -> ((Number)row.get("lastAccess")).longValue())
                .thenComparing(row -> String.valueOf(row.get("key"))));
        return keys;
    }
    /** The production trace is optional so a diagnostic JAR remains compatible with older TD JARs. */
    private static Map<String,Object> streamsBuildTrace() {
        if (!telemetryEnabled()) return Map.of("available", false, "reason", "disabled; enable -Dtd.inference.requestTelemetry=true");
        try {
            Class<?> trace = Class.forName("com.terrain.chunkprofile.StreamsBuildTrace");
            return Map.of("available", true, "snapshot", value(trace.getMethod("snapshot").invoke(null)));
        } catch (Throwable failure) {
            return Map.of("available", false, "error", failure.getClass().getSimpleName());
        }
    }
    /** Read the production opt-in counter only; it must never start sampling or reset its lifetime totals. */
    private static Map<String,Object> streamsGroundMarineAudit() {
        try {
            Class<?> sampling = Class.forName("com.github.xandergos.terraindiffusionmc.compat.streams.StreamsMarineSampling");
            Object raw = sampling.getMethod("auditSnapshot").invoke(null);
            if (!(raw instanceof Map<?,?>)) throw new IllegalStateException("auditSnapshot is not a Map");
            return Map.of("available", true, "snapshot", value(raw));
        } catch (Throwable failure) {
            return Map.of("available", false, "error", failure.getClass().getSimpleName());
        }
    }
    private static boolean streamsGroundMarineAuditPassed(Map<String,Object> audit) {
        if (!Boolean.TRUE.equals(audit.get("available"))) return false;
        Object snapshot = audit.get("snapshot");
        if (!(snapshot instanceof Map<?,?> values)) return false;
        Object samples = values.get("samples"), mismatches = values.get("mismatches");
        return samples instanceof Number && mismatches instanceof Number
                && ((Number)samples).longValue() > 0L && ((Number)mismatches).longValue() == 0L;
    }
    private static boolean telemetryEnabled() { return Boolean.getBoolean("td.inference.requestTelemetry"); }
    private static Map<String,Object> inferenceReadiness() {
        try { Class<?> type=Class.forName("com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider"); return (Map<String,Object>) value(type.getMethod("inferenceSnapshot").invoke(null)); }
        catch(Throwable failure) { return Map.of("active",-1L,"queued",-1L,"available",false,"error",failure.getClass().getSimpleName()); }
    }
    private static Map<String,Object> onnxMetrics() { return invokeStaticRecord("com.github.xandergos.terraindiffusionmc.pipeline.OnnxModel", "runtimeMetrics", "modelRunMetrics"); }
    private static Map<String,Object> invokeRecord(String type, String first, String second) { try { Class<?> c=Class.forName(type);Object instance=c.getMethod(first).invoke(null);return Map.of("available",true,"snapshot",value(c.getMethod(second).invoke(instance))); } catch(Throwable e){return Map.of("available",false,"error",e.getClass().getSimpleName());} }
    private static Map<String,Object> invokeStaticRecord(String type, String... methods) { Map<String,Object> out=new LinkedHashMap<>();try{Class<?>c=Class.forName(type);out.put("available",true);for(String m:methods)out.put(m,value(c.getMethod(m).invoke(null)));}catch(Throwable e){out.put("available",false);out.put("error",e.getClass().getSimpleName());}return out; }
    @SuppressWarnings("unchecked") private static Object value(Object input) { if(input==null||input instanceof String||input instanceof Number||input instanceof Boolean)return input; if(input instanceof Map<?,?> map){Map<String,Object>r=new TreeMap<>();map.forEach((k,v)->r.put(String.valueOf(k),value(v)));return r;} if(input instanceof Iterable<?> xs){List<Object>r=new ArrayList<>();xs.forEach(x->r.add(value(x)));return r;} Class<?>c=input.getClass();if(c.isRecord()){Map<String,Object>r=new LinkedHashMap<>();try{for(RecordComponent p:c.getRecordComponents())r.put(p.getName(),value(p.getAccessor().invoke(input)));return r;}catch(Throwable e){return Map.of("record",input.toString(),"serializationError",e.getClass().getSimpleName());}}return input.toString(); }
    private static void atomic(Path path,Object value) throws IOException { Path parent=path.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);Path temp=path.resolveSibling("."+path.getFileName()+".tmp");Files.writeString(temp,JSON.toJson(value),StandardCharsets.UTF_8);Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }

    /** CPU deltas retain all thread states; blocked/waiting stacks are counted separately as wall samples. */
    private static final class CpuSampler {
        private static final int MAX_UNIQUE = 4096;
        private final ThreadMXBean bean=ManagementFactory.getThreadMXBean(); private final AtomicBoolean live=new AtomicBoolean();
        private final Map<Long,Long> before=new TreeMap<>();
        private final Map<String,Long> threadCpu=new TreeMap<>();
        private final Map<String,Long> stacks=new TreeMap<>(), waitingSamples=new TreeMap<>();
        private long samples, observed, skippedCpuStacks, skippedWaitingStacks; private Thread thread;
        void start(){ if(!bean.isThreadCpuTimeSupported())return; if(!bean.isThreadCpuTimeEnabled())bean.setThreadCpuTimeEnabled(true);live.set(true);thread=new Thread(this::loop,"td-chunk-profile-cpu");thread.setDaemon(true);thread.start(); }
        void loop(){while(live.get()){try{Thread.sleep(200);}catch(InterruptedException e){break;}sample();}}
        synchronized void sample(){
            samples++;
            for(long id:bean.getAllThreadIds()){
                long now=bean.getThreadCpuTime(id); Long old=before.put(id,now);
                ThreadInfo info=bean.getThreadInfo(id,96); if(info==null)continue;
                String name=info.getThreadName(), stack=stack(name,info);
                if(now>=0&&old!=null&&now>old){
                    long d=now-old; observed+=d; threadCpu.merge(name,d,Long::sum);
                    if(stacks.containsKey(stack)||stacks.size()<MAX_UNIQUE) stacks.merge(stack,d,Long::sum); else skippedCpuStacks++;
                }
                if((info.getThreadState()==Thread.State.WAITING||info.getThreadState()==Thread.State.BLOCKED)
                        && relevant(name,stack)){
                    if(waitingSamples.containsKey(stack)||waitingSamples.size()<MAX_UNIQUE) waitingSamples.merge(stack,1L,Long::sum); else skippedWaitingStacks++;
                }
            }
        }
        private static boolean relevant(String name,String stack){ String lower=(name+" "+stack).toLowerCase(Locale.ROOT); return lower.contains("streams")||lower.contains("river")||lower.contains("c2me")||lower.contains("chunk")||lower.contains("inference")||lower.contains("onnx")||lower.contains("server"); }
        private static String stack(String name,ThreadInfo info){StringBuilder s=new StringBuilder(name).append(" [").append(info.getThreadState()).append("]");for(StackTraceElement e:info.getStackTrace())s.append(" <- ").append(e.getClassName()).append('.').append(e.getMethodName());return s.toString();}
        void stop(){live.set(false);if(thread!=null){thread.interrupt();try{thread.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
        synchronized Map<String,Object> result(){
            return Map.of("intervalMillis",200,"sampleIntervals",samples,"observedCpuSeconds",seconds(observed),
                    "topCpuStacks",top(stacks,"cpuSeconds",true),"topThreadCpu",top(threadCpu,"cpuSeconds",true),
                    "waitingOrBlockedRelevantSamples",top(waitingSamples,"sampleCount",false),
                    "uniqueCpuStacks",stacks.size(),"uniqueWaitingStacks",waitingSamples.size(),
                    "skippedCpuStacks",skippedCpuStacks,"skippedWaitingStacks",skippedWaitingStacks);
        }
        private static List<Map<String,Object>> top(Map<String,Long> source,String value,boolean seconds){List<Map.Entry<String,Long>> rows=new ArrayList<>(source.entrySet());rows.sort(Map.Entry.<String,Long>comparingByValue().reversed());List<Map<String,Object>>out=new ArrayList<>();for(int i=0;i<Math.min(100,rows.size());i++)out.add(Map.of("stack",rows.get(i).getKey(),value,seconds?ChunkProfileClient.seconds(rows.get(i).getValue()):rows.get(i).getValue()));return out;}
    }
}
