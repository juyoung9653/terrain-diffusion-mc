package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One-time copied-world diagnostic; uses normal server block updates only. */
public final class SavedWaterSettlement {
    private static final Logger LOG = LoggerFactory.getLogger("TerrainDiffusion/SavedWaterSettlement");
    private static final int TICKS = 1200;
    private static final int CHUNK_BATCH = 8;
    private static final int TARGET_BATCH = 1000;
    private SavedWaterSettlement() { }

    public static String run(MinecraftServer server, Path positionsFile, Path chunksFile) throws Exception {
        Inputs input = parse(positionsFile, chunksFile);
        ServerLevel level = server.overworld();
        Receipt receipt = new Receipt(input.targets.size(), input.chunks.size());
        List<ChunkPos> addedForces = new ArrayList<>();
        OperationState prior = null;
        Exception failure = null;
        try {
            int sea = submit(server, () -> { requireProfileAndConfig(level); return level.getSeaLevel(); });
            validateTargets(input, sea, submit(server, level::getMaxBuildHeight));
            prior = submit(server, () -> new OperationState(server.tickRateManager().isFrozen(), level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).get()));
            // Freeze before loading: no target can settle before the all-target validation.
            submit(server, () -> { server.tickRateManager().setFrozen(true); level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server); return null; });
            force(server, level, input.chunks, addedForces);
            receipt.loadedChunks = input.chunks.size();
            receipt.tickableChunks = awaitTickable(server, level, input.chunks, 60_000L);
            if (receipt.tickableChunks != input.chunks.size()) throw new IllegalStateException("not all requested chunks are ticking");
            WaterScan before = scanChunks(server, level, input.chunks);
            int invalid = submit(server, () -> invalidTargets(level, input.targets));
            receipt.sourceCount = before.sources.size(); receipt.sourceDigest = digest(before.sources);
            if (invalid != 0) throw new IllegalStateException("all targets must be actual ordinary non-source water; invalid=" + invalid);
            receipt.removed = clearTargets(server, level, input.targets);
            if (receipt.removed != input.targets.size()) throw new IllegalStateException("normal block update did not remove every validated target");
            WaterScan immediate = scanChunks(server, level, input.chunks);
            receipt.sourceAdded = difference(immediate.sources, before.sources);
            receipt.sourceRemoved = difference(before.sources, immediate.sources);
            if (receipt.sourceAdded != 0 || receipt.sourceRemoved != 0) throw new IllegalStateException("source coordinates changed during clear");
            scheduleReflow(server, level, input.targets, before.sources);
            receipt.startTick = submit(server, server::getTickCount);
            submit(server, () -> { server.tickRateManager().setFrozen(false); return null; });
            receipt.endTick = awaitTicks(server, level, input.chunks, receipt.startTick + TICKS, 180_000L);
            WaterScan after = scanChunks(server, level, input.chunks);
            receipt.finalFlowing = after.flowing;
            receipt.sourceAdded = difference(after.sources, before.sources);
            receipt.sourceRemoved = difference(before.sources, after.sources);
            receipt.ticks = receipt.endTick - receipt.startTick;
            receipt.status = receipt.sourceRemoved == 0 ? "settled" : "source_changed";
            if (receipt.sourceRemoved != 0) throw new IllegalStateException("source water coordinates changed during settlement");
        } catch (Exception caught) {
            failure = caught;
            if (receipt.status.equals("pending")) receipt.status = "failed";
            receipt.error = caught.getClass().getSimpleName() + ": " + caught.getMessage();
        } finally {
            Exception cleanup = null;
            for (int start = 0; start < addedForces.size(); start += CHUNK_BATCH) try {
                List<ChunkPos> batch = addedForces.subList(start, Math.min(addedForces.size(), start + CHUNK_BATCH));
                submit(server, () -> { for (ChunkPos chunk : batch) level.setChunkForced(chunk.x, chunk.z, false); return null; });
            } catch (Exception caught) { cleanup = append(cleanup, caught); }
            if (prior != null) try {
                OperationState restore = prior;
                submit(server, () -> { level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(restore.randomTickSpeed, server); server.tickRateManager().setFrozen(restore.frozen); return null; });
            } catch (Exception caught) { cleanup = append(cleanup, caught); }
            if (cleanup != null) { receipt.status = "failed"; receipt.error = "cleanup: " + cleanup.getMessage(); if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
        }
        String json = receipt.json();
        try { Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("td-saved-water-settlement.json"), json + "\n", StandardCharsets.UTF_8); }
        catch (IOException writeFailure) { if (failure != null) failure.addSuppressed(writeFailure); else throw writeFailure; }
        if (failure != null) throw failure;
        return json;
    }

    private static void requireProfileAndConfig(ServerLevel level) throws Exception {
        if (!WorldNaturalRiverState.enabled(level) || WorldNaturalRiverState.version(level) != 7) throw new IllegalStateException("saved-water settlement requires enabled natural-river profile 7");
        Class<?> config = Class.forName("dev.streamsreflowing.neoforge.StreamsConfigNeo");
        if ((Boolean) config.getMethod("spillOverWater").invoke(null) || (Boolean) config.getMethod("forceBakedFluid").invoke(null)) throw new IllegalStateException("requires spillOverWater=false and forceBakedFluid=false");
    }

    private static void validateTargets(Inputs input, int sea, int maxY) {
        for (Target target : input.targets) {
            if (target.y <= sea || target.y >= maxY) throw new IllegalArgumentException("target outside above-sea build range: " + target);
            if (!input.chunkSet.contains(new ChunkPos(target.x >> 4, target.z >> 4))) throw new IllegalArgumentException("target outside supplied chunk load set: " + target);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (!input.chunkSet.contains(new ChunkPos((target.x >> 4) + dx, (target.z >> 4) + dz)))
                    throw new IllegalArgumentException("target requires a complete one-chunk load halo: " + target);
            }
        }
    }

    private static Inputs parse(Path positionsFile, Path chunksFile) throws IOException {
        Set<Target> targetSet = new LinkedHashSet<>();
        for (String line : Files.readAllLines(positionsFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] part = line.trim().split(",", -1);
            if (part.length != 3) throw new IllegalArgumentException("target must be x,y,z: " + line);
            Target target = new Target(integer(part[0], line), integer(part[1], line), integer(part[2], line));
            if (!targetSet.add(target)) throw new IllegalArgumentException("duplicate target: " + line);
        }
        Set<ChunkPos> chunkSet = new LinkedHashSet<>();
        for (String line : Files.readAllLines(chunksFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] part = line.trim().split(",", -1);
            if (part.length != 2) throw new IllegalArgumentException("chunk must be cx,cz: " + line);
            ChunkPos chunk = new ChunkPos(integer(part[0], line), integer(part[1], line));
            if (!chunkSet.add(chunk)) throw new IllegalArgumentException("duplicate chunk: " + line);
        }
        if (targetSet.isEmpty() || chunkSet.isEmpty()) throw new IllegalArgumentException("targets and chunks must both be nonempty");
        List<Target> targets = new ArrayList<>(targetSet); targets.sort(Comparator.comparingInt(Target::x).thenComparingInt(Target::z).thenComparingInt(Target::y));
        List<ChunkPos> chunks = new ArrayList<>(chunkSet); chunks.sort(Comparator.comparingInt((ChunkPos c) -> c.x).thenComparingInt(c -> c.z));
        return new Inputs(List.copyOf(targets), Set.copyOf(chunkSet), List.copyOf(chunks));
    }

    private static int integer(String value, String line) { try { return Integer.parseInt(value.trim()); } catch (NumberFormatException failure) { throw new IllegalArgumentException("invalid integer: " + line, failure); } }

    private static void force(MinecraftServer server, ServerLevel level, List<ChunkPos> chunks, List<ChunkPos> added) throws Exception {
        for (int start = 0; start < chunks.size(); start += CHUNK_BATCH) {
            int end = Math.min(chunks.size(), start + CHUNK_BATCH); List<ChunkPos> batch = chunks.subList(start, end);
            added.addAll(submit(server, () -> { List<ChunkPos> out = new ArrayList<>(); for (ChunkPos chunk : batch) if (level.setChunkForced(chunk.x, chunk.z, true)) out.add(chunk); return out; }));
            LOG.info("Saved-water settlement forced {}/{} chunks", end, chunks.size());
        }
    }

    private static int awaitTickable(MinecraftServer server, ServerLevel level, List<ChunkPos> chunks, long timeout) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout); int count = 0;
        while (System.nanoTime() < deadline) { count = submit(server, () -> tickable(level, chunks)); if (count == chunks.size()) return count; Thread.sleep(50L); }
        return count;
    }
    private static long awaitTicks(MinecraftServer server, ServerLevel level, List<ChunkPos> chunks, long target, long timeout) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (System.nanoTime() < deadline) { long tick = submit(server, server::getTickCount); if (tick >= target && submit(server, () -> tickable(level, chunks)) == chunks.size()) return tick; Thread.sleep(50L); }
        throw new IllegalStateException("did not advance " + TICKS + " ticks with every chunk tickable");
    }
    private static int tickable(ServerLevel level, List<ChunkPos> chunks) { int count = 0; for (ChunkPos chunk : chunks) { BlockPos pos = new BlockPos(chunk.getMiddleBlockX(), level.getMinBuildHeight(), chunk.getMiddleBlockZ()); if (level.isPositionEntityTicking(pos) && level.getChunkSource().isPositionTicking(ChunkPos.asLong(chunk.x, chunk.z))) count++; } return count; }

    private static WaterScan scanChunks(MinecraftServer server, ServerLevel level, List<ChunkPos> chunks) throws Exception {
        Set<Long> sources = new HashSet<>(); int flowing = 0;
        for (int start = 0; start < chunks.size(); start += CHUNK_BATCH) {
            List<ChunkPos> cohort = chunks.subList(start, Math.min(chunks.size(), start + CHUNK_BATCH));
            WaterScan batch = submit(server, () -> scanLoaded(level, cohort));
            sources.addAll(batch.sources); flowing += batch.flowing;
        }
        return new WaterScan(sources, flowing);
    }
    /** Direct section reads skip air and never query an unloaded neighbour. Server thread only. */
    private static WaterScan scanLoaded(ServerLevel level, List<ChunkPos> chunks) {
        Set<Long> sources = new HashSet<>(); int flowing = 0;
        for (ChunkPos chunk : chunks) {
            LevelChunk loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
            if (loaded == null) throw new IllegalStateException("unloaded requested chunk " + chunk);
            LevelChunkSection[] sections = loaded.getSections();
            for (int index = 0; index < sections.length; index++) {
                LevelChunkSection section = sections[index]; if (section.hasOnlyAir()) continue;
                int baseY = (level.getMinSection() + index) << 4, firstY = Math.max(0, level.getSeaLevel() + 1 - baseY);
                if (firstY >= 16) continue;
                for (int y = firstY; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    BlockState state = section.getBlockState(x, y, z); FluidState fluid = state.getFluidState();
                    if (!fluid.is(FluidTags.WATER)) continue;
                    if (fluid.isSource()) sources.add(BlockPos.asLong(chunk.getMinBlockX() + x, baseY + y, chunk.getMinBlockZ() + z)); else flowing++;
                }
            }
        }
        return new WaterScan(sources, flowing);
    }
    private static int invalidTargets(ServerLevel level, List<Target> targets) { int invalid = 0; for (Target target : targets) { BlockPos pos = new BlockPos(target.x, target.y, target.z); FluidState fluid = level.getFluidState(pos); if (!level.getBlockState(pos).is(Blocks.WATER) || !fluid.is(FluidTags.WATER) || fluid.isSource()) invalid++; } return invalid; }
    private static int clearTargets(MinecraftServer server, ServerLevel level, List<Target> targets) throws Exception { int removed = 0; for (int start = 0; start < targets.size(); start += TARGET_BATCH) { int end = Math.min(targets.size(), start + TARGET_BATCH); List<Target> batch = targets.subList(start, end); removed += submit(server, () -> { int changed = 0; for (Target target : batch) if (level.setBlock(new BlockPos(target.x, target.y, target.z), Blocks.AIR.defaultBlockState(), 3)) changed++; return changed; }); LOG.info("Saved-water settlement cleared {}/{} targets", end, targets.size()); } return removed; }
    private static void scheduleReflow(MinecraftServer server, ServerLevel level, List<Target> targets, Set<Long> sources) throws Exception {
        Set<Long> positions = new HashSet<>();
        for (Target target : targets) { BlockPos pos = new BlockPos(target.x, target.y, target.z); positions.add(pos.above().asLong()); positions.add(pos.below().asLong()); positions.add(pos.north().asLong()); positions.add(pos.south().asLong()); positions.add(pos.east().asLong()); positions.add(pos.west().asLong()); }
        positions.addAll(sources); List<Long> ordered = new ArrayList<>(positions); ordered.sort(Long::compare); int scheduled = 0;
        for (int start = 0; start < ordered.size(); start += TARGET_BATCH) { int end = Math.min(ordered.size(), start + TARGET_BATCH); List<Long> batch = ordered.subList(start, end); scheduled += submit(server, () -> { int count = 0; for (long raw : batch) { BlockPos pos = BlockPos.of(raw); FluidState fluid = level.getFluidState(pos); if (fluid.is(FluidTags.WATER)) { level.scheduleTick(pos, fluid.getType(), 1); count++; } } return count; }); }
        LOG.info("Saved-water settlement scheduled {} source/adjacent fluid ticks", scheduled);
    }
    private static int difference(Set<Long> left, Set<Long> right) { int count = 0; for (long value : left) if (!right.contains(value)) count++; return count; }
    private static String digest(Set<Long> positions) { try { MessageDigest digest = MessageDigest.getInstance("SHA-256"); positions.stream().sorted().forEach(value -> digest.update((Long.toString(value) + "\n").getBytes(StandardCharsets.UTF_8))); StringBuilder out = new StringBuilder(); for (byte value : digest.digest()) out.append(String.format("%02x", value)); return out.toString(); } catch (Exception failure) { throw new IllegalStateException("SHA-256 unavailable", failure); } }
    private static Exception append(Exception first, Exception next) { if (first == null) return next; first.addSuppressed(next); return first; }
    private static <T> T submit(MinecraftServer server, java.util.concurrent.Callable<T> task) throws Exception {
        try {
            return server.submit(() -> {
                try { return task.call(); }
                catch (Exception failure) { throw new CompletionException(failure); }
            }).get(120, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof CompletionException wrapped && wrapped.getCause() instanceof Exception checked) throw checked;
            if (cause instanceof Exception checked) throw checked;
            throw new IllegalStateException("server task failed", cause);
        }
    }
    private record Inputs(List<Target> targets, Set<ChunkPos> chunkSet, List<ChunkPos> chunks) { }
    private record Target(int x, int y, int z) { }
    private record WaterScan(Set<Long> sources, int flowing) { }
    private record OperationState(boolean frozen, int randomTickSpeed) { }
    private static final class Receipt {
        final int targets, requestedChunks; int loadedChunks, tickableChunks, removed, sourceCount, sourceAdded, sourceRemoved, finalFlowing; long startTick, endTick, ticks; String sourceDigest = "", status = "pending", error = "";
        Receipt(int targets, int requestedChunks) { this.targets = targets; this.requestedChunks = requestedChunks; }
        String json() { return "{\"status\":\"" + status + "\",\"targetCount\":" + targets + ",\"removed\":" + removed + ",\"ticks\":" + ticks + ",\"requestedChunks\":" + requestedChunks + ",\"loadedChunks\":" + loadedChunks + ",\"tickableChunks\":" + tickableChunks + ",\"sourcePositionsCount\":" + sourceCount + ",\"sourcePositionsDigest\":\"" + sourceDigest + "\",\"sourceAdded\":" + sourceAdded + ",\"sourceRemoved\":" + sourceRemoved + ",\"finalFlowing\":" + finalFlowing + ",\"error\":\"" + error.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"; }
    }
}
