package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

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

    private static record CacheKey(int i1, int j1, int i2, int j2) {}
    private static record CacheEntry(HeightmapData data, AtomicLong lastAccessed) {}

    private static final int MAX_CACHE_SIZE = 64;
    private static final int MAX_CACHE_SIZE_HEADROOM = 8;
    private static final Map<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_CLOCK = new AtomicLong();
    private static final Map<CacheKey, Future<HeightmapData>> PENDING = new ConcurrentHashMap<>();
    /** Single thread for pipeline.get() so MemoryTileStore is not accessed concurrently. */
    private static final ExecutorService INFERENCE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "terrain-diffusion-inference");
        t.setDaemon(true);
        return t;
    });

    private static volatile LocalTerrainProvider INSTANCE;
    private static long instanceSeed;

    private final WorldPipeline pipeline;

    private static final Object INIT_LOCK = new Object();

    private LocalTerrainProvider(long seed, PipelineModels models) {
        this.pipeline = new WorldPipeline(seed, models);
    }

    /** Seed is 64-bit world seed. Creates provider once; later worlds only update seed and clear caches (lightweight). */
    public static synchronized void init(long seed) {
        PipelineModels.awaitLoad();
        PipelineModels models = PipelineModels.getInstance();
        if (models == null) throw new IllegalStateException("PipelineModels failed to load");
        if (INSTANCE == null) {
            INSTANCE = new LocalTerrainProvider(seed, models);
            instanceSeed = seed;
        } else if (instanceSeed != seed) {
            INSTANCE.pipeline.setSeed(seed);
            instanceSeed = seed;
            CACHE.clear();
            PENDING.clear();
        }
    }

    public static LocalTerrainProvider getInstance() {
        if (INSTANCE != null) return INSTANCE;

        synchronized(INIT_LOCK) {
            if (INSTANCE != null) return INSTANCE;
            PipelineModels.awaitLoad();
            PipelineModels models = PipelineModels.getInstance();
            if (models == null) throw new IllegalStateException("PipelineModels failed to load");
            INSTANCE = new LocalTerrainProvider(0L, models);
            instanceSeed = 0L;
        }

        return INSTANCE;
    }

    public static void clearCache() {
        CACHE.clear();
        PENDING.clear();
    }

    // =========================================================================
    // Explorer API — all pipeline calls routed through INFERENCE_EXECUTOR
    // =========================================================================

    /** Returns the current world seed used by the pipeline. */
    public static long getSeed() {
        return instanceSeed;
    }

    /**
     * Run elevation and climate inference on the inference thread.
     *
     * @return float[2]: [0] = elev (H*W), [1] = climate (5*H*W, or null)
     */
    public static float[][] getPipelineData(int i1, int j1, int i2, int j2, boolean withClimate) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.get(i1, j1, i2, j2, withClimate));
    }

    /**
     * Fetch a coarse tensor slice on the inference thread.
     * Coordinates are in coarse index units (1 unit = 256 native pixels).
     *
     * @return FloatTensor with shape [7, ci1-ci0, cj1-cj0]
     */
    public static FloatTensor getPipelineCoarse(int ci0, int cj0, int ci1, int cj1) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.getCoarseSlice(ci0, cj0, ci1, cj1));
    }

    /** Coarse-only ecological overview; bounds are game blocks and maxima are exclusive. */
    public static BroadEcologyClimate getBroadEcologyClimate(int minX, int minZ, int maxX, int maxZ, int scale)
            throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.getBroadEcologyClimate(minZ, minX, maxZ, maxX, scale));
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
        submitToInferenceThread(() -> {
            LocalTerrainProvider provider = getInstance();
            provider.pipeline.setSeed(newSeed);
            instanceSeed = newSeed;
            CACHE.clear();
            PENDING.clear();
            return null;
        });
    }

    /** Change to a random new seed; returns the new seed value. */
    public static long generateRandomSeedFromExplorer() throws Exception {
        long newSeed = new Random().nextLong();
        changeSeedFromExplorer(newSeed);
        return newSeed;
    }

    private static <T> T submitToInferenceThread(Callable<T> task) throws Exception {
        return INFERENCE_EXECUTOR.submit(task).get();
    }

    /**
     * Fetch heightmap for a block-coordinate region (i=Z, j=X).
     * Coordinates are in block space; scale from config determines blocks per native pixel.
     * Blocks the calling thread until the tile is ready (one tile can take 10–30+ seconds).
     * If the caller is the server or a chunk worker, the game will stall until this returns.
     */
    public HeightmapData fetchHeightmap(int i1, int j1, int i2, int j2) {
        CacheKey key = new CacheKey(i1, j1, i2, j2);
        CacheEntry cached = CACHE.get(key);
        if (cached != null) {
            cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
            return cached.data;
        }

        return this.genHeightmap(key, i1, j1, i2, j2);
    }

    private HeightmapData genHeightmap(CacheKey key, int i1, int j1, int i2, int j2) {
        int scale = WorldScaleManager.getCurrentScale();
        FutureTask<HeightmapData> task = new FutureTask<>(() -> {
            long computedWindowCountBefore = pipeline.getTotalComputedWindowCount();
            HeightmapData data = scale <= 1
                    ? handle1x(i1, j1, i2, j2)
                    : handleUpsampled(i1, j1, i2, j2, scale);
            long computedWindowCountAfter = pipeline.getTotalComputedWindowCount();

            long newlyComputedWindowCount = computedWindowCountAfter - computedWindowCountBefore;
            int regionWidth = j2 - j1;
            int regionHeight = i2 - i1;
            LOG.info(
                    "Terrain Diffusion ({}) finished generating region {}x{} ({} newly computed windows)",
                    OnnxModel.getResolvedInferenceProvider(), regionWidth, regionHeight, newlyComputedWindowCount);
            CACHE.put(key, new CacheEntry(data, new AtomicLong(CACHE_CLOCK.incrementAndGet())));
            evictLruTo(MAX_CACHE_SIZE);
            PENDING.remove(key);
            return data;
        });
        Future<HeightmapData> existing = PENDING.putIfAbsent(key, task);
        FutureTask<HeightmapData> toRun = (existing == null) ? task : (FutureTask<HeightmapData>) existing;
        if (existing == null) {
            int regionWidth = j2 - j1;
            int regionHeight = i2 - i1;
            LOG.info(
                    "Terrain Diffusion ({}) uncached region requested: ({}, {})-({}, {}) size {}x{}",
                    OnnxModel.getResolvedInferenceProvider(), j1, i1, j2, i2, regionWidth, regionHeight);
            INFERENCE_EXECUTOR.submit(toRun);
        }
        try {
            return toRun.get();
        } catch (Exception e) {
            PENDING.remove(key);
            throw new RuntimeException("Terrain tile failed: " + key, e);
        }
    }

    private static void evictLruTo(int maxSize) {
        int headroomHalf = MAX_CACHE_SIZE_HEADROOM / 2;
        if (CACHE.size() > maxSize + headroomHalf) {
            CACHE.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessed.get()))
                .limit(MAX_CACHE_SIZE_HEADROOM)
                .map(Map.Entry::getKey)
                .forEach(CACHE::remove);
        }
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
                elevFlat, elevPadded, terralithContext.coastal(), i1, j1, 1, NATIVE_RESOLUTION);
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
        float[][] elevNative2D = to2D(elevNativeFlat, nH, nW);
        float[][] elevUp = LaplacianUtils.bilinearResize(elevNative2D, nH * scale, nW * scale);

        // Crop offsets in the upsampled array
        int padUp   = 2 * scale;
        int offsetI = i1 - i1n * scale;
        int offsetJ = j1 - j1n * scale;
        int cropI1  = padUp + offsetI;
        int cropJ1  = padUp + offsetJ;

        float[] elevSmooth = cropFlat(elevUp, cropI1,     cropJ1,     H,   W,   nH * scale, nW * scale);
        float[] elevPadded = cropFlat(elevUp, cropI1 - 1, cropJ1 - 1, H+2, W+2, nH * scale, nW * scale);

        // Upsample climate (4, nH, nW) → (4, H, W)
        float[] climate = upsampleClimate(climateNativeFlat, nH, nW, cropI1, cropJ1, H, W, scale, nH * scale, nW * scale);

        float[] elevOut = addElevationNoise(elevSmooth, elevPadded, i1, j1, H, W, pixelSizeM);

        short[] terralithFlat = new short[H * W];
        BiomeClassifier.TerralithContext terralithContext = MacroBiomeContext.build(macro[0], macro[1],
                macroI0, macroJ0, macroH, macroW, scale, pixelSizeM, i1, j1, H, W);
        short[] biomeFlat = BiomeClassifier.classify(elevSmooth, climate, i1, j1, elevPadded, H, W, pixelSizeM, terralithFlat, terralithContext);
        return withEcology(buildHeightmapData(elevOut, biomeFlat, terralithFlat, H, W),
                elevSmooth, elevPadded, terralithContext.coastal(), i1, j1, scale, pixelSizeM);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private HeightmapData withEcology(HeightmapData data, float[] elevation, float[] paddedElevation,
                                      boolean[] coast, int i0, int j0, int scale, float metersPerBlock) {
        int h = data.height, w = data.width, n = h * w;
        BroadEcologyClimate climate = pipeline.getBroadEcologyClimate(i0, j0, i0 + h, j0 + w, scale);
        float[] gradients = sobelGradient(paddedElevation, h + 2, w + 2, h, w);
        short[] biomes = new short[n], terrain = new short[n];
        float[] cover = new float[n], temperature = new float[n], moisture = new float[n], slopes = new float[n];
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int index = r * w + c, x = j0 + c, z = i0 + r;
            var broad = climate.sample(x, z);
            var selection = EcologyClassifier.select(x, z, elevation[index], gradients[index] / metersPerBlock,
                    coast[index], broad.temperature(), broad.precipitation(), broad.elevation());
            var sample = selection.sample();
            biomes[index] = (short) sample.biomeId(); terrain[index] = selection.terrainBiomeId();
            cover[index] = sample.treeCover(); temperature[index] = sample.temperature();
            moisture[index] = sample.moisture(); slopes[index] = sample.slope();
        }
        return new HeightmapData(data.heightmap, data.biomeIds, data.terralithBiomeIds,
                new EcologyData(w, h, biomes, terrain, cover, temperature, moisture, slopes), w, h);
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
                                            int scale, int upH, int upW) {
        if (climNative == null) return null;
        float[] result = new float[4 * H * W];
        for (int ch = 0; ch < 4; ch++) {
            float[][] chNative = new float[nH][nW];
            for (int r = 0; r < nH; r++)
                System.arraycopy(climNative, ch * nH * nW + r * nW, chNative[r], 0, nW);
            float[][] chUp = LaplacianUtils.bilinearResize(chNative, upH, upW);
            for (int r = 0; r < H; r++)
                for (int c = 0; c < W; c++)
                    result[ch * H * W + r * W + c] = chUp[cropI1 + r][cropJ1 + c];
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

    private static float[][] to2D(float[] flat, int H, int W) {
        float[][] a = new float[H][W];
        for (int r = 0; r < H; r++) System.arraycopy(flat, r * W, a[r], 0, W);
        return a;
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
