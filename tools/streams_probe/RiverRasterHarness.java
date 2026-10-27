package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalRiverAdapter.RasterConfig;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsRiverRaster;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.world.level.WorldGenLevel;
import dev.streamsreflowing.worldgen.StreamsWorldgen;

/** Differential checks for the uncached reflection semantics of StreamsRiverRaster. */
public final class RiverRasterHarness {
    public record Column(boolean inCorridor, double width, double distance, int waterSurfaceY,
                         int bedY, double lateral, boolean inChannel, boolean source,
                         boolean terminal, double flowDirX, double flowDirZ) { }
    public record Lake(int level) { }
    public static class Engine {
        private final RasterConfig config; private final Lake lake;
        public Engine(RasterConfig config, Lake lake) { this.config=config; this.lake=lake; }
        public RasterConfig config() { return config; }
        public Lake lakeNear(int x, int z, int radius) { return lake; }
        public Column plan(int x, int z) { return COLUMN; }
        public boolean lakeNearChunk(int x, int z, int radius) { return false; }
    }
    public static class PoolEngine extends Engine {
        private final Column centre; private final boolean lakeChunk;
        public PoolEngine(RasterConfig c, Column centre, boolean lakeChunk) { super(c, null); this.centre=centre; this.lakeChunk=lakeChunk; }
        @Override public Column plan(int x, int z) { return centre; }
        @Override public boolean lakeNearChunk(int x, int z, int radius) { return lakeChunk; }
    }
    public static final class BrokenPoolEngine extends PoolEngine {
        public BrokenPoolEngine(RasterConfig c, Column centre) { super(c, centre, false); }
        @Override public Column plan(int x, int z) {
            Column value = super.plan(x, z);
            return x == 0 && z == 0 ? value : new Column(value.inCorridor(), value.width(), value.distance(),
                    value.waterSurfaceY(), value.bedY(), value.lateral(), false, value.source(), value.terminal(),
                    value.flowDirX(), value.flowDirZ());
        }
    }
    private enum Level implements WorldGenLevel { INSTANCE }
    public static final class DerivedEngine extends Engine { public DerivedEngine(RasterConfig c, Lake l) { super(c,l); } }
    private static final Column COLUMN = new Column(true, 2, 0, 62, 55, 3, true, false, false, 1, 0);
    private RiverRasterHarness() { }
    public static void main(String[] args) throws Exception {
        Column corridor = new Column(true, .5, .6, 62, 55, 2, false, false, false, 1, 0);
        compare(new Engine(new RasterConfig(3, 4), new Lake(64)), corridor, "pre-v4");
        compare(new Engine(new RasterConfig(4, 4), new Lake(64)), new Column(true, 0, 0, 62, 55, 2, false, false, false, 1, 0), "zero-width");
        compare(new Engine(new RasterConfig(4, 4), new Lake(64)), new Column(true, Double.NaN, 0, 62, 55, 2, false, false, false, 1, 0), "nan-width");
        compare(new Engine(new RasterConfig(4, 4), new Lake(64)), corridor, "sub-voxel-channel");
        compare(new Engine(new RasterConfig(4, 4), new Lake(65)), new Column(true, 2, 3, 62, 55, 2, false, false, false, 1, 0), "dry-bank-lake-accepted");
        compare(new Engine(new RasterConfig(4, 2), new Lake(66)), new Column(true, 2, 3, 62, 55, 2, false, false, false, 1, 0), "dry-bank-lake-rejected");
        compare(new DerivedEngine(new RasterConfig(4, 4), new Lake(65)), corridor, "derived-engine-metadata");
        require(StreamsRiverRaster.resolve(new Engine(new RasterConfig(4, 4), new Lake(65)), null, 0, 0) == null, "null guard");
        verifyPoolGuards();
        require(!StreamsRiverRaster.connectedCrossing(2, 2, (x,z) -> !((x == 0 && z == 1) || (x == 1 && z == 0))), "diagonal corner guard");
        require(!StreamsRiverRaster.connectedCrossing(2, 0, (x,z) -> x != 1), "crossing gap guard");
        require(StreamsRiverRaster.connectedCrossing(2, 2, (x,z) -> true), "connected crossing");
        System.out.println("RiverRasterHarness: PASS");
    }
    private static void verifyPoolGuards() {
        Column flow = new Column(true, 2, 0, 62, 55, 2, true, false, false, 1, 0);
        StreamsWorldgen.current = new PoolEngine(new RasterConfig(4, 4), flow, false);
        require(StreamsRiverRaster.poolGrade(Level.INSTANCE, 0, 0, "other", 5, 4) == 4, "pool reason guard");
        require(StreamsRiverRaster.poolGrade(Level.INSTANCE, 0, 0, "junction pool (neighbour at r=2 side=1)", 5, 6) == 6, "pool monotonic guard");
        StreamsWorldgen.current = new PoolEngine(new RasterConfig(3, 4), flow, false);
        require(StreamsRiverRaster.poolGrade(Level.INSTANCE, 0, 0, "junction pool (neighbour at r=2 side=1)", 5, 4) == 4, "pool version guard");
        StreamsWorldgen.current = new PoolEngine(new RasterConfig(4, 4), new Column(true, 2, 0, 62, 55, 2, true, true, false, 1, 0), false);
        require(StreamsRiverRaster.poolGrade(Level.INSTANCE, 0, 0, "junction pool (neighbour at r=2 side=1)", 5, 4) == 4, "pool source guard");
        StreamsWorldgen.current = new PoolEngine(new RasterConfig(4, 4), flow, true);
        require(StreamsRiverRaster.poolGrade(Level.INSTANCE, 0, 0, "junction pool (neighbour at r=2 side=1)", 5, 4) == 4, "pool lake guard");
        StreamsWorldgen.current = new PoolEngine(new RasterConfig(4, 4), new Column(true, 2, 0, 62, 55, 2, true, false, false, 0, 0), false);
        require(StreamsRiverRaster.poolGrade(Level.INSTANCE, 0, 0, "junction pool (neighbour at r=2 side=1)", 5, 4) == 4, "pool zero-flow guard");
        StreamsWorldgen.current = new PoolEngine(new RasterConfig(4, 4), flow, false);
        require(StreamsRiverRaster.poolGrade(Level.INSTANCE, 0, 0, "junction pool (neighbour at r=2 side=1)", 5, 4) == 4, "pool connected crossing");
        StreamsWorldgen.current = new BrokenPoolEngine(new RasterConfig(4, 4), flow);
        require(StreamsRiverRaster.poolGrade(Level.INSTANCE, 0, 0, "junction pool (neighbour at r=2 side=1)", 5, 4) == 5, "pool disconnected crossing");
    }
    private static void compare(Engine engine, Column column, String label) throws Exception {
        Object expected = originalResolve(engine, column, 17, -9);
        Object actual = StreamsRiverRaster.resolve(engine, column, 17, -9);
        require(expected.equals(actual), label + " changed result: " + expected + " != " + actual);
    }
    private static Object originalResolve(Object engine, Object column, int x, int z) throws Exception {
        if (column == null || !(Boolean) column.getClass().getMethod("inCorridor").invoke(column)) return column;
        Object config = engine.getClass().getMethod("config").invoke(engine);
        if (((RasterConfig) config).version() < 4) return column;
        double width = ((Number) column.getClass().getMethod("width").invoke(column)).doubleValue();
        if (!(width > 0)) return column;
        double resolvedWidth = Math.max(width, .71);
        boolean channel = ((Number) column.getClass().getMethod("distance").invoke(column)).doubleValue() <= resolvedWidth;
        int water = ((Number) column.getClass().getMethod("waterSurfaceY").invoke(column)).intValue(), bankReference = water;
        if (!channel) {
            Object lake = engine.getClass().getMethod("lakeNear", int.class, int.class, int.class).invoke(engine,x,z,8);
            if (lake != null) {
                int lakeLevel = ((Number) lake.getClass().getMethod("level").invoke(lake)).intValue();
                if (lakeLevel > water && lakeLevel-water <= Math.ceil(((RasterConfig) config).ditchInset())) bankReference=lakeLevel;
            }
        }
        if (resolvedWidth == width && bankReference == water) return column;
        Column value=(Column)column;
        return new Column(value.inCorridor(), resolvedWidth, value.distance(), bankReference, value.bedY()+bankReference-water,
                value.lateral()*width/resolvedWidth, channel, value.source(), value.terminal(), value.flowDirX(), value.flowDirZ());
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}


