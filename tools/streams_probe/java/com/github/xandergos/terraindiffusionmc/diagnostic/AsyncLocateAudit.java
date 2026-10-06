package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.world.AsyncStructureLocate;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated native audit. Requires Async Locator in the probe's mods directory. */
public final class AsyncLocateAudit {
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static <T> CompletableFuture<T> worker(java.util.concurrent.Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try { result.complete(action.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        }, "td-async-locate-audit");
        thread.setDaemon(true);
        thread.start();
        return result;
    }

    public static String run(MinecraftServer server) throws Exception {
        var level = server.overworld();
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        String id = System.getProperty("td.streamsProbe.asyncLocateStructure", "#minecraft:village");
        HolderSet<Structure> targets = id.startsWith("#")
                ? registry.getTag(net.minecraft.tags.TagKey.create(Registries.STRUCTURE,
                        ResourceLocation.parse(id.substring(1)))).orElseThrow()
                : HolderSet.direct(registry.getHolder(net.minecraft.resources.ResourceKey.create(
                        Registries.STRUCTURE, ResourceLocation.parse(id))).orElseThrow());
        Holder<Structure> holder = targets.get(0);
        Field checkField = StructureManager.class.getDeclaredField("structureCheck");
        checkField.setAccessible(true);
        StructureCheck check = (StructureCheck) checkField.get(level.structureManager());
        require(Arrays.stream(StructureCheck.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().contains("asynclocator") && method.getName().contains("synchronizeCheckStart")),
                "Async Locator synchronization mixin is not installed in this audit");
        Method canCreate = StructureCheck.class.getDeclaredMethod("canCreateStructure", ChunkPos.class, Structure.class);
        canCreate.setAccessible(true);
        AtomicInteger probes = new AtomicInteger();
        Structure original = holder.value();
        Structure slow = new Structure(new Structure.StructureSettings(original.biomes(), original.spawnOverrides(),
                original.step(), original.terrainAdaptation())) {
            @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
                probes.incrementAndGet();
                try { Thread.sleep(500); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                return Optional.empty();
            }
            @Override public StructureType<?> type() { return original.type(); }
        };
        long start = System.nanoTime();
        require(worker(() -> {
            synchronized (check) { return (Boolean) canCreate.invoke(check, new ChunkPos(-123456, 123456), slow); }
        }).get(5, TimeUnit.SECONDS), "Locked probe must request exact chunk verification");
        long lockedProbeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        require(probes.get() == 0, "Speculative terrain ran while the shared structure monitor was held");
        require(!worker(() -> (Boolean) canCreate.invoke(check, new ChunkPos(-123456, 123456), slow))
                .get(5, TimeUnit.SECONDS), "Unlocked vanilla probe was replaced");
        require(probes.get() == 1, "Unlocked vanilla generation-point probe did not run");
        require(server.submit(() -> {
            try { AsyncStructureLocate.getChunk(level, 0, 0); return false; }
            catch (IllegalStateException expected) { return true; }
        }).get(5, TimeUnit.SECONDS), "Server-thread blocking bridge was admitted");

        BlockPos origin = new BlockPos(Integer.getInteger("td.streamsProbe.asyncLocateX", -2179), 128,
                Integer.getInteger("td.streamsProbe.asyncLocateZ", 3517));
        int radius = Integer.getInteger("td.streamsProbe.asyncLocateRadius", 8);
        CompletableFuture<Pair<BlockPos, Holder<Structure>>> located = worker(() -> level.getChunkSource().getGenerator()
                .findNearestMapStructure(level, targets, origin, radius, false));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        long maxCallbackMs = 0, callbacks = 0;
        start = System.nanoTime();
        while (!located.isDone()) {
            require(System.nanoTime() < until, "Exact async locate did not complete within 180 seconds");
            long sent = System.nanoTime();
            var callback = server.submit(() -> true);
            try { callback.get(250, TimeUnit.MILLISECONDS); }
            catch (TimeoutException slowCallback) {
                Thread.getAllStackTraces().forEach((thread, stack) -> {
                    if (thread.getName().equals("Server thread"))
                        System.out.println("ASYNC_LOCATE_SLOW_SERVER " + Arrays.toString(stack));
                });
                callback.get(15, TimeUnit.SECONDS);
            }
            maxCallbackMs = Math.max(maxCallbackMs, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent));
            callbacks++;
            Thread.sleep(50);
        }
        long locateMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Pair<BlockPos, Holder<Structure>> found = located.get();
        require(found != null, "No exact structure result in fixture radius");
        BlockPos pos = found.getFirst();
        var chunk = worker(() -> AsyncStructureLocate.getChunk(level, pos.getX() >> 4, pos.getZ() >> 4))
                .get(60, TimeUnit.SECONDS);
        require(server.submit(() -> {
            var actual = chunk.getStartForStructure(found.getSecond().value());
            return actual != null && actual.isValid();
        }).get(5, TimeUnit.SECONDS), "Async locate accepted an invalid structure start");
        // Stop is destructive only to this audit's locate admission, so do it last.
        server.submit(() -> { AsyncStructureLocate.stop(server); return true; }).get(5, TimeUnit.SECONDS);
        require(worker(() -> {
            try { AsyncStructureLocate.getChunk(level, 0, 0); return false; }
            catch (java.util.concurrent.CancellationException expected) { return true; }
        }).get(5, TimeUnit.SECONDS), "Late request reopened locate admission after stop");
        return "{\"event\":\"async_locate_audit\",\"status\":\"passed\",\"lockedProbeMs\":" + lockedProbeMs
                + ",\"lockedNativeProbes\":0,\"unlockedProbePreserved\":true,\"validStructureStart\":true"
                + ",\"serverThreadRejected\":true,\"stopAdmissionRejected\":true,\"locateMs\":" + locateMs
                + ",\"maxServerCallbackMs\":" + maxCallbackMs + ",\"callbacks\":" + callbacks + "}";
    }

    private AsyncLocateAudit() { }
}
