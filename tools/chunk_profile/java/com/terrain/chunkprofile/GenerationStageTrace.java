package com.terrain.chunkprofile;

import java.util.HashSet;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.state.BlockState;

/** Opt-in, fixture-only evidence for a few generated columns. */
public final class GenerationStageTrace {
    private static final int MIN_Y = 54;
    private static final int MAX_Y = 74;
    private static final int WRITE_MIN_Y = 57;
    // Rivers and the shallow marsh water table meet immediately above sea level.
    // Keep this bounded: tracing every density write would bury the later native
    // carver transition that this fixture-only aid is meant to identify.
    private static final int WRITE_MAX_Y = 70;
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

    /**
     * Records the native plan after Streams has placed a chunk.  This stays
     * reflection-only so the diagnostic still loads when Streams is absent and
     * does not make the probe's compile classpath depend on a particular jar.
     */
    public static void captureStreamsPlan(WorldGenLevel level, int minX, int minZ) {
        if (POSITIONS.isEmpty()) return;
        boolean requested = false;
        for (long key : POSITIONS) {
            int x = (int) (key >> 32);
            int z = (int) key;
            if (x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16) {
                requested = true;
                break;
            }
        }
        if (!requested) return;
        try {
            ClassLoader loader = level.getClass().getClassLoader();
            Class<?> worldgen = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen", false, loader);
            Object engine = worldgen.getMethod("engine", WorldGenLevel.class).invoke(null, level);
            if (engine == null) throw new IllegalStateException("null engine");
            Method plan = engine.getClass().getMethod("plan", int.class, int.class);
            Class<?> access = Class.forName("com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRecordAccess",
                    false, loader);
            Method read = access.getMethod("read", Object.class, String.class);
            for (long key : POSITIONS) {
                int x = (int) (key >> 32);
                int z = (int) key;
                if (x < minX || x >= minX + 16 || z < minZ || z >= minZ + 16) continue;
                captureStreamsPlan(key, x, z, engine, plan, read);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            for (long key : POSITIONS) {
                int x = (int) (key >> 32);
                int z = (int) key;
                if (x < minX || x >= minX + 16 || z < minZ || z >= minZ + 16) continue;
                String captureKey = "streams-native-plan:" + key;
                if (CAPTURED.add(captureKey)) System.err.println("TD streams plan x=" + x + " z=" + z
                        + " unavailable=" + failure.getClass().getSimpleName());
            }
        }
    }

    private static void captureStreamsPlan(long key, int x, int z, Object engine, Method plan, Method read) {
        String captureKey = "streams-native-plan:" + key;
        if (!CAPTURED.add(captureKey)) return;
        try {
            Object column = plan.invoke(engine, x, z);
            if (column == null) throw new IllegalStateException("null column plan");
            System.err.println("TD streams plan x=" + x + " z=" + z
                    + " inChannel=" + read(read, column, "inChannel")
                    + " inCorridor=" + read(read, column, "inCorridor")
                    + " waterSurfaceY=" + read(read, column, "waterSurfaceY")
                    + " bedY=" + read(read, column, "bedY")
                    + " source=" + read(read, column, "source")
                    + " terminal=" + read(read, column, "terminal")
                    + " distance=" + read(read, column, "distance")
                    + " width=" + read(read, column, "width"));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            System.err.println("TD streams plan x=" + x + " z=" + z
                    + " unavailable=" + failure.getClass().getSimpleName());
        }
    }

    private static Object read(Method read, Object plan, String accessor) throws ReflectiveOperationException {
        return read.invoke(null, plan, accessor);
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
