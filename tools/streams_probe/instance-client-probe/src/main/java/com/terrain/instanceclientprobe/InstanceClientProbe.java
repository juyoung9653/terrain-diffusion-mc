package com.terrain.instanceclientprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Method;

/** Client-only audit. It cannot open, write, or trace unless its exact copied-save name is supplied. */
@Mod(InstanceClientProbe.MODID)
public final class InstanceClientProbe {
    public static final String MODID = "td_instance_client_probe";
    private static final Logger LOG = LogUtils.getLogger();
    private static final String WORLD = System.getProperty("td.instanceProbe.world", "");
    private static final boolean ENABLED = Boolean.getBoolean("td.instanceProbe.enable") && approvedCopiedSave(WORLD);
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final AtomicBoolean FINISHED = new AtomicBoolean();
    private static final List<JsonObject> WRITES = new CopyOnWriteArrayList<>();
    private static volatile boolean requested;
    private static volatile Path expectedRoot;
    private static volatile int droppedWrites;

    public InstanceClientProbe() {
        if (FMLEnvironment.dist == Dist.CLIENT && ENABLED) install();
    }

    private static boolean approvedCopiedSave(String name) {
        return name.matches("TD-River-[A-Za-z0-9._-]{1,71}");
    }

    private static void install() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> serverAboutToStart(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> serverStarted(event.getServer()));
        LOG.info("TD instance probe armed for exact copied save {}", WORLD);
    }

    private static void tick() {
        if (FINISHED.get() || requested) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null || !(minecraft.screen instanceof TitleScreen) || minecraft.getOverlay() != null) return;
        if (!minecraft.getLevelSource().levelExists(WORLD)) {
            LOG.error("TD instance probe remains inert: copied save does not exist: {}", WORLD);
            return;
        }
        try {
            expectedRoot = minecraft.gameDirectory.toPath().resolve("saves").resolve(WORLD).toRealPath();
            if (!Files.isRegularFile(expectedRoot.resolve("level.dat"))) {
                LOG.error("TD instance probe remains inert: copied save has no level.dat: {}", expectedRoot);
                return;
            }
        } catch (IOException exception) {
            LOG.error("TD instance probe remains inert while resolving copied save", exception);
            return;
        }
        requested = true;
        minecraft.options.pauseOnLostFocus = false;
        LOG.info("TD instance probe opening copied save {}", expectedRoot);
        minecraft.createWorldOpenFlows().openWorld(WORLD, () -> LOG.error("TD instance probe open failed for {}", WORLD));
    }

    /** Bind before spawn preparation so write tracing includes load-time writes. */
    private static void serverAboutToStart(MinecraftServer server) {
        if (!requested || !server.isSingleplayer() || expectedRoot == null || FINISHED.get()) return;
        try {
            Path actual = server.getWorldPath(LevelResource.ROOT).toRealPath();
            if (!actual.equals(expectedRoot)) {
                LOG.error("TD instance probe remains inert: opened save mismatch expected={} actual={}", expectedRoot, actual);
                return;
            }
        } catch (IOException exception) {
            LOG.error("TD instance probe remains inert while checking loaded save", exception);
            return;
        }
        if (!ACTIVE.compareAndSet(false, true)) return;
        LOG.info("TD instance probe bound exact copied save before server start: {}", expectedRoot);
    }

    private static void serverStarted(MinecraftServer server) {
        if (!ACTIVE.get() || FINISHED.get()) return;
        Thread worker = new Thread(() -> settleAndRecord(server), "td-instance-river-audit");
        worker.setDaemon(true);
        worker.start();
    }

    private static void settleAndRecord(MinecraftServer server) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("event", "td_instance_client_river_material_audit");
        receipt.addProperty("world", WORLD);
        receipt.addProperty("worldRoot", expectedRoot.toString());
        try {
            long readyStart = System.nanoTime();
            while (!fullAndPlayerReady(server)) {
                if (System.nanoTime() - readyStart > 300_000_000_000L) throw new IllegalStateException("full_chunks_or_player_timeout");
                Thread.sleep(50L);
            }
            long startTick = server.submit(server::getTickCount).get();
            long deadline = System.nanoTime() + 300_000_000_000L;
            long endTick;
            do {
                Thread.sleep(50L);
                endTick = server.submit(server::getTickCount).get();
                if (System.nanoTime() > deadline) throw new IllegalStateException("actual_tick_timeout");
            } while (endTick - startTick < 100L);
            final long finishedTick = endTick;
            JsonObject observed = server.submit(() -> observe(server.overworld())).get();
            receipt.addProperty("status", "success_observation");
            receipt.addProperty("settled", true);
            receipt.addProperty("startTick", startTick);
            receipt.addProperty("endTick", finishedTick);
            receipt.addProperty("actualTicks", finishedTick - startTick);
            receipt.add("observation", observed);
        } catch (Exception exception) {
            receipt.addProperty("status", "failed");
            receipt.addProperty("error", exception.toString());
        } finally {
            ACTIVE.set(false);
            receipt.addProperty("traceDropped", droppedWrites);
            try {
                receipt.addProperty("treeGroundSkippedWrites", treeGroundSkippedWrites());
            } catch (ReflectiveOperationException | LinkageError exception) {
                receipt.addProperty("status", "failed");
                receipt.addProperty("error", "Cannot read tree-ground counter: " + exception);
            }
            JsonArray writes = new JsonArray(); WRITES.forEach(writes::add); receipt.add("boundedWriteTrace", writes);
            receipt.addProperty("stopRequested", true);
            writeReceipt(receipt);
            FINISHED.set(true);
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
        }
    }

    private static boolean fullAndPlayerReady(MinecraftServer server) {
        return server.submit(() -> {
            if (server.getPlayerList().getPlayers().isEmpty()) return false;
            ServerLevel level = server.overworld();
            for (int x = 93; x <= 94; x++) for (int z = 10; z <= 11; z++)
                if (level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) return false;
            return true;
        }).join();
    }

    private static JsonObject observe(ServerLevel level) {
        JsonObject result = new JsonObject();
        JsonArray center = new JsonArray();
        for (int y = 75; y <= 84; y++) {
            BlockPos p = new BlockPos(1508, y, 171); BlockState state = level.getBlockState(p);
            JsonObject row = new JsonObject(); row.addProperty("y", y); row.addProperty("block", state.toString());
            row.addProperty("water", level.getFluidState(p).is(FluidTags.WATER)); center.add(row);
        }
        result.add("targetColumn1508_80_171", center);
        int waterBottoms = 0, submergedGrass = 0;
        JsonArray grassPositions = new JsonArray();
        for (int x = 1500; x <= 1516; x++) for (int z = 163; z <= 179; z++) for (int y = 76; y <= 84; y++) {
            BlockPos water = new BlockPos(x, y, z);
            if (!level.getFluidState(water).is(FluidTags.WATER)) continue;
            BlockPos below = water.below();
            if (level.getFluidState(below).is(FluidTags.WATER)) continue;
            waterBottoms++;
            if (level.getBlockState(below).is(Blocks.GRASS_BLOCK)) {
                submergedGrass++;
                if (grassPositions.size() < 128) { JsonObject p = new JsonObject(); p.addProperty("x", x); p.addProperty("y", y - 1); p.addProperty("z", z); grassPositions.add(p); }
            }
        }
        result.addProperty("rectangle", "x1500..1516,z163..179,y75..84");
        result.addProperty("waterBottoms", waterBottoms);
        result.addProperty("submergedGrassBeds", submergedGrass);
        result.add("submergedGrassPositions", grassPositions);
        return result;
    }

    public static void recordWrite(ChunkAccess chunk, String kind, BlockPos pos, BlockState next) {
        if (!ACTIVE.get() || pos.getZ() != 171 || pos.getX() < 1500 || pos.getX() > 1516 || pos.getY() < 78 || pos.getY() > 82) return;
        BlockState previous = chunk.getBlockState(pos);
        if (previous.equals(next)) return;
        if (WRITES.size() >= 1000) { droppedWrites++; return; }
        JsonObject row = new JsonObject(); row.addProperty("kind", kind); row.addProperty("x", pos.getX()); row.addProperty("y", pos.getY()); row.addProperty("z", pos.getZ());
        row.addProperty("previous", previous.toString()); row.addProperty("next", next.toString());
        JsonArray stack = new JsonArray(); StackWalker.getInstance().walk(s -> s.limit(18).toList()).forEach(frame -> stack.add(frame.toString())); row.add("stack", stack);
        WRITES.add(row); System.out.println("TD_INSTANCE_WRITE " + row);
    }

    private static void writeReceipt(JsonObject receipt) {
        if (expectedRoot == null) return;
        try {
            Path out = expectedRoot.resolve("td-instance-client-probe-receipt.json");
            Path temp = expectedRoot.resolve("td-instance-client-probe-receipt.json.tmp");
            Files.writeString(temp, receipt.toString() + "\n", StandardCharsets.UTF_8);
            Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOG.info("TD instance probe receipt: {}", out);
        } catch (IOException exception) { LOG.error("TD instance probe could not write receipt", exception); }
    }

    /** Support either the installed fix or the optional relocated diagnostic fix. */
    private static long treeGroundSkippedWrites() throws ReflectiveOperationException {
        long skipped = 0L;
        for (String name : new String[] {
                "com.github.xandergos.terraindiffusionmc.compat.ecology.TreeGroundMaterialPolicy",
                "com.terrain.instanceclientprobe.production.compat.ecology.TreeGroundMaterialPolicy"}) {
            try {
                Class<?> policy = Class.forName(name);
                Method method = policy.getMethod("skippedWrites");
                skipped += ((Number) method.invoke(null)).longValue();
            } catch (ClassNotFoundException absent) {
                // An old baseline has neither policy. Other reflection failures invalidate the receipt.
            }
        }
        return skipped;
    }
}
