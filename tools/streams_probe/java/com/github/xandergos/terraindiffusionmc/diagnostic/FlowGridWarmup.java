package com.github.xandergos.terraindiffusionmc.diagnostic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Opt-in server probe warm-up for Streams Reflowing's vanilla-water physics grid. */
final class FlowGridWarmup {
    private FlowGridWarmup() { }

    static Result warm(ServerLevel level, List<ChunkPos> chunks) throws Exception {
        Class<?> worldgen = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen");
        Object engine = worldgen.getMethod("engine", WorldGenLevel.class).invoke(null, level);
        if (engine == null) throw new IllegalStateException("Native river engine unavailable for flow-grid warm-up");
        Class<?> fields = Class.forName("dev.streamsreflowing.worldgen.RiverFlowFields");
        Object classifier = fields.getMethod("classifierFor", ServerLevel.class).invoke(null, level);
        Object orientation = fields.getMethod("orientationFor", ServerLevel.class).invoke(null, level);
        Class<?> grid = Class.forName("dev.streamsreflowing.flow.StreamFlowGrid");
        Method getOrBuild = staticMethod(grid, "getOrBuild", 7);
        Method peek = grid.getMethod("peek", int.class, int.class);
        boolean flowVanillaRivers = configBoolean("flowVanillaRivers");
        List<String> chunkLengths = new ArrayList<>();
        int nonEmpty = 0;
        int zeroOrAbsent = 0;
        for (ChunkPos chunk : chunks) {
            getOrBuild.invoke(null, level, engine, classifier, orientation, flowVanillaRivers, chunk.x, chunk.z);
            byte[] cached = (byte[]) peek.invoke(null, chunk.x, chunk.z);
            int length = cached == null ? -1 : cached.length;
            if (length > 0) nonEmpty++; else zeroOrAbsent++;
            chunkLengths.add(chunk.x + "," + chunk.z + ":" + length);
        }
        return new Result(chunks.size(), nonEmpty, zeroOrAbsent, chunkLengths,
                configBoolean("vanillaWaterFlow"), flowVanillaRivers, configBoolean("spillOverWater"),
                configBoolean("spillOnStreamsOnly"), configBoolean("forceBakedFluid"));
    }

    static Active verifyFixedChannel(ServerLevel level, RiverContainmentAudit.Snapshot snapshot, int fixedX, int fixedZ) throws Exception {
        Class<?> worldgen = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen");
        Object engine = worldgen.getMethod("engineIfPresent", ServerLevel.class).invoke(null, level);
        if (engine == null) throw new IllegalStateException("Native river engine unavailable while verifying flow grid");
        Method plan = engine.getClass().getMethod("plan", int.class, int.class);
        Object column = plan.invoke(engine, fixedX, fixedZ);
        boolean inChannel = (Boolean) column.getClass().getMethod("inChannel").invoke(column);
        Class<?> grid = Class.forName("dev.streamsreflowing.flow.StreamFlowGrid");
        Method angle = staticMethod(grid, "angleForPhysics", 4);
        int waterAtFixedColumn = 0;
        int activeAtFixedColumn = 0;
        List<String> samples = new ArrayList<>();
        for (long key : snapshot.waterPositions()) {
            BlockPos pos = BlockPos.of(key);
            if (pos.getX() != fixedX || pos.getZ() != fixedZ) continue;
            waterAtFixedColumn++;
            int value = ((Number) angle.invoke(null, level, pos.getX(), pos.getY(), pos.getZ())).intValue();
            if (value != -1) activeAtFixedColumn++;
            samples.add(pos.getX() + "," + pos.getY() + "," + pos.getZ() + ":" + value);
        }
        return new Active(fixedX, fixedZ, inChannel, waterAtFixedColumn, activeAtFixedColumn, samples);
    }

    private static boolean configBoolean(String name) throws Exception {
        Class<?> config = Class.forName("dev.streamsreflowing.neoforge.StreamsConfigNeo");
        return (Boolean) config.getMethod(name).invoke(null);
    }

    private static Method staticMethod(Class<?> type, String name, int parameters) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters && Modifier.isStatic(method.getModifiers())) return method;
        }
        throw new NoSuchMethodException(type.getName() + "." + name + " with " + parameters + " parameters");
    }

    record Result(int requestedChunks, int chunksWithGrid, int chunksZeroOrAbsent, List<String> cacheByteLengths,
                  boolean vanillaWaterFlow, boolean flowVanillaRivers, boolean spillOverWater,
                  boolean spillOnStreamsOnly, boolean forceBakedFluid) {
        String json() {
            return "{\"audit\":\"flow_grid_warmup\",\"requestedChunks\":" + requestedChunks
                    + ",\"chunksWithGrid\":" + chunksWithGrid + ",\"chunksZeroOrAbsent\":" + chunksZeroOrAbsent
                    + ",\"cacheByteLengths\":" + array(cacheByteLengths) + ",\"vanillaWaterFlow\":" + vanillaWaterFlow
                    + ",\"flowVanillaRivers\":" + flowVanillaRivers + ",\"spillOverWater\":" + spillOverWater
                    + ",\"spillOnStreamsOnly\":" + spillOnStreamsOnly + ",\"forceBakedFluid\":" + forceBakedFluid + "}";
        }
    }

    record Active(int fixedX, int fixedZ, boolean inChannel, int waterAtFixedColumn, int physicsActiveAtFixedColumn,
                  List<String> samples) {
        boolean active() { return inChannel && waterAtFixedColumn > 0 && physicsActiveAtFixedColumn > 0; }
        String json() {
            return "{\"audit\":\"flow_grid_physics\",\"status\":\"" + (active() ? "passed" : "failed")
                    + "\",\"fixedX\":" + fixedX + ",\"fixedZ\":" + fixedZ + ",\"inChannel\":" + inChannel
                    + ",\"waterAtFixedColumn\":" + waterAtFixedColumn + ",\"physicsActiveAtFixedColumn\":" + physicsActiveAtFixedColumn
                    + ",\"samples\":" + array(samples) + "}";
        }
    }

    private static String array(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            out.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return out.append(']').toString();
    }
}
