package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsBankLakeMask;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsShallowBanks;
import dev.streamsreflowing.core.RiverEngine;
import dev.streamsreflowing.core.compose.ColumnPlan;
import dev.streamsreflowing.worldgen.StreamsWorldgen;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Differential test for the V6 dry-bank guard, intentionally independent of its reflection code. */
public final class ShallowBanksHarness {
    private static final BlockPos POS = new BlockPos(17, -9, 33);
    private static final BlockState AIR = new BlockState(true, null, false);
    private static final BlockState DIRT = new BlockState(false, Blocks.DIRT, false);
    private static final BlockState GRASS = new BlockState(false, Blocks.GRASS_BLOCK, false);
    private static final BlockState COARSE = new BlockState(false, Blocks.COARSE_DIRT, false);
    private static final BlockState ROOTED = new BlockState(false, Blocks.ROOTED_DIRT, false);
    private static final BlockState PODZOL = new BlockState(false, Blocks.PODZOL, false);
    private static final BlockState MUD = new BlockState(false, Blocks.MUD, false);
    private static final BlockState WATER_DIRT = new BlockState(false, Blocks.DIRT, true);
    private static final BlockState STONE = new BlockState(false, new Object(), false);
    private static final ColumnPlan FAR = plan(false, true, -100, 2, 0, false, false);
    private static final AtomicLong LEGACY_RETAINED_WRITES = new AtomicLong();

    private ShallowBanksHarness() { }

    public static void main(String[] args) throws Exception {
        boolean benchmark = args.length == 1 && args[0].equals("--benchmark");
        testBranches();
        testHeightEdges();
        testCallReductions();
        testParallelEngines();
        if (benchmark) benchmark();
        System.out.println("ShallowBanksHarness: PASS");
    }

    private static void testBranches() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("retain-negative-y", 6, 6, DIRT, AIR, engine(6, baseCentre(), -8), true));
        cases.add(new Case("retain-high-positive-y", 6, 6, DIRT, AIR, engine(6, baseCentre(), 400), true, new BlockPos(17, 400, 33)));
        cases.add(new Case("grass", 6, 6, GRASS, AIR, engine(6, baseCentre(), -8), true));
        cases.add(new Case("coarse-dirt", 6, 6, COARSE, AIR, engine(6, baseCentre(), -8), true));
        cases.add(new Case("rooted-dirt", 6, 6, ROOTED, AIR, engine(6, baseCentre(), -8), true));
        cases.add(new Case("podzol", 6, 6, PODZOL, AIR, engine(6, baseCentre(), -8), true));
        cases.add(new Case("mud", 6, 6, MUD, AIR, engine(6, baseCentre(), -8), true));
        cases.add(new Case("replacement-not-air", 6, 6, DIRT, DIRT, engine(6, baseCentre(), -8), false));
        cases.add(new Case("world-v5", 5, 6, DIRT, AIR, engine(6, baseCentre(), -8), false));
        cases.add(new Case("config-v5", 6, 5, DIRT, AIR, engine(5, baseCentre(), -8), false));
        cases.add(new Case("legacy-lake", 6, 8, DIRT, AIR, engine(8, baseCentre(), -8).lakeChunk(true), false));
        cases.add(new Case("channel-centre", 6, 6, DIRT, AIR, engine(6, plan(true, true, -10, 2, 0, false, false), -8), false));
        cases.add(new Case("not-corridor", 6, 6, DIRT, AIR, engine(6, plan(false, false, -10, 2, 0, false, false), -8), false));
        cases.add(new Case("source", 6, 6, DIRT, AIR, engine(6, plan(false, true, -10, 2, 0, true, false), -8), false));
        cases.add(new Case("terminal", 6, 6, DIRT, AIR, engine(6, plan(false, true, -10, 2, 0, false, true), -8), false));
        cases.add(new Case("nan-width", 6, 6, DIRT, AIR, engine(6, plan(false, true, -10, Double.NaN, 0, false, false), -8), false));
        cases.add(new Case("nan-distance", 6, 6, DIRT, AIR, engine(6, plan(false, true, -10, 2, Double.NaN, false, false), -8), false));
        cases.add(new Case("zero-width", 6, 6, DIRT, AIR, engine(6, plan(false, true, -10, 0, 0, false, false), -8), false));
        cases.add(new Case("wide", 6, 6, DIRT, AIR, engine(6, plan(false, true, -10, 4, 0, false, false), -8), false));
        cases.add(new Case("too-far", 6, 6, DIRT, AIR, engine(6, plan(false, true, -10, 2, 5.01, false, false), -8), false));
        cases.add(new Case("v9-mask", 6, 9, DIRT, AIR, engine(9, baseCentre(), -8).mask(true), false));
        cases.add(new Case("no-neighbor", 6, 6, DIRT, AIR, engine(6, baseCentre(), -8).neighbors(FAR), false));
        cases.add(new Case("above-freeboard", 6, 6, DIRT, AIR, engine(6, baseCentre(), -12), false));
        cases.add(new Case("wet-soil", 6, 6, WATER_DIRT, AIR, engine(6, baseCentre(), -8), false));
        cases.add(new Case("not-soil", 6, 6, STONE, AIR, engine(6, baseCentre(), -8), false));
        long before = StreamsShallowBanks.retainedWrites();
        int accepted = 0;
        for (Case value : cases) {
            compare(value);
            if (value.expected) accepted++;
        }
        long delta = StreamsShallowBanks.retainedWrites() - before;
        require(delta == accepted, "branch retained writes expected=" + accepted + " actual=" + delta);
    }

    private static void compare(Case value) {
        boolean expected = original(new Level(value.worldVersion, value.existing), value.pos, value.replacement, value.engine.copy());
        value.engine.reset();
        StreamsWorldgen.setEngine(value.engine);
        boolean actual = StreamsShallowBanks.preserve(new Level(value.worldVersion, value.existing), value.pos, value.replacement);
        require(actual == expected, value.name + " output differs expected=" + expected + " actual=" + actual);
        require(actual == value.expected, value.name + " fixture expected value stale");
    }

    private static void testHeightEdges() {
        long before = StreamsShallowBanks.retainedWrites();
        List<Case> edges = List.of(
                new Case("freeboard-boundary", 6, 6, DIRT, AIR, engine(6, baseCentre(), 400), true, new BlockPos(17, 401, 33)),
                new Case("above-freeboard-boundary", 6, 6, DIRT, AIR, engine(6, baseCentre(), 400), false, new BlockPos(17, 402, 33)),
                new Case("minimum-y", 6, 6, DIRT, AIR, engine(6, baseCentre(), Integer.MIN_VALUE), true, new BlockPos(17, Integer.MIN_VALUE, 33)),
                // The original safeWater + 1 uses int arithmetic; MAX_VALUE overflows and must remain rejected.
                new Case("maximum-overflow", 6, 6, DIRT, AIR, engine(6, baseCentre(), Integer.MAX_VALUE), false, new BlockPos(17, Integer.MAX_VALUE, 33)),
                new Case("maximum-water-negative-y", 6, 6, DIRT, AIR, engine(6, baseCentre(), Integer.MAX_VALUE), false, new BlockPos(17, -1, 33)));
        int accepted = 0;
        for (Case edge : edges) {
            compare(edge);
            if (edge.expected) accepted++;
        }
        long delta = StreamsShallowBanks.retainedWrites() - before;
        require(delta == accepted, "height-edge retained writes expected=" + accepted + " actual=" + delta);
        System.out.println("height boundaries: cases=" + edges.size() + " retained=" + delta);
    }
    private static void testCallReductions() {
        Engine nonSoil = engine(6, baseCentre(), -8);
        int oldNonSoil = originalCalls(nonSoil, STONE);
        int newNonSoil = productionCalls(nonSoil, STONE);
        require(oldNonSoil == 26, "baseline non-soil must reach all plans");
        require(!Boolean.getBoolean("shallowBanks.expectOptimized") || newNonSoil < oldNonSoil, "non-soil must avoid plan calls old=" + oldNonSoil + " new=" + newNonSoil);
        require(Boolean.getBoolean("shallowBanks.expectOptimized") || newNonSoil == oldNonSoil, "baseline call count changed unexpectedly");

        Engine earlyWater = engine(6, baseCentre(), -8).neighbor(-2, -2, plan(true, true, -8, 2, 0, false, false));
        int oldEarlyWater = originalCalls(earlyWater, DIRT);
        int newEarlyWater = productionCalls(earlyWater, DIRT);
        require(oldEarlyWater == 26, "baseline early-water must scan all neighbors");
        require(newEarlyWater == oldEarlyWater, "unbounded early-water scan must preserve max-fold call count old=" + oldEarlyWater + " new=" + newEarlyWater);
        System.out.println("plan calls: non-soil old=" + oldNonSoil + " new=" + newNonSoil
                + ", early-water old=" + oldEarlyWater + " new=" + newEarlyWater);
    }

    private static int originalCalls(Engine engine, BlockState existing) {
        Engine copy = engine.copy(); original(new Level(6, existing), POS, AIR, copy); return copy.calls;
    }
    private static int productionCalls(Engine engine, BlockState existing) {
        Engine copy = engine.copy(); StreamsWorldgen.setEngine(copy); StreamsShallowBanks.preserve(new Level(6, existing), POS, AIR); return copy.calls;
    }

    private static void testParallelEngines() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Void>> work = new ArrayList<>();
            for (int i = 0; i < 96; i++) {
                final int value = i;
                work.add(() -> { Engine engine = value % 2 == 0 ? engine(6, baseCentre(), -8) : new AlternateEngine(6, baseCentre(), -8); BlockState block = value % 3 == 0 ? STONE : DIRT; boolean expected = original(new Level(6, block), POS, AIR, engine.copy()); StreamsWorldgen.setEngine(engine); boolean actual = StreamsShallowBanks.preserve(new Level(6, block), POS, AIR); require(actual == expected, "parallel worker " + value); return null; });
            }
            long before = StreamsShallowBanks.retainedWrites();
            int expectedRetained = 64;
            for (Future<Void> completed : workers.invokeAll(work)) completed.get();
            long delta = StreamsShallowBanks.retainedWrites() - before;
            require(delta == expectedRetained, "parallel retained writes expected=" + expectedRetained + " actual=" + delta);
            System.out.println("parallel results: workers=96 retained=" + delta);
        } finally { workers.shutdownNow(); }
    }

    private static void benchmark() {
        long oldMedian = median(false), newMedian = median(true);
        int oldChecksum = checksum(false), newChecksum = checksum(true);
        require(oldChecksum == newChecksum, "benchmark checksum differs old=" + oldChecksum + " new=" + newChecksum);
        System.out.println("benchmark median ns: original=" + oldMedian + " optimized=" + newMedian + " checksum=" + oldChecksum + " (informational)");
    }
    private static int checksum(boolean optimized) { int sum=0; Engine e=engine(6, baseCentre(), -8); for (int i=0;i<10_000;i++) sum = 31 * sum + run(optimized, e); return sum; }
    private static long median(boolean optimized) {
        long[] samples = new long[7];
        for (int s = 0; s < samples.length; s++) { Engine e = engine(6, baseCentre(), -8); for (int warm = 0; warm < 5_000; warm++) run(optimized, e); long start = System.nanoTime(); for (int i = 0; i < 20_000; i++) run(optimized, e); samples[s] = System.nanoTime() - start; }
        java.util.Arrays.sort(samples); return samples[samples.length / 2];
    }
    private static int run(boolean optimized, Engine e) { Engine copy=e.copy(); boolean result; StreamsWorldgen.setEngine(copy); if (optimized) result=StreamsShallowBanks.preserve(new Level(6, DIRT), POS, AIR); else result=legacyReflective(new Level(6, DIRT), POS, AIR); return (result ? 1 : 0) + copy.calls; }

    /** Exact former reflective hot path retained only as a benchmark reference. */
    private static boolean legacyReflective(WorldGenLevel level, BlockPos pos, BlockState replacement) {
        if (!replacement.isAir() || ((Number) level.getLevel()).intValue() < 6) return false;
        try {
            Object engine = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                    .getMethod("engine", WorldGenLevel.class).invoke(null, level);
            Object config = engine.getClass().getMethod("config").invoke(engine);
            int version = ((Number) config).intValue();
            if (version < 6 || version < 9 && (Boolean) engine.getClass().getMethod("lakeNearChunk", int.class, int.class, int.class)
                    .invoke(engine, pos.getX() & ~15, pos.getZ() & ~15, 8)) return false;
            java.lang.reflect.Method plan = engine.getClass().getMethod("plan", int.class, int.class);
            Object centre = plan.invoke(engine, pos.getX(), pos.getZ());
            Class<?> column = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
            java.lang.reflect.Method channel = column.getMethod("inChannel"), corridor = column.getMethod("inCorridor"),
                    width = column.getMethod("width"), distance = column.getMethod("distance"), surface = column.getMethod("waterSurfaceY"),
                    source = column.getMethod("source"), terminal = column.getMethod("terminal");
            double halfWidth = ((Number) width.invoke(centre)).doubleValue(), lateralDistance = ((Number) distance.invoke(centre)).doubleValue();
            if ((Boolean) channel.invoke(centre) || !(Boolean) corridor.invoke(centre) || (Boolean) source.invoke(centre)
                    || (Boolean) terminal.invoke(centre) || !Double.isFinite(halfWidth) || !Double.isFinite(lateralDistance)
                    || !(halfWidth > 0 && halfWidth < 4) || lateralDistance > halfWidth + 3) return false;
            if (version >= 9 && ((StreamsBankLakeMask.ShallowBankLakeProbe) engine).nearMask(pos.getX(), pos.getZ(), 2)) return false;
            boolean nearbyChannel = false; int safeWater = Integer.MIN_VALUE;
            for (int dx=-2; dx<=2; dx++) for (int dz=-2; dz<=2; dz++) { Object neighbor=plan.invoke(engine,pos.getX()+dx,pos.getZ()+dz); if((Boolean)channel.invoke(neighbor)) { nearbyChannel=true; safeWater=Math.max(safeWater,((Number)surface.invoke(neighbor)).intValue()); } }
            if (!nearbyChannel) return false;
            boolean retained = pos.getY() <= safeWater + 1 && drySoil(level.getBlockState(pos));
            if (retained) LEGACY_RETAINED_WRITES.incrementAndGet();
            return retained;
        } catch (ReflectiveOperationException failure) { throw new AssertionError("legacy reference reflection", failure); }
    }
    private static boolean original(Level level, BlockPos pos, BlockState replacement, Engine engine) {
        if (!replacement.isAir() || level.version < 6) return false;
        int version = engine.version;
        if (version < 6 || (version < 9 && engine.lakeNearChunk(pos.getX() & ~15, pos.getZ() & ~15, 8))) return false;
        ColumnPlan centre = engine.plan(pos.getX(), pos.getZ());
        double width=centre.width(), distance=centre.distance();
        if (centre.inChannel() || !centre.inCorridor() || centre.source() || centre.terminal() || !Double.isFinite(width) || !Double.isFinite(distance) || !(width > 0 && width < 4) || distance > width + 3) return false;
        if (version >= 9 && engine.nearMask(pos.getX(), pos.getZ(), 2)) return false;
        boolean nearby=false; int safe=Integer.MIN_VALUE;
        for (int dx=-2; dx<=2; dx++) for (int dz=-2; dz<=2; dz++) { ColumnPlan neighbor=engine.plan(pos.getX()+dx,pos.getZ()+dz); if(neighbor.inChannel()) { nearby=true; safe=Math.max(safe,neighbor.waterSurfaceY()); } }
        return nearby && pos.getY() <= safe + 1 && drySoil(level.existing);
    }
    private static boolean drySoil(BlockState s) { return s.getFluidState().isEmpty() && (s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.DIRT)||s.is(Blocks.COARSE_DIRT)||s.is(Blocks.ROOTED_DIRT)||s.is(Blocks.PODZOL)||s.is(Blocks.MUD)); }
    private static ColumnPlan baseCentre() { return plan(false, true, -10, 2, 0, false, false); }
    private static ColumnPlan plan(boolean channel, boolean corridor, int surface, double width, double distance, boolean source, boolean terminal) { return new ColumnPlan(channel,corridor,surface,width,distance,source,terminal); }
    private static Engine engine(int version, ColumnPlan centre, int surface) { return new Engine(version, centre, plan(true,true,surface,2,0,false,false)); }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    private record Case(String name, int worldVersion, int configVersion, BlockState existing,
                        BlockState replacement, Engine engine, boolean expected, BlockPos pos) {
        private Case(String name, int worldVersion, int configVersion, BlockState existing,
                     BlockState replacement, Engine engine, boolean expected) {
            this(name, worldVersion, configVersion, existing, replacement, engine, expected, POS);
        }
    }
    private static final class Level implements WorldGenLevel {
        private final int version;
        private final BlockState existing;

        private Level(int version, BlockState existing) {
            this.version = version;
            this.existing = existing;
        }

        @Override
        public Object getLevel() {
            return version;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return existing;
        }
    }

    public static class Engine extends RiverEngine implements StreamsBankLakeMask.ShallowBankLakeProbe {
        final int version;
        final ColumnPlan centre;
        final ColumnPlan defaultNeighbor;
        boolean lakeChunk;
        boolean mask;
        int calls;
        java.util.Map<Long, ColumnPlan> overrides = new java.util.HashMap<>();

        Engine(int version, ColumnPlan centre, ColumnPlan neighbor) {
            this.version = version;
            this.centre = centre;
            this.defaultNeighbor = neighbor;
        }

        @Override
        public Object config() {
            return version;
        }

        @Override
        public boolean lakeNearChunk(int x, int z, int radius) {
            return lakeChunk;
        }

        @Override
        public ColumnPlan plan(int x, int z) {
            calls++;
            if (x == POS.getX() && z == POS.getZ()) return centre;
            return overrides.getOrDefault(key(x - POS.getX(), z - POS.getZ()), defaultNeighbor);
        }

        @Override
        public boolean nearMask(int x, int z, int radius) {
            return mask;
        }

        Engine lakeChunk(boolean value) {
            lakeChunk = value;
            return this;
        }

        Engine mask(boolean value) {
            mask = value;
            return this;
        }

        Engine neighbors(ColumnPlan value) {
            overrides.clear();
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) overrides.put(key(x, z), value);
            return this;
        }

        Engine neighbor(int x, int z, ColumnPlan value) {
            overrides.put(key(x, z), value);
            return this;
        }

        void reset() {
            calls = 0;
        }

        Engine copy() {
            Engine result = new Engine(version, centre, defaultNeighbor);
            result.lakeChunk = lakeChunk;
            result.mask = mask;
            result.overrides = new java.util.HashMap<>(overrides);
            return result;
        }
    }

    public static final class AlternateEngine extends Engine {
        AlternateEngine(int version, ColumnPlan centre, int surface) {
            super(version, centre, ShallowBanksHarness.plan(true, true, surface, 2, 0, false, false));
        }
    }

    private static long key(int x,int z){return ((long)x<<32) ^ (z & 0xffffffffL);}
}