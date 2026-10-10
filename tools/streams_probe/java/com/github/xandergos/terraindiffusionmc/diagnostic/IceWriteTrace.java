package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded write-attempt tracing for a copied diagnostic world, with no world queries or edits. */
public final class IceWriteTrace {
    private static final boolean ENABLED = Boolean.getBoolean("td.streamsProbe.iceTrace");
    private static final boolean BANKS = Boolean.getBoolean("td.streamsProbe.bankWriteTrace");
    private static final AtomicInteger COUNT = new AtomicInteger();
    private IceWriteTrace() { }

    public static void record(ChunkAccess chunk, BlockPos pos, BlockState state) {
        if (!ENABLED && !BANKS) return;
        int x = pos.getX(), z = pos.getZ();
        boolean bank = BANKS && z == Integer.getInteger("td.streamsProbe.bankTraceZ", 1017)
                && Math.abs((long) x - Integer.getInteger("td.streamsProbe.bankTraceX", 606)) <= 8
                && pos.getY() >= Integer.getInteger("td.streamsProbe.bankTraceMinY", 72)
                && pos.getY() <= Integer.getInteger("td.streamsProbe.bankTraceMaxY", 80);
        if (!bank && (!ENABLED || !state.is(Blocks.ICE))) return;
        int centerX = Integer.getInteger("td.streamsProbe.iceTraceX", 985);
        int centerZ = Integer.getInteger("td.streamsProbe.iceTraceZ", 1007);
        int radius = Integer.getInteger("td.streamsProbe.iceTraceRadius", 96);
        if (!bank && (Math.abs((long) x - centerX) > radius || Math.abs((long) z - centerZ) > radius)) return;
        BlockState previous = chunk.getBlockState(pos);
        if (previous.equals(state)) return;
        int sequence = COUNT.incrementAndGet();
        if (sequence > Integer.getInteger("td.streamsProbe.iceTraceLimit", 600)) return;
        JsonObject row = new JsonObject();
        row.addProperty("event", bank ? "bank_write_trace" : "ice_write_trace");
        row.addProperty("sequence", sequence);
        row.addProperty("x", x); row.addProperty("y", pos.getY()); row.addProperty("z", z);
        row.addProperty("chunkType", chunk.getClass().getName());
        row.addProperty("thread", Thread.currentThread().getName());
        row.addProperty("previous", previous.toString());
        row.addProperty("next", state.toString());
        row.addProperty("above", chunk.getBlockState(pos.above()).toString());
        row.addProperty("below", chunk.getBlockState(pos.below()).toString());
        try {
            var biome = chunk.getNoiseBiome(x >> 2, pos.getY() >> 2, z >> 2);
            row.addProperty("storedQuartBiome", biome.unwrapKey().map(k -> k.location().toString()).orElse("unknown"));
            row.addProperty("baseTemperature", biome.value().getBaseTemperature());
        } catch (RuntimeException exception) {
            row.addProperty("biomeReadError", exception.toString());
        }
        JsonArray stack = new JsonArray();
        StackWalker.getInstance().walk(frames -> frames.limit(36).toList())
                .forEach(frame -> stack.add(frame.toString()));
        row.add("stack", stack);
        System.out.println(row);
    }
}
