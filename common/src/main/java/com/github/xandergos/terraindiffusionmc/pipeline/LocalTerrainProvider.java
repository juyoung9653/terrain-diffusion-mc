package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Provides terrain heightmap and biome data from the local WorldPipeline.
 *
 * <p>When scale=1 the pipeline is sampled at native model resolution directly.
 * When scale>1 the pipeline is sampled at native resolution and the result is
 * bilinearly upsampled, giving 1 block = nativeResolution/scale.
 */
public final class LocalTerrainProvider {
    private static final Logger LOG = LoggerFactory.getLogger(LocalTerrainProvider.class);

    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();

    private static final FastNoiseLite ELEV_NOISE_COARSE = makeFnl(99999, 1f/24f, 3, 2f, 0.5f);
    private static final FastNoiseLite ELEV_NOISE_FINE   = makeFnl(88888, 1f/6f,  2, 2f, 0.6f);

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    public static final class HeightmapData {
        public final short[][] heightmap;
        public final short[][] biomeIds;
        public final short[][] terralithBiomeIds;
        public final EcologyData ecology;
        public final int width;
        public final int height;

        public HeightmapData(short[][] heightmap, short[][] biomeIds, int width, int height) {
            this(heightmap, biomeIds, null, width, height);
        }

        public HeightmapData(short[][] heightmap, short[][] biomeIds, short[][] terralithBiomeIds, int width, int height) {
            this(heightmap, biomeIds, terralithBiomeIds, null, width, height);
        }

        public HeightmapData(short[][] heightmap, short[][] biomeIds, short[][] terralithBiomeIds,
                             EcologyData ecology, int width, int height) {
            this.heightmap = heightmap;
            this.biomeIds  = biomeIds;
            this.terralithBiomeIds = terralithBiomeIds;
            this.ecology = ecology;
            this.width     = width;
            this.height    = height;
        }
    }

    private static record CacheKey(int i1, int j1, int i2, int j2, int scale) {}
    private static final class CacheEntry {
        final HeightmapData data;
        final AtomicLong lastAccessed;
        volatile boolean valid = true;

        CacheEntry(HeightmapData data, AtomicLong lastAccessed) {
            this.data = data;
            this.lastAccessed = lastAccessed;
        }
    }

    // One weak entry per worker: idle threads retain neither old worlds nor evicted tiles.
    private static final ThreadLocal<RecentTile> RECENT_TILE = new ThreadLocal<>();
    private static final ThreadLocal<RecentTile> RECENT_GROUND_TILE = new ThreadLocal<>();
    private static final boolean RECENT_TILE_CACHE_ENABLED =
            Boolean.parseBoolean(System.getProperty("td.inference.recentTileCache", "true"));
    private static final class RecentTile {
        final WeakReference<LocalTerrainProvider> provider;
        final WeakReference<CacheEntry> entry;
        final CacheKey key;
        final long generation;
        int hits;

        RecentTile(LocalTerrainProvider provider, CacheEntry entry, CacheKey key, long generation) {
            this.provider = new WeakReference<>(provider);
            this.entry = new WeakReference<>(entry);
            this.key = key;
            this.generation = generation;
        }
    }

    private static final int MAX_CACHE_SIZE = 64;
    private static volatile int activeCacheSize = MAX_CACHE_SIZE;
    private static final int MAX_CACHE_SIZE_HEADROOM = 8;
    private static final boolean REQUEST_TELEMETRY_ENABLED =
            Boolean.getBoolean("td.inference.requestTelemetry");
    private static final int REQUEST_TELEMETRY_HISTORY_MAX = 4096;
    private static final int REQUEST_TELEMETRY_EXAMPLES_MAX = 16;
    private static final boolean COLD_ECOLOGY_CACHE_ENABLED =
            Boolean.parseBoolean(System.getProperty("td.inference.coldEcologyCache", "true"));
    private static final int COLD_ECOLOGY_HOT_TILES = Math.max(0,
            Integer.getInteger("td.inference.coldEcologyHotTiles", 16));
    private final Map<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private final AtomicLong CACHE_CLOCK = new AtomicLong();
    private final Map<CacheKey, FutureTask<HeightmapData>> PENDING = new LinkedHashMap<>();
    // Ground-only entries have no biome/ecology payload and never enter CACHE.
    private final Map<CacheKey, CacheEntry> GROUND_CACHE = new ConcurrentHashMap<>();
    private final Map<CacheKey, FutureTask<HeightmapData>> GROUND_PENDING = new LinkedHashMap<>();
    private final Object cacheLock = new Object();
    private final EcologyDataCache coldEcologyCache = new EcologyDataCache(COLD_ECOLOGY_HOT_TILES);

    private final Map<PartialKey, HeightmapData> partialCache = new LinkedHashMap<>(16, .75f, true);
    private final Map<PartialKey, FutureTask<HeightmapData>> partialPending = new LinkedHashMap<>();
    private record PartialKey(CacheKey bounds, boolean biomes, int referenceTileSize) { }
    // Native postprocessing is request-shape dependent. Preserve the original full-tile
    // bounds while sharing a small, byte-bounded set of reference planes across sparse reads.
    private final Map<CacheKey, float[][]> referenceCache = new LinkedHashMap<>(8, .75f, true);
    private static final long MAX_REFERENCE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_REFERENCE_ENTRIES = 8;
    private long referenceBytes;
    private final AtomicLong referenceHits = new AtomicLong(), referenceMisses = new AtomicLong(), referenceEvictions = new AtomicLong();
    private volatile long cacheGeneration;
    private final LongAdder hits = new LongAdder();
    private final AtomicLong misses = new AtomicLong(), joined = new AtomicLong(), evictions = new AtomicLong();
    private final AtomicLong computedWindows = new AtomicLong(), executionNanos = new AtomicLong();
    private final RequestTelemetryLedger requestTelemetry = REQUEST_TELEMETRY_ENABLED
            ? new RequestTelemetryLedger() : null;
    private static final ThreadLocal<InferenceExecutor.Priority> REQUEST_PRIORITY =
            ThreadLocal.withInitial(() -> InferenceExecutor.Priority.FOREGROUND);
    private static final ThreadLocal<RequestOrigin> REQUEST_ORIGIN = new ThreadLocal<>();
    public static final int SAMPLE_TILE_SIZE = 16;
    public static int sampleTileSize() { return legacySampling() ? TerrainDiffusionConfig.tileSize() : Math.min(SAMPLE_TILE_SIZE, TerrainDiffusionConfig.tileSize()); }

    public enum RequestOrigin { UNSCOPED, STREAMS_DEMAND, STREAMS_PREFETCH, STRUCTURE }
    public record WorkTotals(long count, long computedWindows, long executionNanos) { }
    public record OriginTotals(RequestOrigin origin, WorkTotals full, WorkTotals ground) { }
    /** Completion categories overlap; upgrades are reported separately. */
    public record RequestTelemetry(int schemaVersion, boolean enabled, WorkTotals fullCompleted, WorkTotals groundCompleted,
                                   WorkTotals groundToFullUpgrade, WorkTotals bothCompletedOverlap,
                                   long queuedGroundReusedFull, WorkTotals sameKindRepeat, java.util.List<OriginTotals> origins,
                                   int historyTrackedKeys, long historyDrops, boolean historyComplete,
                                   java.util.List<String> repeatExamples, java.util.List<String> overlapExamples) { }
    public record PerformanceSnapshot(long hits, long misses, long pendingJoins, long evictions,
                                      long computedWindows, long executionNanos, int fullTiles, int partialTiles,
                                      long referenceHits, long referenceMisses, long referenceEvictions, long referenceBytes, int referenceTiles,
                                      InferenceExecutor.Snapshot inference, int groundTiles,
                                      RequestTelemetry requestTelemetry,
                                      Map<String, Map<String, Long>> windowTelemetry) { }

    /** Hit totals are exact after callers drain; a live LongAdder snapshot may lag concurrent hits. */
    public PerformanceSnapshot performanceSnapshot() {
        Map<String, Map<String, Long>> windowTelemetry = requestTelemetry == null ? Map.of() : pipeline.windowTelemetry();
        synchronized (cacheLock) {
            return new PerformanceSnapshot(hits.sum(), misses.get(), joined.get(), evictions.get(),
                    computedWindows.get(), executionNanos.get(), CACHE.size(), partialCache.size(),
                    referenceHits.get(), referenceMisses.get(), referenceEvictions.get(), referenceBytes, referenceCache.size(), INFERENCE_EXECUTOR.snapshot(), GROUND_CACHE.size(),
                    requestTelemetry == null ? disabledRequestTelemetry() : requestTelemetry.snapshot(), windowTelemetry);
        }
    }

    /** Reset while callers are idle when an exact measurement boundary is required. */
    public void resetPerformanceMetrics() {
        hits.reset(); misses.set(0); joined.set(0); evictions.set(0); computedWindows.set(0); executionNanos.set(0);
        referenceHits.set(0); referenceMisses.set(0); referenceEvictions.set(0);
        if (requestTelemetry != null) synchronized (cacheLock) { requestTelemetry.resetCounters(); }
        INFERENCE_EXECUTOR.resetMetrics();
    }

    private static RequestTelemetry disabledRequestTelemetry() {
        WorkTotals none = new WorkTotals(0, 0, 0);
        return new RequestTelemetry(2, false, none, none, none, none, 0, none,
                java.util.List.of(), 0, 0, true, java.util.List.of(), java.util.List.of());
    }

    private InferenceExecutor.Priority requestPriority() {
        return previewOwner == null ? REQUEST_PRIORITY.get() : InferenceExecutor.Priority.BACKGROUND;
    }

    public static boolean legacySampling() { return Boolean.parseBoolean(System.getProperty("td.inference.legacySampling", "true")); }

    public <T> T withPriority(InferenceExecutor.Priority priority, Callable<T> action) throws Exception {
        InferenceExecutor.Priority previous = REQUEST_PRIORITY.get();
        REQUEST_PRIORITY.set(priority);
        try { return action.call(); } finally { REQUEST_PRIORITY.set(previous); }
    }

    /** Tags task admission only; cached density reads retain their normal fast path. */
    public <T> T withRequestOrigin(RequestOrigin origin, Callable<T> action) throws Exception {
        if (!REQUEST_TELEMETRY_ENABLED) return action.call();
        RequestOrigin previous = REQUEST_ORIGIN.get();
        REQUEST_ORIGIN.set(origin);
        try { return action.call(); }
        finally {
            if (previous == null) REQUEST_ORIGIN.remove();
            else REQUEST_ORIGIN.set(previous);
        }
    }

    private static RequestOrigin requestOrigin() {
        RequestOrigin origin = REQUEST_ORIGIN.get();
        return origin == null ? RequestOrigin.UNSCOPED : origin;
    }

    private enum WorkKind { FULL, GROUND }
    private static final class RequestAdmission {
        final CacheKey key; final long generation, telemetryEpoch; final WorkKind kind; final RequestOrigin origin;
        final boolean groundToFullUpgrade, sameKindRepeat;
        OverlapPair overlap;
        boolean published;
        long publishedWindows, publishedNanos;
        RequestAdmission(CacheKey key, long generation, long telemetryEpoch, WorkKind kind, RequestOrigin origin,
                         boolean groundToFullUpgrade, boolean sameKindRepeat) {
            this.key = key; this.generation = generation; this.telemetryEpoch = telemetryEpoch; this.kind = kind;
            this.origin = origin; this.groundToFullUpgrade = groundToFullUpgrade;
            this.sameKindRepeat = sameKindRepeat;
        }
    }
    private static final class OverlapPair {
        final RequestAdmission ground, full; final long telemetryEpoch;
        boolean groundPublished, fullPublished, disarmed, counted;
        long groundWindows, groundNanos, fullWindows, fullNanos;
        OverlapPair(RequestAdmission ground, RequestAdmission full, long telemetryEpoch) {
            this.ground = ground; this.full = full; this.telemetryEpoch = telemetryEpoch;
        }
    }

    /** Bounded, cache-lock-confined task history. It never runs on per-density cached reads. */
    private final class RequestTelemetryLedger {
        private final LinkedHashMap<CacheKey, History> history = new LinkedHashMap<>(16, .75f, true);
        private final java.util.EnumMap<RequestOrigin, MutableTotals> fullByOrigin = new java.util.EnumMap<>(RequestOrigin.class);
        private final java.util.EnumMap<RequestOrigin, MutableTotals> groundByOrigin = new java.util.EnumMap<>(RequestOrigin.class);
        private final MutableTotals fullCompleted = new MutableTotals(), groundCompleted = new MutableTotals();
        private final MutableTotals groundToFullUpgrade = new MutableTotals(), bothCompletedOverlap = new MutableTotals();
        private final MutableTotals sameKindRepeat = new MutableTotals();
        private final java.util.LinkedHashSet<String> repeatExamples = new java.util.LinkedHashSet<>();
        private final java.util.LinkedHashSet<String> overlapExamples = new java.util.LinkedHashSet<>();
        private long queuedGroundReusedFull, historyDrops, counterEpoch;

        RequestAdmission admit(CacheKey key, long generation, WorkKind kind, boolean groundToFull, boolean overlap) {
            History prior = history.get(key);
            boolean same = prior != null && prior.generation == generation && prior.completed(kind);
            History current = history(key, generation);
            RequestAdmission admission = new RequestAdmission(key, generation, counterEpoch, kind, requestOrigin(),
                    groundToFull, same);
            if (kind == WorkKind.GROUND) current.activeGround = admission;
            else if (overlap && current.activeGround != null && current.activeGround.telemetryEpoch == counterEpoch) {
                OverlapPair pair = new OverlapPair(current.activeGround, admission, counterEpoch);
                if (current.activeGround.published) {
                    pair.groundPublished = true;
                    pair.groundWindows = current.activeGround.publishedWindows;
                    pair.groundNanos = current.activeGround.publishedNanos;
                }
                current.activeGround.overlap = pair; admission.overlap = pair; current.activeGround = null;
            } else if (overlap) current.activeGround = null;
            return admission;
        }

        void queuedGroundReusedFull(RequestAdmission admission) {
            if (admission.telemetryEpoch == counterEpoch) queuedGroundReusedFull++;
        }

        void completed(RequestAdmission admission, long windows, long nanos) {
            History current = history(admission.key, admission.generation);
            current.complete(admission.kind);
            admission.published = true; admission.publishedWindows = windows; admission.publishedNanos = nanos;
            if (admission.telemetryEpoch != counterEpoch) return;
            MutableTotals total = admission.kind == WorkKind.FULL ? fullCompleted : groundCompleted;
            total.add(windows, nanos);
            (admission.kind == WorkKind.FULL ? fullByOrigin : groundByOrigin)
                    .computeIfAbsent(admission.origin, ignored -> new MutableTotals()).add(windows, nanos);
            if (admission.groundToFullUpgrade) groundToFullUpgrade.add(windows, nanos);
            if (admission.sameKindRepeat) { sameKindRepeat.add(windows, nanos); example(repeatExamples, admission); }
            OverlapPair pair = admission.overlap;
            if (pair != null && !pair.disarmed && pair.telemetryEpoch == counterEpoch) {
                if (admission == pair.full) {
                    pair.fullPublished = true; pair.fullWindows = windows; pair.fullNanos = nanos;
                } else {
                    pair.groundPublished = true; pair.groundWindows = windows; pair.groundNanos = nanos;
                }
                if (!pair.counted && pair.fullPublished && pair.groundPublished) {
                    pair.counted = true;
                    bothCompletedOverlap.add(pair.fullWindows + pair.groundWindows, pair.fullNanos + pair.groundNanos);
                    example(overlapExamples, admission);
                }
            }
        }

        void abandon(RequestAdmission admission) {
            History current = history.get(admission.key);
            if (current != null && current.generation == admission.generation && current.activeGround == admission)
                current.activeGround = null;
            if (admission.overlap != null) admission.overlap.disarmed = true;
        }

        void clearGeneration() { history.clear(); }
        void resetCounters() {
            fullCompleted.clear(); groundCompleted.clear(); groundToFullUpgrade.clear(); bothCompletedOverlap.clear();
            sameKindRepeat.clear(); queuedGroundReusedFull = 0;
            fullByOrigin.clear(); groundByOrigin.clear();
            repeatExamples.clear(); overlapExamples.clear();
            counterEpoch++;
        }
        RequestTelemetry snapshot() {
            java.util.ArrayList<OriginTotals> origins = new java.util.ArrayList<>();
            for (RequestOrigin origin : RequestOrigin.values()) origins.add(new OriginTotals(origin,
                    totals(fullByOrigin.get(origin)), totals(groundByOrigin.get(origin))));
            return new RequestTelemetry(2, true, totals(fullCompleted), totals(groundCompleted), totals(groundToFullUpgrade),
                    totals(bothCompletedOverlap), queuedGroundReusedFull, totals(sameKindRepeat),
                    java.util.List.copyOf(origins), history.size(), historyDrops, historyDrops == 0,
                    java.util.List.copyOf(repeatExamples), java.util.List.copyOf(overlapExamples));
        }
        private History history(CacheKey key, long generation) {
            History value = history.get(key);
            if (value != null && value.generation == generation) return value;
            if (value != null) history.remove(key);
            if (history.size() >= REQUEST_TELEMETRY_HISTORY_MAX) { history.remove(history.keySet().iterator().next()); historyDrops++; }
            value = new History(generation); history.put(key, value); return value;
        }
        private void example(java.util.LinkedHashSet<String> examples, RequestAdmission admission) {
            if (examples.size() < REQUEST_TELEMETRY_EXAMPLES_MAX) examples.add(admission.kind + "@" + admission.key);
        }
        private WorkTotals totals(MutableTotals totals) { return totals == null ? new WorkTotals(0, 0, 0) : totals.snapshot(); }
    }
    private static final class History {
        final long generation; boolean fullCompleted, groundCompleted;
        RequestAdmission activeGround;
        History(long generation) { this.generation = generation; }
        boolean completed(WorkKind kind) { return kind == WorkKind.FULL ? fullCompleted : groundCompleted; }
        void complete(WorkKind kind) { if (kind == WorkKind.FULL) fullCompleted = true; else groundCompleted = true; }
    }
    private static final class MutableTotals {
        long count, windows, nanos;
        void add(long windows, long nanos) { count++; this.windows += windows; this.nanos += nanos; }
        void clear() { count = windows = nanos = 0; }
        WorkTotals snapshot() { return new WorkTotals(count, windows, nanos); }
    }

    /** Larger native river plates revisit a bounded working set across sampler threads. */
    public static void setLargeRiverPlanningCache(boolean enabled) {
        activeCacheSize = enabled ? 512 : MAX_CACHE_SIZE;
        LOG.info("Terrain heightmap cache target: {} tiles", activeCacheSize);
        LocalTerrainProvider current = INSTANCE;
        if (current != null) current.evictLruTo(activeCacheSize);
    }

    private static boolean useColdEcologyCache() {
        return COLD_ECOLOGY_CACHE_ENABLED && activeCacheSize > MAX_CACHE_SIZE;
    }

    private void retainEcology(HeightmapData data) {
        if (useColdEcologyCache() && data.ecology != null) data.ecology.retainCold(coldEcologyCache);
    }
    /** Single thread for pipeline.get() so MemoryTileStore is not accessed concurrently. */
    private static final InferenceExecutor INFERENCE_EXECUTOR =
            new InferenceExecutor("terrain-diffusion-inference");
    private static final Object INIT_LOCK = new Object();
    private static InferenceExecutor.Scope gameplayScope = INFERENCE_EXECUTOR.openScope();
    private static volatile LocalTerrainProvider INSTANCE;
    private final InferenceExecutor.Scope scope;
    private volatile long seed;
    private final WorldPipeline pipeline;
    private volatile boolean fastGroundSampling;
    private PreviewSession previewOwner;
    private static final ThreadLocal<PreviewSession> PREVIEW = new ThreadLocal<>();
    private static final ThreadLocal<LocalTerrainProvider> BOUND_PROVIDER = new ThreadLocal<>();
    private static final ThreadLocal<GroundCursor> GROUND_CURSOR = new ThreadLocal<>();

    private LocalTerrainProvider(long seed, PipelineModels models, InferenceExecutor.Scope scope) {
        this.seed = seed;
        this.scope = scope;
        this.pipeline = new WorldPipeline(seed, models);
        this.fastGroundSampling = WorldScaleManager.isFastGroundSampling();
    }

    /** Every real server receives an isolated provider/cache lifetime, including same-seed reopen. */
    public static void beginGameplay() {
        InferenceExecutor.Scope old;
        synchronized (INIT_LOCK) {
            old = gameplayScope;
            old.close();
            gameplayScope = INFERENCE_EXECUTOR.openScope();
            INSTANCE = null;
        }
        old.awaitDrained();
    }

    /** Saving may still need terrain; call only after the server has saved/stopped. */
    public static void shutdownGameplay() {
        InferenceExecutor.Scope old;
        LocalTerrainProvider stoppedProvider;
        synchronized (INIT_LOCK) {
            stoppedProvider = INSTANCE;
            old = gameplayScope;
            old.close();
            INSTANCE = null;
        }
        LOG.info("Draining gameplay terrain inference: {}", INFERENCE_EXECUTOR.snapshot());
        old.awaitDrained();
        LOG.info("Gameplay terrain inference drained: {}", INFERENCE_EXECUTOR.snapshot());
        if (stoppedProvider != null) LOG.info("Terrain request metrics: {}", stoppedProvider.performanceSnapshot());
    }

    /** Client teardown, before the JVM starts ONNX Runtime's independent environment shutdown hook. */
    public static void shutdownAll() {
        LOG.info("Draining all terrain inference: {}", INFERENCE_EXECUTOR.snapshot());
        INFERENCE_EXECUTOR.shutdownAndDrain();
        PipelineModels.stopLoadingAndAwait();
        LOG.info("All terrain inference drained: {}", INFERENCE_EXECUTOR.snapshot());
    }

    public static InferenceExecutor.Snapshot inferenceSnapshot() { return INFERENCE_EXECUTOR.snapshot(); }

    /** Mutations of a pipeline's seed and tile store share the native inference lane. */
    public static void init(long seed) {
        LocalTerrainProvider provider = getInstance();
        try {
            provider.scope.submit(() -> {
                provider.pipeline.setSeed(seed);
                provider.seed = seed;
                provider.fastGroundSampling = WorldScaleManager.isFastGroundSampling();
                provider.clearLocalCache();
                return null;
            }).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted initializing terrain seed", interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new IllegalStateException("Could not initialize terrain seed", failed.getCause());
        }
    }

    public static LocalTerrainProvider peekInstance() {
        if (PREVIEW.get() != null) return null;
        LocalTerrainProvider bound = BOUND_PROVIDER.get();
        return bound != null ? bound : INSTANCE;
    }

    public boolean isFastGroundSampling() {
        return fastGroundSampling;
    }

    public static LocalTerrainProvider getInstance() {
        PreviewSession preview = PREVIEW.get();
        if (preview != null) return preview.provider();
        LocalTerrainProvider bound = BOUND_PROVIDER.get();
        if (bound != null) { bound.scope.requireOpen(); return bound; }
        LocalTerrainProvider current = INSTANCE;
        if (current != null) { current.scope.requireOpen(); return current; }
        synchronized (INIT_LOCK) {
            gameplayScope.requireOpen();
            if (INSTANCE == null) {
                PipelineModels.awaitLoad();
                PipelineModels models = PipelineModels.getInstance();
                if (models == null) throw new IllegalStateException("PipelineModels failed to load");
                INSTANCE = new LocalTerrainProvider(0L, models, gameplayScope);
            }
            return INSTANCE;
        }
    }

    /** Bind a long-lived external producer to the world in which it was created. */
    public <T> T withBinding(Callable<T> action) throws Exception {
        scope.requireOpen();
        LocalTerrainProvider previous = BOUND_PROVIDER.get();
        BOUND_PROVIDER.set(this);
        try { return action.call(); }
        finally {
            if (previous == null) BOUND_PROVIDER.remove();
            else BOUND_PROVIDER.set(previous);
        }
    }

    /** A native sampler may scan hundreds of Y values in one exact ground column. */
    public <T> T withSamplingBinding(Callable<T> action) throws Exception {
        return withBinding(() -> {
            GroundCursor previous = GROUND_CURSOR.get();
            GROUND_CURSOR.set(new GroundCursor(this));
            try { return action.call(); }
            finally {
                if (previous == null) GROUND_CURSOR.remove();
                else GROUND_CURSOR.set(previous);
            }
        });
    }

    /** Exact pipeline meters, or MIN_VALUE when no complete-tile sampler scope applies. */
    public static int scopedGroundHeight(int x, int z) {
        GroundCursor cursor = GROUND_CURSOR.get();
        if (cursor == null || PREVIEW.get() != null || BOUND_PROVIDER.get() != cursor.provider
                || !legacySampling()) return Integer.MIN_VALUE;
        return cursor.height(x, z);
    }

    private static final class GroundCursor {
        final LocalTerrainProvider provider;
        WeakReference<CacheEntry> entry = new WeakReference<>(null);
        long generation;
        int startX, startZ, tileSize, scale;

        GroundCursor(LocalTerrainProvider provider) { this.provider = provider; }

        int height(int x, int z) {
            provider.scope.requireOpen();
            int currentScale = WorldScaleManager.getCurrentScale();
            int currentTileSize = TerrainDiffusionConfig.tileSize();
            long currentGeneration = provider.cacheGeneration;
            CacheEntry ready = entry.get();
            if (ready != null && ready.valid && generation == currentGeneration
                    && scale == currentScale && tileSize == currentTileSize
                    && x >= startX && x < startX + tileSize && z >= startZ && z < startZ + tileSize) {
                return ready.data.heightmap[z - startZ][x - startX];
            }
            int sx = Math.floorDiv(x, currentTileSize) * currentTileSize;
            int sz = Math.floorDiv(z, currentTileSize) * currentTileSize;
            HeightmapData data = provider.fetchGroundHeightmap(sz, sx, sz + currentTileSize, sx + currentTileSize);
            CacheEntry fetched = provider.CACHE.get(new CacheKey(sz, sx, sz + currentTileSize,
                    sx + currentTileSize, currentScale));
            if (fetched == null) fetched = provider.GROUND_CACHE.get(new CacheKey(sz, sx, sz + currentTileSize,
                    sx + currentTileSize, currentScale));
            // Never attach a pre-clear result to a post-clear generation, nor retain evicted tiles.
            if (fetched != null && fetched.valid && fetched.data == data
                    && provider.cacheGeneration == currentGeneration) {
                entry = new WeakReference<>(fetched);
                generation = currentGeneration;
                scale = currentScale;
                tileSize = currentTileSize;
                startX = sx;
                startZ = sz;
            } else {
                entry.clear();
            }
            return data.heightmap[z - sz][x - sx];
        }
    }

    public static void clearCache() {
        LocalTerrainProvider provider = INSTANCE;
        if (provider != null) provider.clearLocalCache();
    }

    private void clearLocalCache() {
        synchronized (cacheLock) {
            CACHE.values().forEach(entry -> entry.valid = false);
            cacheGeneration++;
            if (requestTelemetry != null) requestTelemetry.clearGeneration();
            CACHE.clear();
            GROUND_CACHE.values().forEach(entry -> entry.valid = false);
            GROUND_CACHE.clear();
            partialCache.clear();
            coldEcologyCache.clear();
            referenceCache.clear();
            referenceBytes = 0;
            partialPending.values().forEach(task -> task.cancel(false));
            partialPending.clear();
            PENDING.values().forEach(task -> task.cancel(false));
            PENDING.clear();
            GROUND_PENDING.values().forEach(task -> task.cancel(false));
            GROUND_PENDING.clear();
        }
    }

    // =========================================================================
    // Explorer API — all pipeline calls routed through INFERENCE_EXECUTOR
    // =========================================================================

    /** Returns the current world seed used by the pipeline. */
    public static long getSeed() {
        PreviewSession preview = PREVIEW.get();
        if (preview != null) return preview.seed;
        LocalTerrainProvider bound = BOUND_PROVIDER.get();
        LocalTerrainProvider provider = bound != null ? bound : INSTANCE;
        return provider == null ? 0L : provider.seed;
    }

    /** Thread-scoped preview scale; never changes the loaded world's saved/runtime scale. */
    public static Integer previewScale() {
        PreviewSession preview = PREVIEW.get();
        return preview == null ? null : preview.scale;
    }

    public static PreviewSession createPreviewSession(long seed, int scale) {
        return new PreviewSession(seed, WorldScaleManager.clampScale(scale));
    }

    /**
     * Independent seeded pipeline and bounded tile cache. Shared ONNX models still execute on
     * the single inference thread. Closing invalidates queued work without interrupting ONNX.
     */
    public static final class PreviewSession implements AutoCloseable {
        private final long seed;
        private final int scale;
        private final InferenceExecutor.Scope scope = INFERENCE_EXECUTOR.openScope();
        private final Map<CacheKey, HeightmapData> cache = new LinkedHashMap<>(16, .75f, true);
        private final Map<CacheKey, FutureTask<HeightmapData>> pending = new LinkedHashMap<>();
        private LocalTerrainProvider provider;
        private boolean closed;

        private PreviewSession(long seed, int scale) { this.seed = seed; this.scale = scale; }
        public long seed() { return seed; }
        public int scale() { return scale; }

        public void run(Runnable action) {
            synchronized (this) { requireOpen(); }
            PreviewSession previous = PREVIEW.get();
            PREVIEW.set(this);
            try { action.run(); }
            finally {
                if (previous == null) PREVIEW.remove();
                else PREVIEW.set(previous);
            }
        }

        private LocalTerrainProvider provider() {
            synchronized (this) {
                requireOpen();
                if (provider != null) return provider;
            }
            PipelineModels.awaitLoad();
            PipelineModels models = PipelineModels.getInstance();
            if (models == null) throw new IllegalStateException("PipelineModels failed to load");
            synchronized (this) {
                requireOpen();
                if (provider == null) {
                    provider = new LocalTerrainProvider(seed, models, scope);
                    provider.previewOwner = this;
                    provider.fastGroundSampling = true;
                }
                return provider;
            }
        }

        private void requireOpen() {
            if (closed) throw new CancellationException("Terrain preview session closed");
            scope.requireOpen();
        }

        private HeightmapData fetch(CacheKey key) {
            FutureTask<HeightmapData> future;
            synchronized (this) {
                requireOpen();
                HeightmapData ready = cache.get(key);
                if (ready != null) { provider.hits.increment(); return ready; }
                future = pending.get(key);
                if (future == null) {
                    LocalTerrainProvider local = provider;
                    local.misses.incrementAndGet();
                    future = scope.submit(InferenceExecutor.Priority.BACKGROUND, () -> {
                        synchronized (this) { requireOpen(); }
                        try {
                            long started = System.nanoTime(), before = local.pipeline.getTotalComputedWindowCount();
                            HeightmapData result = scale == 1
                                    ? local.handle1x(key.i1, key.j1, key.i2, key.j2)
                                    : local.handleUpsampled(key.i1, key.j1, key.i2, key.j2, scale);
                            local.computedWindows.addAndGet(local.pipeline.getTotalComputedWindowCount() - before);
                            local.executionNanos.addAndGet(System.nanoTime() - started);
                            synchronized (this) {
                                requireOpen();
                                cache.put(key, result);
                                while (cache.size() > 16) { cache.remove(cache.keySet().iterator().next()); local.evictions.incrementAndGet(); }
                            }
                            return result;
                        } finally {
                            synchronized (this) { pending.remove(key); }
                        }
                    });
                    pending.put(key, future);
                } else { provider.joined.incrementAndGet(); }
            }
            try { return future.get(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Terrain preview caller interrupted");
            } catch (java.util.concurrent.ExecutionException failed) {
                if (failed.getCause() instanceof CancellationException cancelled) throw cancelled;
                throw new IllegalStateException("Terrain preview tile failed: " + key, failed.getCause());
            }
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            scope.close();
            pending.values().forEach(future -> future.cancel(false));
            pending.clear();
            cache.clear();
            // An active ONNX call can finish using its local provider reference. No stale
            // result may enter the cache, and shared models belong to PipelineModels.
            provider = null;
        }
    }

    /**
     * Run elevation and climate inference on the inference thread.
     *
     * @return float[2]: [0] = elev (H*W), [1] = climate (5*H*W, or null)
     */
    public static float[][] getPipelineData(int i1, int j1, int i2, int j2, boolean withClimate) throws Exception {
        LocalTerrainProvider provider = getInstance();
        return provider.submitToInferenceThread(() -> provider.pipeline.get(i1, j1, i2, j2, withClimate));
    }

    /**
     * Fetch a coarse tensor slice on the inference thread.
     * Coordinates are in coarse index units (1 unit = 256 native pixels).
     *
     * @return FloatTensor with shape [7, ci1-ci0, cj1-cj0]
     */
    public static FloatTensor getPipelineCoarse(int ci0, int cj0, int ci1, int cj1) throws Exception {
        LocalTerrainProvider provider = getInstance();
        return provider.submitToInferenceThread(() -> provider.pipeline.getCoarseSlice(ci0, cj0, ci1, cj1));
    }

    /** Coarse-only ecological overview; bounds are game blocks and maxima are exclusive. */
    public static BroadEcologyClimate getBroadEcologyClimate(int minX, int minZ, int maxX, int maxZ, int scale)
            throws Exception {
        LocalTerrainProvider provider = getInstance();
        return provider.submitToInferenceThread(() -> provider.pipeline.getBroadEcologyClimate(minZ, minX, maxZ, maxX, scale));
    }

    /** Coarse-only hint batch. Exact terrain is still fetched for every accepted search result. */
    public static CoarseBiomeHints getCoarseBiomeHints(int minX, int minZ, int maxX, int maxZ, int scale)
            throws Exception {
        int nativePerCell = 32 * WorldPipelineModelConfig.latentCompression();
        int blocksPerCell = nativePerCell * scale;
        int cx0 = Math.floorDiv(minX, blocksPerCell), cz0 = Math.floorDiv(minZ, blocksPerCell);
        int cx1 = Math.floorDiv(maxX, blocksPerCell) + 1, cz1 = Math.floorDiv(maxZ, blocksPerCell) + 1;
        FloatTensor data = getPipelineCoarse(cz0 - 1, cx0 - 1, cz1 + 1, cx1 + 1);
        return new CoarseBiomeHints(data, cx0, cz0, cx1 - cx0, cz1 - cz0,
                blocksPerCell, nativePerCell * NATIVE_RESOLUTION);
    }

    /**
     * Change the world seed used by the pipeline and clear all caches.
     * Note: this also affects terrain generation for new Minecraft chunks.
     */
    public static void changeSeedFromExplorer(long newSeed) throws Exception {
        LocalTerrainProvider provider = getInstance();
        provider.submitToInferenceThread(() -> {
            provider.pipeline.setSeed(newSeed);
            provider.seed = newSeed;
            provider.clearLocalCache();
            return null;
        });
    }

    /** Change to a random new seed; returns the new seed value. */
    public static long generateRandomSeedFromExplorer() throws Exception {
        long newSeed = new Random().nextLong();
        changeSeedFromExplorer(newSeed);
        return newSeed;
    }

    private <T> T submitToInferenceThread(Callable<T> task) throws Exception {
        return scope.submit(task).get();
    }

    /**
     * Fetch heightmap for a block-coordinate region (i=Z, j=X).
     * Coordinates are in block space; scale from config determines blocks per native pixel.
     * Blocks the calling thread until the tile is ready (one tile can take 10–30+ seconds).
     * If the caller is the server or a chunk worker, the game will stall until this returns.
     */
    /** Read-only weather/decoration lookup. Never starts inference or updates cache retention. */
    public HeightmapData peekHeightmap(int i1, int j1, int i2, int j2) {
        synchronized (cacheLock) {
            CacheEntry cached = CACHE.get(new CacheKey(i1, j1, i2, j2, WorldScaleManager.getCurrentScale()));
            return cached != null && cached.valid ? cached.data : null;
        }
    }

    public HeightmapData fetchHeightmap(int i1, int j1, int i2, int j2) {
        scope.requireOpen();
        int scale = WorldScaleManager.getCurrentScale();
        if (previewOwner != null) return previewOwner.fetch(new CacheKey(i1, j1, i2, j2, scale));
        // Capture before lookup: a clear during a fetch must never stamp old data with a new generation.
        long generation = cacheGeneration;
        if (RECENT_TILE_CACHE_ENABLED) {
            RecentTile recent = RECENT_TILE.get();
            if (recent != null && recent.provider.get() == this && recent.generation == generation
                    && recent.key.i1 == i1 && recent.key.j1 == j1
                    && recent.key.i2 == i2 && recent.key.j2 == j2 && recent.key.scale == scale) {
                CacheEntry entry = recent.entry.get();
                if (entry != null && entry.valid) {
                    hits.increment();
                    // Approximate LRU, refreshed every 64 consecutive hits instead of two shared writes per hit.
                    if ((++recent.hits & 63) == 0) entry.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
                    return entry.data;
                }
            }
        }
        CacheKey key = new CacheKey(i1, j1, i2, j2, scale);
        CacheEntry cached = CACHE.get(key);
        if (cached != null && cached.valid) {
            hits.increment();
            cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
            if (RECENT_TILE_CACHE_ENABLED) RECENT_TILE.set(new RecentTile(this, cached, key, generation));
            return cached.data;
        }

        HeightmapData result = this.genHeightmap(key, i1, j1, i2, j2);
        if (RECENT_TILE_CACHE_ENABLED) {
            CacheEntry ready = CACHE.get(key);
            if (ready != null && ready.valid && ready.data == result && cacheGeneration == generation)
                RECENT_TILE.set(new RecentTile(this, ready, key, generation));
        }
        return result;
    }

    private HeightmapData genHeightmap(CacheKey key, int i1, int j1, int i2, int j2) {
        int scale = key.scale;
        FutureTask<HeightmapData> task;
        synchronized (cacheLock) {
            scope.requireOpen();
            CacheEntry ready = CACHE.get(key); // Completion may have raced the optimistic lookup.
            if (ready != null) {
                hits.increment();
                ready.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
                return ready.data;
            }
            task = PENDING.get(key);
            if (task == null) {
                misses.incrementAndGet();
                long generation = cacheGeneration;
                CacheEntry priorGround = GROUND_CACHE.get(key);
                final RequestAdmission telemetryAdmission = requestTelemetry == null ? null : requestTelemetry.admit(key, generation,
                        WorkKind.FULL, priorGround != null && priorGround.valid, GROUND_PENDING.containsKey(key));
                task = scope.submit(requestPriority(), () -> {
                    boolean published = false;
                    try {
                        long started = System.nanoTime();
                        long before = pipeline.getTotalComputedWindowCount();
                        HeightmapData data = scale <= 1
                                ? handle1x(i1, j1, i2, j2)
                                : handleUpsampled(i1, j1, i2, j2, scale);
                        long windows = pipeline.getTotalComputedWindowCount() - before;
                        long elapsed = System.nanoTime() - started;
                        computedWindows.addAndGet(windows);
                        executionNanos.addAndGet(elapsed);
                        LOG.debug("Terrain Diffusion ({}) finished generating region {}x{} ({} newly computed windows)",
                                OnnxModel.getResolvedInferenceProvider(), j2 - j1, i2 - i1,
                                pipeline.getTotalComputedWindowCount() - before);
                        synchronized (cacheLock) {
                            scope.requireOpen();
                            if (generation != cacheGeneration) throw new CancellationException("Terrain cache generation changed");
                            retainEcology(data);
                            CACHE.put(key, new CacheEntry(data, new AtomicLong(CACHE_CLOCK.incrementAndGet())));
                            CacheEntry ground = GROUND_CACHE.remove(key);
                            if (ground != null) ground.valid = false;
                            evictLruTo(activeCacheSize);
                            if (telemetryAdmission != null) requestTelemetry.completed(telemetryAdmission, windows, elapsed);
                            published = true;
                        }
                        return data;
                    } finally {
                        synchronized (cacheLock) {
                            if (!published && telemetryAdmission != null) requestTelemetry.abandon(telemetryAdmission);
                            if (generation == cacheGeneration) PENDING.remove(key);
                        }
                    }
                });
                PENDING.put(key, task);
                LOG.debug("Terrain Diffusion ({}) uncached region requested: ({}, {})-({}, {}) size {}x{}",
                        OnnxModel.getResolvedInferenceProvider(), j1, i1, j2, i2, j2 - j1, i2 - i1);
            } else {
                joined.incrementAndGet();
                INFERENCE_EXECUTOR.promote(task, requestPriority());
            }
        }
        try {
            return task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Terrain tile caller interrupted");
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof CancellationException cancelled) throw cancelled;
            throw new IllegalStateException("Terrain tile failed: " + key, failed.getCause());
        }
    }

    private void evictLruTo(int maxSize) {
        synchronized (cacheLock) {
            int total = CACHE.size() + GROUND_CACHE.size();
            if (total <= maxSize + MAX_CACHE_SIZE_HEADROOM / 2) return;
            // Both capabilities share the budget. Evicting only ground entries when
            // full tiles occupy the budget immediately discards every new ground
            // tile, making density-column scans recompute it for every sample.
            // Snapshot recency before sorting: cache hits may update it outside this lock.
            record Candidate(CacheKey key, CacheEntry entry, boolean full, long accessed) { }
            java.util.stream.Stream.concat(
                    CACHE.entrySet().stream().map(e -> new Candidate(e.getKey(), e.getValue(),
                            true, e.getValue().lastAccessed.get())),
                    GROUND_CACHE.entrySet().stream().map(e -> new Candidate(e.getKey(), e.getValue(),
                            false, e.getValue().lastAccessed.get())))
                    .sorted(Comparator.comparingLong(Candidate::accessed))
                    .limit(Math.max(MAX_CACHE_SIZE_HEADROOM, total - maxSize))
                    .forEach(candidate -> {
                        var cache = candidate.full() ? CACHE : GROUND_CACHE;
                        if (cache.remove(candidate.key(), candidate.entry())) {
                            candidate.entry().valid = false;
                            if (candidate.full()) coldEcologyCache.forget(candidate.entry().data.ecology);
                            evictions.incrementAndGet();
                        }
                    });
        }
    }

    /** Exact ground only; partial entries never masquerade as complete biome/ecology tiles. */
    public HeightmapData fetchGroundHeightmap(int i1, int j1, int i2, int j2) {
        if (!fastGroundSampling)
            return legacySampling() ? fetchCompleteRegion(i1, j1, i2, j2) : fetchPartial(i1, j1, i2, j2, false);
        return fetchFastGround(i1, j1, i2, j2);
    }

    private HeightmapData fetchFastGround(int i1, int j1, int i2, int j2) {
        scope.requireOpen();
        int scale = WorldScaleManager.getCurrentScale(), tile = TerrainDiffusionConfig.tileSize();
        int ri = Math.floorDiv(i1, tile) * tile, rj = Math.floorDiv(j1, tile) * tile;
        if (i2 <= i1 || j2 <= j1 || i2 > ri + tile || j2 > rj + tile)
            throw new IllegalArgumentException("Ground read must fit one terrain tile");
        boolean full = i1 == ri && j1 == rj && i2 == ri + tile && j2 == rj + tile;
        long observedGeneration = cacheGeneration;
        if (RECENT_TILE_CACHE_ENABLED) {
            RecentTile recent = RECENT_GROUND_TILE.get();
            if (recent != null && recent.provider.get() == this
                    && recent.generation == observedGeneration
                    && recent.key.i1 == ri && recent.key.j1 == rj
                    && recent.key.i2 == ri + tile && recent.key.j2 == rj + tile && recent.key.scale == scale) {
                CacheEntry entry = recent.entry.get();
                if (entry != null && entry.valid) {
                    hits.increment();
                    if ((++recent.hits & 63) == 0) entry.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
                    return full ? entry.data : cropData(entry.data, i1 - ri, j1 - rj, i2 - i1, j2 - j1);
                }
            }
        }
        CacheKey key = new CacheKey(ri, rj, ri + tile, rj + tile, scale);
        FutureTask<HeightmapData> task;
        synchronized (cacheLock) {
            scope.requireOpen();
            CacheEntry complete = CACHE.get(key);
            if (complete != null && complete.valid) {
                hits.increment(); complete.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
                rememberGround(key, complete, observedGeneration);
                return full ? complete.data : cropData(complete.data, i1-ri, j1-rj, i2-i1, j2-j1);
            }
            task = PENDING.get(key);
            if (task == null) {
                CacheEntry ground = GROUND_CACHE.get(key);
                if (ground != null && ground.valid) {
                    hits.increment(); ground.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
                    rememberGround(key, ground, observedGeneration);
                    return full ? ground.data : cropData(ground.data, i1-ri, j1-rj, i2-i1, j2-j1);
                }
                task = GROUND_PENDING.get(key);
                if (task == null) {
                    misses.incrementAndGet(); long generation = cacheGeneration;
                    final RequestAdmission telemetryAdmission = requestTelemetry == null ? null
                            : requestTelemetry.admit(key, generation, WorkKind.GROUND, false, false);
                    task = scope.submit(requestPriority(), () -> {
                        boolean published = false;
                        try {
                            // A foreground complete request may overtake queued ground work.
                            // Reuse its result before doing any inference or publishing a duplicate.
                            synchronized (cacheLock) {
                                scope.requireOpen();
                                if (generation != cacheGeneration) throw new CancellationException("Terrain cache generation changed");
                                CacheEntry ready = CACHE.get(key);
                                if (ready != null && ready.valid) {
                                    if (telemetryAdmission != null) {
                                        requestTelemetry.queuedGroundReusedFull(telemetryAdmission);
                                        requestTelemetry.abandon(telemetryAdmission);
                                    }
                                    return ready.data;
                                }
                            }
                            long started = System.nanoTime(), before = pipeline.getTotalComputedWindowCount();
                            HeightmapData data = handleGround(ri, rj, ri + tile, rj + tile, scale);
                            long windows = pipeline.getTotalComputedWindowCount() - before;
                            long elapsed = System.nanoTime() - started;
                            computedWindows.addAndGet(windows);
                            executionNanos.addAndGet(elapsed);
                            synchronized (cacheLock) {
                                scope.requireOpen();
                                if (generation != cacheGeneration) throw new CancellationException("Terrain cache generation changed");
                                GROUND_CACHE.put(key, new CacheEntry(data, new AtomicLong(CACHE_CLOCK.incrementAndGet())));
                                evictLruTo(activeCacheSize);
                                if (telemetryAdmission != null) requestTelemetry.completed(telemetryAdmission, windows, elapsed);
                                published = true;
                            }
                            return data;
                        } finally {
                            synchronized (cacheLock) {
                                if (!published && telemetryAdmission != null) requestTelemetry.abandon(telemetryAdmission);
                                if (generation == cacheGeneration) GROUND_PENDING.remove(key);
                            }
                        }
                    });
                    GROUND_PENDING.put(key, task);
                } else { joined.incrementAndGet(); INFERENCE_EXECUTOR.promote(task, requestPriority()); }
            } else {
                joined.incrementAndGet();
                INFERENCE_EXECUTOR.promote(task, requestPriority());
            }
        }
        try {
            HeightmapData data = task.get();
            CacheEntry entry = CACHE.get(key);
            if (entry == null || entry.data != data) entry = GROUND_CACHE.get(key);
            if (entry != null && entry.data == data) rememberGround(key, entry, observedGeneration);
            return full ? data : cropData(data, i1 - ri, j1 - rj, i2 - i1, j2 - j1);
        }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException("Ground tile caller interrupted"); }
        catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof CancellationException cancelled) throw cancelled;
            throw new IllegalStateException("Ground tile failed: " + key, failed.getCause());
        }
    }

    private void rememberGround(CacheKey key, CacheEntry entry, long generation) {
        if (RECENT_TILE_CACHE_ENABLED && entry.valid && generation == cacheGeneration)
            RECENT_GROUND_TILE.set(new RecentTile(this, entry, key, generation));
    }

    private HeightmapData handleGround(int i1, int j1, int i2, int j2, int scale) {
        int h = i2 - i1, w = j2 - j1;
        if (scale == 1) {
            int halo = MacroBiomeContext.REGION_BLOCKS + 64 + MacroBiomeContext.COAST_BLOCKS + 2;
            float[] elevation = pipeline.getElevationCrop(i1-halo, j1-halo, i2+halo, j2+halo, i1, j1, i2, j2);
            return new HeightmapData(toHeights(elevation, h, w), null, w, h);
        }
        int i1n = Math.floorDiv(i1, scale), j1n = Math.floorDiv(j1, scale);
        int i2n = -Math.floorDiv(-i2, scale), j2n = -Math.floorDiv(-j2, scale);
        int i1p = i1n - 2, j1p = j1n - 2, i2p = i2n + 2, j2p = j2n + 2;
        int nH = i2p - i1p, nW = j2p - j1p;
        int halo = (int) Math.ceil(194f / scale) + 2;
        float[] nativeElevation = pipeline.getElevationCrop(i1n-halo, j1n-halo, i2n+halo, j2n+halo, i1p, j1p, i2p, j2p);
        int cropI = 2 * scale + (i1 - i1n * scale), cropJ = 2 * scale + (j1 - j1n * scale);
        float[] padded = ExactTerrainSampling.resizeCrop(nativeElevation, 0, 0, nH, nW, 0, 0, nH, nW,
                scale, cropI - 1, cropJ - 1, h + 2, w + 2);
        float[] smooth = cropFlat(padded, 1, 1, h, w, h + 2, w + 2);
        float[] ground = addElevationNoise(smooth, padded, i1, j1, h, w, NATIVE_RESOLUTION / scale);
        return new HeightmapData(toHeights(ground, h, w), null, w, h);
    }

    /** Original classifiers, including caves' ground and natural ecology, over a small region. */
    public HeightmapData fetchBiomeHeightmap(int i1, int j1, int i2, int j2) {
        return legacySampling() ? fetchCompleteRegion(i1, j1, i2, j2) : fetchPartial(i1, j1, i2, j2, true);
    }

    // Dense Streams sampling needs complete classified tiles across interleaved regions.
    // Rebuilding large reference planes for tiny output crops can dominate startup.
    private HeightmapData fetchCompleteRegion(int i1, int j1, int i2, int j2) {
        int tile = TerrainDiffusionConfig.tileSize();
        int ri = Math.floorDiv(i1, tile) * tile, rj = Math.floorDiv(j1, tile) * tile;
        if (i2 <= i1 || j2 <= j1 || i2 > ri + tile || j2 > rj + tile)
            throw new IllegalArgumentException("Terrain read crosses reference tile");
        HeightmapData complete = fetchHeightmap(ri, rj, ri + tile, rj + tile);
        if (i1 == ri && j1 == rj && i2 == ri + tile && j2 == rj + tile) return complete;
        return cropData(complete, i1 - ri, j1 - rj, i2 - i1, j2 - j1);
    }

    private HeightmapData fetchPartial(int i1, int j1, int i2, int j2, boolean biomes) {
        scope.requireOpen();
        int scale = WorldScaleManager.getCurrentScale(), tile = TerrainDiffusionConfig.tileSize();
        int ri = Math.floorDiv(i1, tile) * tile, rj = Math.floorDiv(j1, tile) * tile;
        if (i2 <= i1 || j2 <= j1 || i2 - i1 > SAMPLE_TILE_SIZE || j2 - j1 > SAMPLE_TILE_SIZE)
            throw new IllegalArgumentException("Partial read must fit the bounded sample tile");
        if (i2 > ri + tile || j2 > rj + tile) throw new IllegalArgumentException("Partial read crosses reference tile");
        CacheKey full = new CacheKey(ri, rj, ri + tile, rj + tile, scale);
        if (legacySampling())
            return cropData(fetchHeightmap(ri, rj, ri + tile, rj + tile), i1 - ri, j1 - rj, i2 - i1, j2 - j1);
        PartialKey key = new PartialKey(new CacheKey(i1, j1, i2, j2, scale), biomes, tile);
        FutureTask<HeightmapData> task;
        synchronized (cacheLock) {
            scope.requireOpen();
            HeightmapData ready = partialCache.get(key);
            if (ready == null && !biomes) ready = partialCache.get(new PartialKey(key.bounds, true, tile));
            if (ready != null) { hits.increment(); return ready; }
            CacheEntry complete = CACHE.get(full);
            if (complete != null) {
                hits.increment(); complete.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
                HeightmapData cropped = cropData(complete.data, i1 - ri, j1 - rj, i2 - i1, j2 - j1);
                cachePartial(key, cropped);
                return cropped;
            }
            task = partialPending.get(key);
            if (task == null && !biomes) task = partialPending.get(new PartialKey(key.bounds, true, tile));
            if (task == null) {
                misses.incrementAndGet();
                long generation = cacheGeneration;
                task = scope.submit(requestPriority(), () -> {
                    long start = System.nanoTime(), before = pipeline.getTotalComputedWindowCount();
                    try {
                        HeightmapData data = handlePartial(key, ri, rj, generation);
                        computedWindows.addAndGet(pipeline.getTotalComputedWindowCount() - before);
                        executionNanos.addAndGet(System.nanoTime() - start);
                        synchronized (cacheLock) {
                            scope.requireOpen();
                            if (generation != cacheGeneration) throw new CancellationException("Terrain cache generation changed");
                            cachePartial(key, data);
                        }
                        return data;
                    } finally {
                        synchronized (cacheLock) { if (generation == cacheGeneration) partialPending.remove(key); }
                    }
                });
                partialPending.put(key, task);
            } else {
                joined.incrementAndGet(); INFERENCE_EXECUTOR.promote(task, requestPriority());
            }
        }
        try { return task.get(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new CancellationException("Terrain sample interrupted");
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof CancellationException cancelled) throw cancelled;
            throw new IllegalStateException("Exact terrain sample failed: " + key, failed.getCause());
        }
    }

    private void cachePartial(PartialKey key, HeightmapData data) {
        retainEcology(data);
        partialCache.put(key, data);
        while (partialCache.size() > 512) {
            HeightmapData evicted = partialCache.remove(partialCache.keySet().iterator().next());
            coldEcologyCache.forget(evicted.ecology); evictions.incrementAndGet();
        }
    }

    /** Called on the single inference lane; publication also observes external cache invalidation. */
    private float[][] referenceData(CacheKey bounds, boolean climate, long generation) {
        synchronized (cacheLock) {
            scope.requireOpen();
            if (generation != cacheGeneration) throw new CancellationException("Terrain cache generation changed");
            float[][] ready = referenceCache.get(bounds);
            if (ready != null && (!climate || ready[1] != null)) {
                referenceHits.incrementAndGet();
                return ready;
            }
            referenceMisses.incrementAndGet();
        }
        float[][] result = pipeline.get(bounds.i1, bounds.j1, bounds.i2, bounds.j2, climate);
        synchronized (cacheLock) {
            scope.requireOpen();
            if (generation != cacheGeneration) throw new CancellationException("Terrain cache generation changed");
            long bytes = referenceSize(result);
            if (bytes <= MAX_REFERENCE_BYTES) {
                float[][] previous = referenceCache.remove(bounds);
                if (previous != null) referenceBytes -= referenceSize(previous);
                while (!referenceCache.isEmpty() && (referenceCache.size() >= MAX_REFERENCE_ENTRIES
                        || referenceBytes + bytes > MAX_REFERENCE_BYTES)) {
                    CacheKey oldest = referenceCache.keySet().iterator().next();
                    referenceBytes -= referenceSize(referenceCache.remove(oldest));
                    referenceEvictions.incrementAndGet();
                }
                referenceCache.put(bounds, result);
                referenceBytes += bytes;
            }
        }
        return result;
    }

    private static long referenceSize(float[][] planes) {
        long bytes = 0;
        for (float[] plane : planes) if (plane != null) bytes += (long) plane.length * Float.BYTES;
        return bytes;
    }

    private HeightmapData handlePartial(PartialKey key, int referenceI, int referenceJ, long generation) {
        CacheKey b = key.bounds;
        int h = b.i2 - b.i1, w = b.j2 - b.j1, scale = b.scale;
        int ni = Math.floorDiv(referenceI, scale), nj = Math.floorDiv(referenceJ, scale);
        int neI = -Math.floorDiv(-(referenceI + key.referenceTileSize), scale);
        int neJ = -Math.floorDiv(-(referenceJ + key.referenceTileSize), scale);
        int halo = scale == 1 ? MacroBiomeContext.REGION_BLOCKS + 64 + MacroBiomeContext.COAST_BLOCKS + 2
                : (int) Math.ceil(194f / scale) + 2;
        int si = ni - halo, sj = nj - halo, sh = neI - ni + 2 * halo, sw = neJ - nj + 2 * halo;
        float[][] nativeData = referenceData(new CacheKey(si, sj, si + sh, sj + sw, scale), key.biomes, generation);
        float[] elevation, padded, climate = null;
        if (scale == 1) {
            elevation = cropFlat(nativeData[0], b.i1 - si, b.j1 - sj, h, w, sh, sw);
            padded = cropFlat(nativeData[0], b.i1 - si - 1, b.j1 - sj - 1, h + 2, w + 2, sh, sw);
            if (key.biomes) climate = cropClimate(nativeData[1], sh, sw, b.i1 - si, b.j1 - sj, h, w);
        } else {
            int refI = Math.floorDiv(referenceI, scale) - 2, refJ = Math.floorDiv(referenceJ, scale) - 2;
            int refH = -Math.floorDiv(-(referenceI + key.referenceTileSize), scale) + 2 - refI;
            int refW = -Math.floorDiv(-(referenceJ + key.referenceTileSize), scale) + 2 - refJ;
            padded = ExactTerrainSampling.resizeCrop(nativeData[0], si, sj, sh, sw, refI, refJ, refH, refW,
                    scale, b.i1 - 1, b.j1 - 1, h + 2, w + 2);
            elevation = cropFlat(padded, 1, 1, h, w, h + 2, w + 2);
            if (key.biomes) {
                climate = new float[4 * h * w];
                for (int channel = 0; channel < 4; channel++) {
                    float[] values = java.util.Arrays.copyOfRange(nativeData[1], channel * sh * sw, (channel + 1) * sh * sw);
                    float[] resized = ExactTerrainSampling.resizeCrop(values, si, sj, sh, sw, refI, refJ, refH, refW,
                            scale, b.i1, b.j1, h, w);
                    System.arraycopy(resized, 0, climate, channel * h * w, h * w);
                }
            }
        }
        float meters = NATIVE_RESOLUTION / scale;
        float[] ground = scale == 1 ? elevation : addElevationNoise(elevation, padded, b.i1, b.j1, h, w, meters);
        if (!key.biomes) return new HeightmapData(toHeights(ground, h, w), null, w, h);
        BiomeClassifier.TerralithContext context = MacroBiomeContext.build(nativeData[0], nativeData[1], si, sj, sh, sw,
                scale, meters, b.i1, b.j1, h, w);
        short[] terralith = new short[h * w];
        short[] biomes = BiomeClassifier.classify(elevation, climate, b.i1, b.j1, padded, h, w, meters, terralith, context);
        return withEcology(buildHeightmapData(ground, biomes, terralith, h, w), elevation, padded, context.shore(),
                b.i1, b.j1, scale, meters);
    }

    private static short[][] toHeights(float[] values, int h, int w) {
        short[][] result = new short[h][w];
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++)
            result[r][c] = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(values[r * w + c])));
        return result;
    }

    private static HeightmapData cropData(HeightmapData source, int r, int c, int h, int w) {
        short[][] ground = cropShort(source.heightmap, r, c, h, w), biomes = cropShort(source.biomeIds, r, c, h, w);
        short[][] terralith = cropShort(source.terralithBiomeIds, r, c, h, w);
        EcologyData ecology = source.ecology == null ? null : source.ecology.crop(c, r, w, h);
        return new HeightmapData(ground, biomes, terralith, ecology, w, h);
    }

    private static short[][] cropShort(short[][] source, int r, int c, int h, int w) {
        if (source == null) return null;
        short[][] result = new short[h][w];
        for (int row = 0; row < h; row++) System.arraycopy(source[row + r], c, result[row], 0, w);
        return result;
    }

    // =========================================================================
    // Scale == 1: block coords == native pixel coords
    // =========================================================================

    private HeightmapData handle1x(int i1, int j1, int i2, int j2) {
        int H = i2 - i1, W = j2 - j1;
        int halo = MacroBiomeContext.REGION_BLOCKS + 2 * 32 + MacroBiomeContext.COAST_BLOCKS + 2;
        int hi0 = i1 - halo, hj0 = j1 - halo, hi2 = i2 + halo, hj2 = j2 + halo;
        float[][] macro = pipeline.get(hi0, hj0, hi2, hj2, true);
        int macroH = hi2 - hi0, macroW = hj2 - hj0;
        float[] elevFlat = cropFlat(macro[0], i1 - hi0, j1 - hj0, H, W, macroH, macroW);
        float[] elevPadded = cropFlat(macro[0], i1 - hi0 - 1, j1 - hj0 - 1, H + 2, W + 2, macroH, macroW);
        float[] climate = cropClimate(macro[1], macroH, macroW, i1 - hi0, j1 - hj0, H, W);
        BiomeClassifier.TerralithContext terralithContext = MacroBiomeContext.build(macro[0], macro[1],
                hi0, hj0, macroH, macroW, 1, NATIVE_RESOLUTION, i1, j1, H, W);

        short[] terralithFlat = new short[H * W];
        short[] biomeFlat = BiomeClassifier.classify(elevFlat, climate, i1, j1, elevPadded, H, W, NATIVE_RESOLUTION, terralithFlat, terralithContext);
        return withEcology(buildHeightmapData(elevFlat, biomeFlat, terralithFlat, H, W),
                elevFlat, elevPadded, terralithContext.shore(), i1, j1, 1, NATIVE_RESOLUTION);
    }

    // =========================================================================
    // Scale > 1: pipeline at native res → bilinear upsample to block res
    // =========================================================================

    private HeightmapData handleUpsampled(int i1, int j1, int i2, int j2, int scale) {
        int H = i2 - i1, W = j2 - j1;
        float pixelSizeM = NATIVE_RESOLUTION / scale;

        // Convert block coords to native pixel coords
        int i1n = Math.floorDiv(i1, scale);
        int j1n = Math.floorDiv(j1, scale);
        int i2n = -Math.floorDiv(-i2, scale);
        int j2n = -Math.floorDiv(-j2, scale);

        // The expanded read supplies both the normal bilinear padding and the
        // globally addressed Terralith context halo in one pipeline request.
        int i1p = i1n - 2, j1p = j1n - 2;
        int i2p = i2n + 2, j2p = j2n + 2;
        int nH = i2p - i1p, nW = j2p - j1p;
        int haloNative = (int) Math.ceil((192f + 2f) / scale) + 2;
        int macroI0 = i1n - haloNative, macroJ0 = j1n - haloNative;
        int macroI2 = i2n + haloNative, macroJ2 = j2n + haloNative;
        int macroH = macroI2 - macroI0, macroW = macroJ2 - macroJ0;
        float[][] macro = pipeline.get(macroI0, macroJ0, macroI2, macroJ2, true);
        float[] elevNativeFlat = cropFlat(macro[0], i1p - macroI0, j1p - macroJ0, nH, nW, macroH, macroW);
        float[] climateNativeFlat = cropClimate(macro[1], macroH, macroW, i1p - macroI0, j1p - macroJ0, nH, nW);

        // Bilinear upsample elevation: (nH, nW) → (nH*scale, nW*scale)
        // Crop offsets in the upsampled array
        int padUp   = 2 * scale;
        int offsetI = i1 - i1n * scale;
        int offsetJ = j1 - j1n * scale;
        int cropI1  = padUp + offsetI;
        int cropJ1  = padUp + offsetJ;

        float[] elevPadded = ExactTerrainSampling.resizeCrop(elevNativeFlat, 0, 0, nH, nW,
                0, 0, nH, nW, scale, cropI1 - 1, cropJ1 - 1, H + 2, W + 2);
        float[] elevSmooth = cropFlat(elevPadded, 1, 1, H, W, H + 2, W + 2);

        // Upsample climate (4, nH, nW) → (4, H, W)
        float[] climate = upsampleClimate(climateNativeFlat, nH, nW, cropI1, cropJ1, H, W, scale);

        float[] elevOut = addElevationNoise(elevSmooth, elevPadded, i1, j1, H, W, pixelSizeM);

        short[] terralithFlat = new short[H * W];
        BiomeClassifier.TerralithContext terralithContext = MacroBiomeContext.build(macro[0], macro[1],
                macroI0, macroJ0, macroH, macroW, scale, pixelSizeM, i1, j1, H, W);
        short[] biomeFlat = BiomeClassifier.classify(elevSmooth, climate, i1, j1, elevPadded, H, W, pixelSizeM, terralithFlat, terralithContext);
        return withEcology(buildHeightmapData(elevOut, biomeFlat, terralithFlat, H, W),
                elevSmooth, elevPadded, terralithContext.shore(), i1, j1, scale, pixelSizeM);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private HeightmapData withEcology(HeightmapData data, float[] elevation, float[] paddedElevation,
                                      ShoreContext shore, int i0, int j0, int scale, float metersPerBlock) {
        int h = data.height, w = data.width, n = h * w;
        BroadEcologyClimate climate = pipeline.getBroadEcologyClimate(i0, j0, i0 + h, j0 + w, scale);
        float[] gradients = sobelGradient(paddedElevation, h + 2, w + 2, h, w);
        float[] snowRetention = EcologyClassifier.snowRetention(paddedElevation, h, w, metersPerBlock);
        short[] biomes = new short[n], terrain = new short[n];
        float[] cover = new float[n], temperature = new float[n], moisture = new float[n], slopes = new float[n];
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int index = r * w + c, x = j0 + c, z = i0 + r;
            var broad = climate.sample(x, z);
            var selection = EcologyClassifier.select(x, z, elevation[index], gradients[index] / metersPerBlock,
                    broad.temperature(), broad.precipitation(), broad.elevation(),
                    shore.waterDistanceBlocks(index), shore.openWaterFraction(index), shore.reliefSlope(index));
            var sample = selection.sample();
            biomes[index] = (short) sample.biomeId(); terrain[index] = selection.terrainBiomeId();
            cover[index] = sample.treeCover(); temperature[index] = sample.temperature();
            moisture[index] = sample.moisture(); slopes[index] = sample.slope();
        }
        return new HeightmapData(data.heightmap, data.biomeIds, data.terralithBiomeIds,
                new EcologyData(w, h, biomes, terrain, cover, temperature, moisture, slopes, snowRetention), w, h);
    }

    private float[] addElevationNoise(float[] elevSmooth, float[] elevPadded,
                                       int i1, int j1, int H, int W, float pixelSizeM) {
        float[] slopeGradient = sobelGradient(elevPadded, H + 2, W + 2, H, W);
        float[] elevOut = elevSmooth.clone();
        float normFactor = 40f * pixelSizeM / NATIVE_RESOLUTION;
        float ampC = 100f * pixelSizeM / NATIVE_RESOLUTION;
        float ampF = 70f  * pixelSizeM / NATIVE_RESOLUTION;

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevSmooth[idx];
                if (e < 0f) continue;

                float grad = slopeGradient[idx];
                float sf = Math.min(1f, grad / normFactor);
                sf = sf * sf * (float) Math.sqrt(sf);

                float nx = j1 + c, ny = i1 + r;
                elevOut[idx] = e
                        + ELEV_NOISE_COARSE.GetNoise(nx, ny) * ampC * sf
                        + ELEV_NOISE_FINE.GetNoise(nx, ny)   * ampF * sf;
            }
        }
        return elevOut;
    }

    private static float[] sobelGradient(float[] padded, int pH, int pW, int H, int W) {
        final float[] SOBEL_X = {-1,0,1, -2,0,2, -1,0,1};
        final float[] SOBEL_Y = {-1,-2,-1, 0,0,0, 1,2,1};
        float[] result = new float[H * W];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int k = 0; k < 9; k++) {
                    float v = padded[(r + k/3) * pW + (c + k%3)];
                    dx += v * SOBEL_X[k];
                    dy += v * SOBEL_Y[k];
                }
                dx /= 8f; dy /= 8f;
                result[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy);
            }
        }
        return result;
    }

    private static float[] upsampleClimate(float[] climNative, int nH, int nW,
                                            int cropI1, int cropJ1, int H, int W,
                                            int scale) {
        if (climNative == null) return null;
        float[] result = new float[4 * H * W];
        for (int ch = 0; ch < 4; ch++) {
            float[] cropped = ExactTerrainSampling.resizeCrop(climNative, ch * nH * nW,
                    0, 0, nH, nW,
                    0, 0, nH, nW, scale, cropI1, cropJ1, H, W);
            System.arraycopy(cropped, 0, result, ch * H * W, H * W);
        }
        return result;
    }

    private static float[] cropFlat(float[][] src, int r0, int c0, int H, int W, int srcH, int srcW) {
        float[] out = new float[H * W];
        for (int r = 0; r < H; r++) {
            int sr = Math.max(0, Math.min(srcH - 1, r0 + r));
            for (int c = 0; c < W; c++)
                out[r * W + c] = src[sr][Math.max(0, Math.min(srcW - 1, c0 + c))];
        }
        return out;
    }

    private static float[] cropFlat(float[] src, int r0, int c0, int h, int w, int srcH, int srcW) {
        float[] result = new float[h * w];
        for (int r = 0; r < h; r++) System.arraycopy(src, (r0 + r) * srcW + c0, result, r * w, w);
        return result;
    }

    private static float[] cropClimate(float[] source, int sourceH, int sourceW, int r0, int c0, int h, int w) {
        if (source == null) return null;
        float[] result = new float[4 * h * w];
        for (int channel = 0; channel < 4; channel++)
            for (int r = 0; r < h; r++)
                System.arraycopy(source, channel * sourceH * sourceW + (r0 + r) * sourceW + c0,
                        result, channel * h * w + r * w, w);
        return result;
    }

    private static HeightmapData buildHeightmapData(float[] elevFlat, short[] biomeFlat, short[] terralithFlat, int H, int W) {
        short[][] heightmap = new short[H][W];
        short[][] biomeIds  = new short[H][W];
        short[][] terralithBiomeIds = new short[H][W];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                float e = elevFlat[r * W + c];
                heightmap[r][c] = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                biomeIds[r][c]  = biomeFlat[r * W + c];
                terralithBiomeIds[r][c] = terralithFlat[r * W + c];
            }
        return new HeightmapData(heightmap, biomeIds, terralithBiomeIds, W, H);
    }
}
