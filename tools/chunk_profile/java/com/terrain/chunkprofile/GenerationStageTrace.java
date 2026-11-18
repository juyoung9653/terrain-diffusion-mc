package com.terrain.chunkprofile;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.state.BlockState;

/** Opt-in, fixture-only evidence for a few generated columns. */
public final class GenerationStageTrace {
    private static final int MIN_Y = 54;
    private static final int MAX_Y = 74;
    private static final int WRITE_MIN_Y = 57;
    private static final int WRITE_MAX_Y = 63;
    private static final int MAX_WRITES_PER_BLOCK = 4;
    private static final Set<Long> POSITIONS = positions();
    private static final Set<String> CAPTURED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, AtomicInteger> WRITES = new ConcurrentHashMap<>();

    private GenerationStageTrace() { }

    public static void capture(String stage, ChunkAccess chunk) {
        if (POSITIONS.isEmpty()) return;
        ChunkPos chunkPos = chunk.getPos();
        capture(stage, chunk, chunkPos.getMinBlockX(), chunkPos.getMinBlockZ());
    }

    public static void capture(String stage, BlockGetter level, int minX, int minZ) {
        if (POSITIONS.isEmpty()) return;
        for (long key : POSITIONS) {
            int x = (int) (key >> 32);
            int z = (int) key;
            if (x < minX || x >= minX + 16 || z < minZ || z >= minZ + 16) continue;
            if (!CAPTURED.add(stage + ':' + key)) continue;
            System.err.println("TD generation trace x=" + x + " z=" + z + " stage=" + stage
                    + " y" + MIN_Y + ".." + MAX_Y + '=' + profile(level, x, z));
        }
    }

    /** Called after a chunk write. It reports only a state transition that actually stuck. */
    public static void captureWrite(BlockGetter level, BlockPos pos, BlockState before,
                                    BlockState proposed, String holder) {
        if (POSITIONS.isEmpty() || pos.getY() < WRITE_MIN_Y || pos.getY() > WRITE_MAX_Y) return;
        long key = key(pos.getX(), pos.getZ());
        if (!POSITIONS.contains(key)) return;
        BlockState actual = level.getBlockState(pos);
        if (before == null || before.equals(actual)) return;
        // Keep independent budgets per Y. Initial density filling cannot consume
        // the later floor transition's evidence at a different level.
        String writeKey = key + ":" + pos.getY();
        AtomicInteger count = WRITES.computeIfAbsent(writeKey, ignored -> new AtomicInteger());
        if (count.incrementAndGet() > MAX_WRITES_PER_BLOCK) return;
        System.err.println("TD generation write x=" + pos.getX() + " y=" + pos.getY() + " z=" + pos.getZ()
                + " holder=" + holder + " before=" + BuiltInRegistries.BLOCK.getKey(before.getBlock())
                + " proposed=" + BuiltInRegistries.BLOCK.getKey(proposed.getBlock())
                + " actual=" + BuiltInRegistries.BLOCK.getKey(actual.getBlock())
                + " stack=" + writeStack());
    }

    private static Set<Long> positions() {
        String configured = System.getProperty("td.generation.tracePositions", "").trim();
        if (configured.isEmpty()) return Set.of();
        Set<Long> positions = new HashSet<>();
        for (String entry : configured.split(";")) {
            String[] pair = entry.trim().split(",");
            if (pair.length != 2 || positions.size() >= 8) continue;
            try { positions.add(key(Integer.parseInt(pair[0].trim()), Integer.parseInt(pair[1].trim()))); }
            catch (NumberFormatException ignored) { }
        }
        return Set.copyOf(positions);
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private static String profile(BlockGetter level, int x, int z) {
        StringBuilder result = new StringBuilder();
        int runStart = MIN_Y;
        BlockState previous = level.getBlockState(new BlockPos(x, runStart, z));
        for (int y = MIN_Y + 1; y <= MAX_Y + 1; y++) {
            BlockState current = y <= MAX_Y ? level.getBlockState(new BlockPos(x, y, z)) : null;
            if (y <= MAX_Y && current.equals(previous)) continue;
            if (!result.isEmpty()) result.append(',');
            result.append(runStart).append("..").append(y - 1).append(':')
                    .append(BuiltInRegistries.BLOCK.getKey(previous.getBlock()));
            runStart = y;
            previous = current;
        }
        return result.toString();
    }

    private static String writeStack() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(frame -> frame.getClassName() + '#' + frame.getMethodName())
                .filter(name -> name.startsWith("dev.streamsreflowing.")
                        || name.startsWith("net.minecraft.world.level.levelgen.")
                        || name.startsWith("com.github.xandergos.terraindiffusionmc."))
                .limit(6).reduce((left, right) -> left + '>' + right).orElse("unavailable"));
    }
}
