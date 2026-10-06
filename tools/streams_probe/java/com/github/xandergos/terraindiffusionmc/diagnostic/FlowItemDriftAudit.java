package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.tags.FluidTags;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Opt-in server-only test of the actual FluidState flow and item displacement.
 * Fixed positions deliberately are not selected by their current vector, so a
 * broken flat-river grid cannot be hidden by a successful-cell scan.
 */
final class FlowItemDriftAudit {
    private static final Cell[] DEFAULT_CELLS = {
            // C2 saved-FULL water samples, independently selected before this probe runs.
            new Cell(-3681, 108, 14, "junction"),
            new Cell(-2961, 93, -502, "wide_confluence")
    };
    private static final int WARM_TICKS = 100;
    private static final int DRIFT_TICKS = 100;
    private static final double MIN_PROJECTION = .25;

    private FlowItemDriftAudit() { }

    static String configuredReceipt() {
        return json("item_flow_config", "status", "configured_before_flow_lookup", "cells", cellsText(cells()));
    }

    static String run(MinecraftServer server, ServerLevel level, int timeoutSeconds) throws Exception {
        List<Cell> cells = cells();
        List<ChunkPos> forced = new ArrayList<>();
        List<ItemEntity> spawned = new ArrayList<>();
        List<Sample> initial = new ArrayList<>();
        List<Sample> warmed = new ArrayList<>();
        List<Drift> drifts = new ArrayList<>();
        String status = "inconclusive";
        String reason = "";
        Exception primaryFailure = null;
        long warmStart = -1, warmEnd = -1, driftStart = -1, driftEnd = -1;
        try {
            for (Cell cell : cells) {
                Sample sample = submit(server, () -> sample(level, cell));
                initial.add(sample);
                if (sample.loaded && submit(server, () -> level.setChunkForced(cell.x >> 4, cell.z >> 4, true)))
                    forced.add(new ChunkPos(cell.x >> 4, cell.z >> 4));
            }
            if (initial.stream().anyMatch(s -> !s.loaded || !s.water)) {
                reason = "fixed_cell_not_loaded_full_water";
            } else {
                warmStart = submit(server, server::getTickCount);
                if (!awaitTicks(server, warmStart + WARM_TICKS, timeoutSeconds)) {
                    reason = "grid_warmup_tick_timeout";
                } else {
                    warmEnd = submit(server, server::getTickCount);
                    for (Cell cell : cells) warmed.add(submit(server, () -> sample(level, cell)));
                    if (warmed.stream().anyMatch(s -> !s.water || s.flowLength() <= 1.0e-5)) {
                        reason = "grid_warmup_zero_or_inactive_current";
                    } else {
                        for (int i = 0; i < cells.size(); i++) {
                            Cell cell = cells.get(i);
                            Sample flow = warmed.get(i);
                            Drift drift = submit(server, () -> {
                                ItemEntity item = spawn(level, cell);
                                return new Drift(cell, flow, item, item.position());
                            });
                            spawned.add(drift.item);
                            drifts.add(drift);
                        }
                        driftStart = submit(server, server::getTickCount);
                        if (!awaitTicks(server, driftStart + DRIFT_TICKS, timeoutSeconds)) {
                            reason = "item_drift_tick_timeout";
                        } else {
                            driftEnd = submit(server, server::getTickCount);
                            boolean pass = true;
                            for (Drift drift : drifts) {
                                submit(server, () -> { finish(level, drift); return null; });
                                pass &= drift.endWater && drift.projection > MIN_PROJECTION;
                            }
                            status = pass ? "passed" : "failed";
                            if (!pass) reason = "item_stuck_or_not_downstream";
                        }
                    }
                }
            }
        } catch (Exception failure) {
            primaryFailure = failure;
            status = "failed";
            reason = "probe_exception_" + failure.getClass().getSimpleName();
            throw failure;
        } finally {
            Exception cleanupFailure = null;
            for (ItemEntity item : spawned) {
                try { submit(server, () -> { if (!item.isRemoved()) item.discard(); return null; }); }
                catch (Exception failure) { cleanupFailure = append(cleanupFailure, failure); }
            }
            for (ChunkPos chunk : forced) {
                try { submit(server, () -> { level.setChunkForced(chunk.x, chunk.z, false); return null; }); }
                catch (Exception failure) { cleanupFailure = append(cleanupFailure, failure); }
            }
            if (cleanupFailure != null) {
                status = "failed";
                reason = "cleanup_failure";
            }
            Exception finalFailure = cleanupFailure;
            try {
                write(level, cells, initial, warmed, drifts, status, reason, warmStart, warmEnd, driftStart, driftEnd);
            } catch (IOException failure) {
                finalFailure = append(finalFailure, failure);
            }
            if (finalFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(finalFailure);
                else throw finalFailure;
            }
        }
        return json("item_flow_audit", "status", status, "reason", reason, "cells", cellsText(cells),
                "warmTicks", WARM_TICKS, "driftTicks", DRIFT_TICKS, "minimumDownstreamProjection", MIN_PROJECTION,
                "report", "td-item-flow-audit.json");
    }

    private static ItemEntity spawn(ServerLevel level, Cell cell) {
        // Normal gravity is intentional: this exercises vanilla item buoyancy and water push.
        ItemEntity item = new ItemEntity(level, cell.x + .5, cell.y + .3, cell.z + .5, new ItemStack(Items.STICK));
        item.setPickUpDelay(6000);
        item.setDeltaMovement(Vec3.ZERO);
        level.addFreshEntity(item);
        return item;
    }

    private static void finish(ServerLevel level, Drift drift) {
        drift.end = drift.item.position();
        drift.endWater = drift.item.isInWater();
        Vec3 delta = drift.end.subtract(drift.start);
        drift.dx = delta.x; drift.dy = delta.y; drift.dz = delta.z;
        double length = drift.flow.flowLength();
        drift.projection = delta.x * drift.flow.flowX / length + delta.z * drift.flow.flowZ / length;
    }

    private static Sample sample(ServerLevel level, Cell cell) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cell.x >> 4, cell.z >> 4);
        // getChunkNow yields a LevelChunk only for an already loaded full chunk; it never generates one.
        if (chunk == null) return new Sample(cell, false, false, false, "", 0, 0, 0);
        BlockPos pos = new BlockPos(cell.x, cell.y, cell.z);
        FluidState state = level.getFluidState(pos);
        Vec3 flow = state.getFlow(level, pos);
        return new Sample(cell, true, state.is(FluidTags.WATER), state.isSource(), String.valueOf(BuiltInRegistries.FLUID.getKey(state.getType())), flow.x, flow.y, flow.z);
    }

    private static boolean awaitTicks(MinecraftServer server, long target, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.min(90, Math.max(15, timeoutSeconds)));
        while (System.nanoTime() < deadline) {
            if (submit(server, server::getTickCount) >= target) return true;
            Thread.sleep(50L);
        }
        return false;
    }

    private static <T> T submit(MinecraftServer server, Supplier<T> task) throws Exception {
        return server.submit(task).get(15, TimeUnit.SECONDS);
    }

    private static List<Cell> cells() {
        String raw = System.getProperty("td.streamsProbe.itemFlowCells", "").trim();
        if (raw.isEmpty()) return List.of(DEFAULT_CELLS);
        List<Cell> parsed = new ArrayList<>();
        for (String part : raw.split(";")) {
            String[] fields = part.trim().split(",");
            if (fields.length != 3) throw new IllegalArgumentException("itemFlowCells needs x,y,z;x,y,z");
            parsed.add(new Cell(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), "cli_" + parsed.size()));
        }
        if (parsed.size() != 2) throw new IllegalArgumentException("itemFlowCells needs exactly two fixed cells");
        return List.copyOf(parsed);
    }

    private static void write(ServerLevel level, List<Cell> cells, List<Sample> initial, List<Sample> warmed, List<Drift> drifts, String status, String reason,
                              long warmStart, long warmEnd, long driftStart, long driftEnd) throws IOException {
        Path output = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("td-item-flow-audit.json");
        JsonObject report = new JsonObject();
        report.addProperty("status", status);
        report.addProperty("reason", reason);
        report.add("configuredCells", cellsJson(cells));
        report.add("initial", samplesJson(initial));
        report.add("afterWarmup", samplesJson(warmed));
        report.add("drifts", driftsJson(drifts));
        report.addProperty("warmStartTick", warmStart);
        report.addProperty("warmEndTick", warmEnd);
        report.addProperty("driftStartTick", driftStart);
        report.addProperty("driftEndTick", driftEnd);
        report.addProperty("warmTicks", WARM_TICKS);
        report.addProperty("driftTicks", DRIFT_TICKS);
        report.addProperty("minimumDownstreamProjection", MIN_PROJECTION);
        Files.writeString(output, report + "\n");
    }

    private static Exception append(Exception prior, Exception added) {
        if (prior == null) return added;
        prior.addSuppressed(added);
        return prior;
    }

    private static JsonArray cellsJson(List<Cell> cells) {
        JsonArray array = new JsonArray();
        for (Cell cell : cells) {
            JsonObject value = new JsonObject();
            value.addProperty("label", cell.label);
            value.addProperty("x", cell.x);
            value.addProperty("y", cell.y);
            value.addProperty("z", cell.z);
            array.add(value);
        }
        return array;
    }

    private static JsonArray samplesJson(List<Sample> samples) {
        JsonArray array = new JsonArray();
        for (Sample sample : samples) {
            JsonObject value = new JsonObject();
            value.addProperty("label", sample.cell.label);
            value.addProperty("x", sample.cell.x);
            value.addProperty("y", sample.cell.y);
            value.addProperty("z", sample.cell.z);
            value.addProperty("loadedFull", sample.loaded);
            value.addProperty("water", sample.water);
            value.addProperty("source", sample.source);
            value.addProperty("fluid", sample.fluid);
            value.addProperty("flowX", sample.flowX);
            value.addProperty("flowY", sample.flowY);
            value.addProperty("flowZ", sample.flowZ);
            value.addProperty("horizontalFlowLength", sample.flowLength());
            array.add(value);
        }
        return array;
    }

    private static JsonArray driftsJson(List<Drift> drifts) {
        JsonArray array = new JsonArray();
        for (Drift drift : drifts) {
            JsonObject value = new JsonObject();
            value.addProperty("label", drift.cell.label);
            value.add("flow", samplesJson(List.of(drift.flow)).get(0));
            value.add("start", vectorJson(drift.start));
            value.add("end", vectorJson(drift.end));
            value.addProperty("deltaX", drift.dx);
            value.addProperty("deltaY", drift.dy);
            value.addProperty("deltaZ", drift.dz);
            value.addProperty("downstreamProjection", drift.projection);
            value.addProperty("endInWater", drift.endWater);
            array.add(value);
        }
        return array;
    }

    private static JsonObject vectorJson(Vec3 vector) {
        JsonObject value = new JsonObject();
        value.addProperty("x", vector.x);
        value.addProperty("y", vector.y);
        value.addProperty("z", vector.z);
        return value;
    }

    private static String cellsText(List<Cell> cells) { return cells.stream().map(c -> c.label + "=" + c.x + "," + c.y + "," + c.z).reduce((a, b) -> a + ";" + b).orElse(""); }
    private static String json(String event, Object... kv) { StringBuilder b = new StringBuilder("{\"event\":\"").append(event).append('\"'); for (int i=0;i<kv.length;i+=2) b.append(",\"").append(kv[i]).append("\":\"").append(esc(String.valueOf(kv[i+1]))).append('\"'); return b.append('}').toString(); }
    private static String esc(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }

    private record Cell(int x, int y, int z, String label) { }
    private record Sample(Cell cell, boolean loaded, boolean water, boolean source, String fluid, double flowX, double flowY, double flowZ) {
        double flowLength() { return Math.hypot(flowX, flowZ); }
    }
    private static final class Drift {
        final Cell cell; final Sample flow; final ItemEntity item; final Vec3 start;
        Vec3 end = Vec3.ZERO; double dx, dy, dz, projection; boolean endWater;
        Drift(Cell cell, Sample flow, ItemEntity item, Vec3 start) { this.cell=cell; this.flow=flow; this.item=item; this.start=start; }
    }
}
