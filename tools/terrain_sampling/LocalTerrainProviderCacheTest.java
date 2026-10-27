package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Fixture harness: production provider/classifiers with deterministic non-native pipeline inputs. */
public final class LocalTerrainProviderCacheTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("benchmark")) {
            try { benchmarkCachedLookup(); } finally { LocalTerrainProvider.shutdownAll(); }
            return;
        }
        System.setProperty("td.inference.legacySampling", "false");
        var provider = LocalTerrainProvider.getInstance();
        try {
            cacheOnlyWeatherRead(provider);
            rechecksCompletedAfterOptimisticMiss(provider);
            concurrentMissesJoin(provider);
            invalidationDoesNotPublishOrRemoveReplacement(provider);
            boundedPartialCache(provider);
            referenceCacheReuseAndBound(provider);
            exactPartialParity(provider);
            completeTileDefaultReusesInterleavedRegions(provider);
            recentTileBoundariesAndInvalidation(provider);
            recentTileEvictionAndRecency(provider);
            System.setProperty("td.inference.legacySampling", "true");
            concurrentMissesJoin(provider);
            invalidationDoesNotPublishOrRemoveReplacement(provider);
            previewIsolation(provider);
            groundOnlyProfileParity(provider);
            providerLifecycle(provider);
            System.out.println("PASS recent tile: bounds/scale, clear, eviction, amortized recency, exact hit metrics, full-tile coalescing/invalidation, preview isolation, seed changes, closed provider, same-seed reopen");
            System.out.println("PASS LocalTerrainProviderCacheTest: completed race, pending coalescing, generation invalidation, 512-entry bound, native-reference reuse/upgrade/byte bounds/invalidation, request-shape-dependent scales1..6 exact height/biome/ecology parity, fast ground profile lifecycle, queued ground promotion");
        } finally { LocalTerrainProvider.shutdownAll(); }
    }

    private static void completeTileDefaultReusesInterleavedRegions(LocalTerrainProvider provider) {
        LocalTerrainProvider.clearCache();
        WorldScaleManager.scale = 1;
        System.clearProperty("td.inference.legacySampling");
        check(LocalTerrainProvider.legacySampling(), "production defaults to complete tiles");
        int tile = LocalTerrainProvider.sampleTileSize();
        int before = WorldPipeline.calls.get();
        for (int pass = 0; pass < 2; pass++) for (int region = 0; region < 12; region++) {
            int x = (region - 6) * tile;
            var ground = provider.fetchGroundHeightmap(0, x, tile, x + tile);
            var biome = provider.fetchBiomeHeightmap(0, x, tile, x + tile);
            check(ground == biome, "height and biome reuse the same complete tile");
        }
        check(WorldPipeline.calls.get() - before == 12, "interleaved revisits must not rebuild reference planes");
        System.setProperty("td.inference.legacySampling", "false");
    }

    private static void cacheOnlyWeatherRead(LocalTerrainProvider provider) throws Exception {
        LocalTerrainProvider.clearCache();
        WorldScaleManager.scale = 1;
        var before = provider.performanceSnapshot();
        int calls = WorldPipeline.calls.get();
        check(LocalTerrainProvider.peekInstance() == provider, "peek must reuse live provider");
        check(provider.peekHeightmap(0, 0, 16, 16) == null, "cold cache read is absent");
        check(WorldPipeline.calls.get() == calls && provider.performanceSnapshot().misses() == before.misses(),
                "weather misses cannot start inference");
        var data = provider.fetchHeightmap(0, 0, 16, 16);
        before = provider.performanceSnapshot();
        calls = WorldPipeline.calls.get();
        check(provider.peekHeightmap(0, 0, 16, 16) == data, "peek returns exact cached data");
        WorldScaleManager.scale = 2;
        check(provider.peekHeightmap(0, 0, 16, 16) == null, "weather cache isolates scale");
        WorldScaleManager.scale = 1;
        check(provider.withBinding(LocalTerrainProvider::peekInstance) == provider, "peek honors binding");
        LocalTerrainProvider.clearCache();
        check(provider.peekHeightmap(0, 0, 16, 16) == null, "peek observes invalidation");
        check(WorldPipeline.calls.get() == calls && provider.performanceSnapshot().misses() == before.misses(),
                "weather reads and invalidation do not compute terrain");
        System.out.println("PASS cache-only weather: miss/hit/scale/binding/clear without inference");
    }

    private static void rechecksCompletedAfterOptimisticMiss(LocalTerrainProvider provider) throws Exception {
        LocalTerrainProvider.clearCache(); provider.resetPerformanceMetrics();
        var expected = provider.fetchHeightmap(0,0,16,16);
        int before=WorldPipeline.calls.get();
        Class<?> keyType=Class.forName(LocalTerrainProvider.class.getName()+"$CacheKey");
        Constructor<?> ctor=keyType.getDeclaredConstructor(int.class,int.class,int.class,int.class,int.class);ctor.setAccessible(true);
        Object key=ctor.newInstance(0,0,16,16,1);
        Method generate=LocalTerrainProvider.class.getDeclaredMethod("genHeightmap",keyType,int.class,int.class,int.class,int.class);generate.setAccessible(true);
        Object result=generate.invoke(provider,key,0,0,16,16);
        check(result==expected && before==WorldPipeline.calls.get(),"completed result must be rechecked inside admission lock");
        check(provider.performanceSnapshot().hits()==1,"locked cache recheck counted as hit");
    }

    private static void concurrentMissesJoin(LocalTerrainProvider provider) throws Exception {
        LocalTerrainProvider.clearCache();provider.resetPerformanceMetrics();
        int tile = LocalTerrainProvider.legacySampling() ? 256 : 16;
        WorldPipeline.entered=new CountDownLatch(1);WorldPipeline.release=new CountDownLatch(1);
        int before=WorldPipeline.calls.get();
        var first=CompletableFuture.supplyAsync(()->provider.fetchGroundHeightmap(0,0,tile,tile));
        check(WorldPipeline.entered.await(5,TimeUnit.SECONDS),"native fixture entered");
        var waiters=new ArrayList<CompletableFuture<LocalTerrainProvider.HeightmapData>>();
        for(int i=0;i<5;i++)waiters.add(CompletableFuture.supplyAsync(()->provider.fetchGroundHeightmap(0,0,tile,tile)));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(provider.performanceSnapshot().pendingJoins()<5 && System.nanoTime()<deadline)Thread.onSpinWait();
        check(provider.performanceSnapshot().pendingJoins()==5,"all pending callers coalesced");
        WorldPipeline.release.countDown();
        var expected=first.get(5,TimeUnit.SECONDS);
        for(var waiter:waiters)check(waiter.get(5,TimeUnit.SECONDS)==expected,"shared result identity");
        WorldPipeline.entered=null;WorldPipeline.release=null;
        check(WorldPipeline.calls.get()==before+1,"one actual generation for six callers");
    }

    private static void invalidationDoesNotPublishOrRemoveReplacement(LocalTerrainProvider provider) throws Exception {
        LocalTerrainProvider.clearCache();provider.resetPerformanceMetrics();
        WorldPipeline.entered=new CountDownLatch(1);WorldPipeline.release=new CountDownLatch(1);
        var first=CompletableFuture.supplyAsync(()->provider.fetchGroundHeightmap(0,0,16,16));
        check(WorldPipeline.entered.await(5,TimeUnit.SECONDS),"invalidation fixture entered");
        LocalTerrainProvider.clearCache();
        var replacement=CompletableFuture.supplyAsync(()->provider.fetchGroundHeightmap(0,0,16,16));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(provider.performanceSnapshot().misses()<2 && System.nanoTime()<deadline)Thread.onSpinWait();
        check(provider.performanceSnapshot().misses()==2,"new generation admitted");
        WorldPipeline.release.countDown();
        replacement.get(5,TimeUnit.SECONDS);
        try {first.join();throw new AssertionError("old caller should be cancelled");}
        catch(java.util.concurrent.CompletionException expected){check(expected.getCause() instanceof java.util.concurrent.CancellationException,"cancellation cause");}
        WorldPipeline.entered=null;WorldPipeline.release=null;
        check((LocalTerrainProvider.legacySampling() ? provider.performanceSnapshot().fullTiles()
                : provider.performanceSnapshot().partialTiles())==1,"only replacement published");
        long misses=provider.performanceSnapshot().misses();provider.fetchGroundHeightmap(0,0,16,16);
        check(provider.performanceSnapshot().misses()==misses,"replacement remains cached");
    }

    private static void boundedPartialCache(LocalTerrainProvider provider) {
        LocalTerrainProvider.clearCache();provider.resetPerformanceMetrics();
        for(int i=0;i<520;i++)provider.fetchGroundHeightmap(0,i*16,16,i*16+16);
        check(provider.performanceSnapshot().partialTiles()==512,"partial cache bounded");
        check(provider.performanceSnapshot().evictions()==8,"evictions counted");
        check(provider.performanceSnapshot().referenceMisses()==33,"520 ground subtiles reuse 33 original reference tiles");
        check(provider.performanceSnapshot().referenceTiles()<=8,"native reference entry bound");
    }

    private static void referenceCacheReuseAndBound(LocalTerrainProvider provider) {
        LocalTerrainProvider.clearCache();provider.resetPerformanceMetrics();
        provider.fetchGroundHeightmap(0,0,16,16);
        provider.fetchGroundHeightmap(0,16,16,32);
        check(provider.performanceSnapshot().referenceHits()==1 && provider.performanceSnapshot().referenceMisses()==1,
                "adjacent ground reads share original full reference bounds");
        provider.fetchBiomeHeightmap(0,0,16,16);
        provider.fetchGroundHeightmap(0,32,16,48);
        check(provider.performanceSnapshot().referenceMisses()==2 && provider.performanceSnapshot().referenceHits()==2,
                "climate upgrades ground reference once and remains reusable by height-only callers");
        for(int i=1;i<5;i++)provider.fetchBiomeHeightmap(0,i*256,16,i*256+16);
        var metrics=provider.performanceSnapshot();
        check(metrics.referenceBytes()<=32L*1024*1024 && metrics.referenceTiles()<=8,"native reference retained memory bounded");
        check(metrics.referenceEvictions()>0,"native reference byte-limit evictions counted");
        provider.resetPerformanceMetrics();
        check(provider.performanceSnapshot().referenceMisses()==0 && provider.performanceSnapshot().referenceBytes()==metrics.referenceBytes(),
                "reset metrics preserves cache residency");
        LocalTerrainProvider.clearCache();
        check(provider.performanceSnapshot().referenceBytes()==0 && provider.performanceSnapshot().referenceTiles()==0,
                "cache invalidation drops native reference planes");
    }

    private static void exactPartialParity(LocalTerrainProvider provider) {
        for(int scale=1;scale<=6;scale++) {
            WorldScaleManager.scale=scale;LocalTerrainProvider.clearCache();
            // Partial first so a full cache hit cannot disguise a sparse-path difference.
            var ground=provider.fetchGroundHeightmap(-256,240,-240,256);
            var biome=provider.fetchBiomeHeightmap(-256,240,-240,256);
            var full=provider.fetchHeightmap(-256,0,0,256);
            for(int r=0;r<16;r++)for(int c=0;c<16;c++) {
                check(ground.heightmap[r][c]==full.heightmap[r][240+c],"ground scale="+scale);
                check(biome.heightmap[r][c]==full.heightmap[r][240+c],"biome ground scale="+scale);
                check(biome.biomeIds[r][c]==full.biomeIds[r][240+c],"vanilla scale="+scale);
                check(biome.terralithBiomeIds[r][c]==full.terralithBiomeIds[r][240+c],"terralith scale="+scale);
                check(biome.ecology.terrainBiomeId(c,r)==full.ecology.terrainBiomeId(c+240,r),"ecology terrain scale="+scale);
                check(biome.ecology.sample(c,r).equals(full.ecology.sample(c+240,r)),"ecology fields scale="+scale);
            }
        }
        WorldScaleManager.scale=1;
    }

    private static void recentTileBoundariesAndInvalidation(LocalTerrainProvider provider) throws Exception {
        LocalTerrainProvider.clearCache(); provider.resetPerformanceMetrics();
        var first = provider.fetchHeightmap(-16, -16, 0, 0);
        for (int i = 0; i < 1000; i++) check(provider.fetchHeightmap(-16, -16, 0, 0) == first, "recent identity");
        check(provider.performanceSnapshot().hits() == 1000, "every recent hit counted exactly");
        check(provider.fetchHeightmap(-15, -16, 0, 0) != first, "i1 participates in recent key");
        provider.fetchHeightmap(-16, -16, 0, 0);
        check(provider.fetchHeightmap(-16, -15, 0, 0) != first, "j1 participates in recent key");
        provider.fetchHeightmap(-16, -16, 0, 0);
        check(provider.fetchHeightmap(-16, -16, 1, 0) != first, "i2 participates in recent key");
        provider.fetchHeightmap(-16, -16, 0, 0);
        check(provider.fetchHeightmap(-16, -16, 0, 1) != first, "j2 participates in recent key");
        for (int scale = 2; scale <= 6; scale++) {
            WorldScaleManager.scale = scale;
            var scaled = provider.fetchHeightmap(-16, -16, 0, 0);
            check(scaled != first, "scale participates in recent key");
            check(provider.fetchHeightmap(-16, -16, 0, 0) == scaled, "scaled recent identity");
        }
        WorldScaleManager.scale = 1;
        check(provider.fetchHeightmap(-16, -16, 0, 0) == first, "map fallback returns original scale");
        CompletableFuture.runAsync(LocalTerrainProvider::clearCache).get(5, TimeUnit.SECONDS);
        check(provider.fetchHeightmap(-16, -16, 0, 0) != first, "other thread clear invalidates recent entry");
        provider.resetPerformanceMetrics();
        provider.fetchHeightmap(-16, -16, 0, 0);
        check(provider.performanceSnapshot().hits() == 1, "metrics reset retains recent tile but resets count");
    }

    private static void recentTileEvictionAndRecency(LocalTerrainProvider provider) throws Exception {
        LocalTerrainProvider.clearCache();
        var first = provider.fetchHeightmap(0, 0, 16, 16);
        var field = LocalTerrainProvider.class.getDeclaredField("CACHE_CLOCK"); field.setAccessible(true);
        var clock = (java.util.concurrent.atomic.AtomicLong) field.get(provider);
        long before = clock.get();
        for (int i = 0; i < 128; i++) provider.fetchHeightmap(0, 0, 16, 16);
        check(clock.get() > before && clock.get() <= before + 3, "recent hits periodically refresh approximate LRU");
        CompletableFuture.runAsync(() -> {
            for (int i = 1; i <= 80; i++) provider.fetchHeightmap(0, i * 16, 16, i * 16 + 16);
        }).get(20, TimeUnit.SECONDS);
        check(provider.performanceSnapshot().evictions() > 0, "full cache eviction exercised");
        check(provider.fetchHeightmap(0, 0, 16, 16) != first, "eviction on another thread invalidates recent entry");
    }

    private static void previewIsolation(LocalTerrainProvider gameplay) {
        var original = gameplay.fetchHeightmap(0, 0, 16, 16);
        LocalTerrainProvider.HeightmapData[] previewData = new LocalTerrainProvider.HeightmapData[1];
        LocalTerrainProvider[] previewProvider = new LocalTerrainProvider[1];
        try (var preview = LocalTerrainProvider.createPreviewSession(51, 2)) {
            preview.run(() -> {
                previewProvider[0] = LocalTerrainProvider.getInstance();
                previewData[0] = previewProvider[0].fetchHeightmap(0, 0, 16, 16);
                check(previewData[0] != original, "preview never returns gameplay recent entry");
                check(previewProvider[0].fetchHeightmap(0, 0, 16, 16) == previewData[0], "preview own cache reused");
            });
        }
        expectClosed(() -> previewProvider[0].fetchHeightmap(0, 0, 16, 16));
        check(gameplay.fetchHeightmap(0, 0, 16, 16) == original, "preview leaves gameplay cache untouched");
    }

    private static void providerLifecycle(LocalTerrainProvider provider) {
        LocalTerrainProvider.init(7);
        var first = provider.fetchHeightmap(0, 0, 16, 16);
        LocalTerrainProvider.init(8);
        var changed = provider.fetchHeightmap(0, 0, 16, 16);
        check(first != changed && first.heightmap[0][0] != changed.heightmap[0][0], "seed switch invalidates recent data");
        LocalTerrainProvider.shutdownGameplay();
        expectClosed(() -> provider.fetchHeightmap(0, 0, 16, 16));
        expectClosed(() -> provider.fetchGroundHeightmap(0, 0, 16, 16));
        LocalTerrainProvider.beginGameplay(); LocalTerrainProvider.init(8);
        var reopened = LocalTerrainProvider.getInstance();
        var fresh = reopened.fetchHeightmap(0, 0, 16, 16);
        check(reopened != provider && fresh != changed, "same-seed reopen uses new provider and data");
        check(fresh.heightmap[0][0] == changed.heightmap[0][0], "same seed preserves terrain");
        expectClosed(() -> provider.fetchHeightmap(0, 0, 16, 16));
    }

    /** Ground-only reads must retain full-tile coordinate context without climate work. */
    private static void groundOnlyProfileParity(LocalTerrainProvider provider) throws Exception {
        System.setProperty("td.inference.legacySampling", "true");
        WorldScaleManager.fastGroundSampling = true;
        LocalTerrainProvider.init(91);
        for (int scale = 1; scale <= 6; scale++) {
            WorldScaleManager.scale = scale;
            LocalTerrainProvider.clearCache();
            int climates = WorldPipeline.climateCalls.get();
            var ground = provider.fetchGroundHeightmap(-16, 240, 0, 256);
            check(WorldPipeline.climateCalls.get() == climates, "fast ground avoids climate scale=" + scale);
            var full = provider.fetchHeightmap(-256, 0, 0, 256);
            check(full.ecology != null && full.biomeIds.length == 256, "full after ground remains complete scale=" + scale);
            for (int r = 0; r < 16; r++) for (int c = 0; c < 16; c++)
                check(ground.heightmap[r][c] == full.heightmap[240 + r][240 + c], "fast ground/full parity scale=" + scale);
        }
        WorldScaleManager.scale = 1;
        LocalTerrainProvider.clearCache();
        int fullCrops = WorldPipeline.cropCalls.get();
        var fullGround = provider.fetchGroundHeightmap(0, 0, 256, 256);
        check(fullGround == provider.fetchGroundHeightmap(0, 0, 256, 256), "repeated fast full-tile ground keeps identity");
        check(WorldPipeline.cropCalls.get() == fullCrops + 1, "repeated fast full-tile ground reuses recent hint");
        provider.fetchHeightmap(0, 0, 256, 256);
        int afterFullPromotion = WorldPipeline.cropCalls.get();
        provider.fetchGroundHeightmap(0, 0, 16, 16);
        check(WorldPipeline.cropCalls.get() == afterFullPromotion, "ground crop reuses promoted complete tile");
        LocalTerrainProvider.clearCache();
        int crops = WorldPipeline.cropCalls.get();
        var first = CompletableFuture.supplyAsync(() -> provider.fetchGroundHeightmap(0, 0, 16, 16));
        var second = CompletableFuture.supplyAsync(() -> provider.fetchGroundHeightmap(0, 0, 16, 16));
        check(first.get(5, TimeUnit.SECONDS).heightmap[0][0] == second.get(5, TimeUnit.SECONDS).heightmap[0][0],
                "same fast-ground bounds share one complete result");
        check(WorldPipeline.cropCalls.get() == crops + 1, "one crop request for same fast-ground bounds");
        LocalTerrainProvider.clearCache();
        crops = WorldPipeline.cropCalls.get();
        var afterClear = provider.fetchGroundHeightmap(0, 0, 16, 16);
        check(afterClear.heightmap[0][0] == first.get().heightmap[0][0]
                        && WorldPipeline.cropCalls.get() == crops + 1,
                "fast-ground invalidation recomputes same-bounds result");
        queuedBackgroundGroundYieldsToForegroundFull(provider);
        WorldPipeline.entered = new CountDownLatch(1); WorldPipeline.release = new CountDownLatch(1);
        var stale = CompletableFuture.supplyAsync(() -> provider.fetchGroundHeightmap(288, 288, 304, 304));
        check(WorldPipeline.entered.await(5, TimeUnit.SECONDS), "fast-ground pending fixture entered");
        LocalTerrainProvider.clearCache();
        WorldPipeline.release.countDown();
        try { stale.join(); throw new AssertionError("cleared fast-ground pending result escaped"); }
        catch (java.util.concurrent.CompletionException expected) {
            check(expected.getCause() instanceof java.util.concurrent.CancellationException, "fast-ground clear cancels stale pending result");
        }
        WorldPipeline.entered = null; WorldPipeline.release = null;
        int[] sampled = provider.withSamplingBinding(() -> new int[] {
                LocalTerrainProvider.scopedGroundHeight(-1, -1), LocalTerrainProvider.scopedGroundHeight(-1, -1)});
        check(sampled[0] != Integer.MIN_VALUE && sampled[0] == sampled[1], "sampling cursor falls back to a stable complete tile");
        WorldScaleManager.fastGroundSampling = false;
        LocalTerrainProvider.init(91);
    }

    /** A queued low-priority ground request must recheck after a full tile wins the lane. */
    private static void queuedBackgroundGroundYieldsToForegroundFull(LocalTerrainProvider provider) throws Exception {
        LocalTerrainProvider.clearCache(); provider.resetPerformanceMetrics();
        WorldPipeline.entered = new CountDownLatch(1); WorldPipeline.release = new CountDownLatch(1);
        try {
            int calls = WorldPipeline.calls.get(), crops = WorldPipeline.cropCalls.get();
            var blocker = CompletableFuture.supplyAsync(() -> provider.fetchHeightmap(-256, -256, 0, 0));
            check(WorldPipeline.entered.await(5, TimeUnit.SECONDS), "priority blocker entered");
            var ground = CompletableFuture.supplyAsync(() -> {
                try { return provider.withPriority(InferenceExecutor.Priority.BACKGROUND,
                        () -> provider.fetchGroundHeightmap(0, 0, 256, 256)); }
                catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (provider.performanceSnapshot().misses() < 2 && System.nanoTime() < deadline) Thread.onSpinWait();
            check(provider.performanceSnapshot().misses() >= 2, "background ground queued behind blocker");
            var full = CompletableFuture.supplyAsync(() -> provider.fetchHeightmap(0, 0, 256, 256));
            while (provider.performanceSnapshot().misses() < 3 && System.nanoTime() < deadline) Thread.onSpinWait();
            check(provider.performanceSnapshot().misses() >= 3, "foreground full queued after background ground");
            WorldPipeline.release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            var fullData = full.get(5, TimeUnit.SECONDS);
            check(ground.get(5, TimeUnit.SECONDS) == fullData, "foreground full tile wins queued background ground");
            check(WorldPipeline.calls.get() == calls + 2 && WorldPipeline.cropCalls.get() == crops,
                    "ground recheck performs no additional pipeline crop work");
            check(provider.performanceSnapshot().groundTiles() == 0, "ground recheck leaves no duplicate ground cache entry");
        } finally {
            WorldPipeline.release.countDown();
            WorldPipeline.entered = null; WorldPipeline.release = null;
        }
    }

    private static void expectClosed(Runnable action) {
        try { action.run(); throw new AssertionError("closed provider returned cached data"); }
        catch (java.util.concurrent.CancellationException expected) { }
    }

    private static volatile long benchmarkSink;

    /** Lookup microbenchmark only: deterministic tiles are computed before timing, no ONNX or chunk generation. */
    private static void benchmarkCachedLookup() throws Exception {
        var provider = LocalTerrainProvider.getInstance();
        provider.fetchHeightmap(0, 0, 16, 16);
        int callsBefore = WorldPipeline.calls.get();
        for (int threads : new int[]{1, 4}) {
            try (var pool = Executors.newFixedThreadPool(threads)) {
                int iterations = 500_000;
                double[] samples = new double[5];
                for (int round = -3; round < samples.length; round++) {
                    var ready = new CountDownLatch(threads); var start = new CountDownLatch(1);
                    var futures = new ArrayList<Future<Long>>();
                    long hitsBefore = provider.performanceSnapshot().hits();
                    for (int thread = 0; thread < threads; thread++) futures.add(pool.submit(() -> {
                        ready.countDown(); start.await();
                        long sum = 0;
                        for (int i = 0; i < iterations; i++) sum += provider.fetchHeightmap(0, 0, 16, 16).heightmap[0][0];
                        return sum;
                    }));
                    ready.await(); long began = System.nanoTime(); start.countDown();
                    for (var future : futures) benchmarkSink += future.get();
                    long elapsed = System.nanoTime() - began;
                    check(provider.performanceSnapshot().hits() - hitsBefore == (long) threads * iterations, "concurrent hit metrics exact after workers finish");
                    if (round >= 0) samples[round] = (double) elapsed / (threads * iterations);
                }
                Arrays.sort(samples);
                System.out.printf(java.util.Locale.ROOT,
                        "BENCH cached lookup only recentTileCache=%s threads=%d median=%.2f ns/op throughput=%.2f Mops/s range=%.2f..%.2f ns/op%n",
                        System.getProperty("td.inference.recentTileCache", "true"), threads,
                        samples[2], 1000.0 / samples[2], samples[0], samples[4]);
            }
        }
        check(WorldPipeline.calls.get() == callsBefore, "benchmark only measures warmed cache hits");
    }

    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
