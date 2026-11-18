package com.terrain.chunkprofile;

import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;

/** Bounded saved-block and rendered-scene review for the wetland terrain change. */
public final class WetlandTerrainReview {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long REQUIRED_SEED = -2049264310295890081L;
    private static final int REQUIRED_SCALE = 1;
    private static final int REQUIRED_TICKS = 100;
    private static final int RADIUS_CHUNKS = 1;
    // A region-ticket distance of two is the level-31 block/entity-ticking range.
    private static final int BLOCK_TICKING_DISTANCE = 2;
    private static final TicketType<ChunkPos> TICKET = TicketType.create("td-wetland-terrain-review", Comparator.comparingLong(ChunkPos::toLong));
    private static final List<Site> SITES = List.of(
            new Site("freshwater", 17383, 3260), new Site("mangrove", 17647, 3406), new Site("swamp_ruin", 17828, 3408));

    private WetlandTerrainReview() { }

    /** Runs after normal requested FULL phases and retains each ticket through its screenshot. */
    public static Map<String,Object> run(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        long seed = level.getSeed();
        int scale = Integer.getInteger("chunkprofile.wetlandTerrainReviewScale", -1);
        if (seed != REQUIRED_SEED || scale != REQUIRED_SCALE) throw new IllegalStateException("Wetland terrain review requires seed " + REQUIRED_SEED + " and scale 1; got seed=" + seed + " scale=" + scale);
        // Review lighting is controlled only in this disposable fixture.
        server.submit(() -> {
            level.setDayTime(6000L);
            level.setWeatherParameters(12000, 0, false, false);
        }).get(30, TimeUnit.SECONDS);
        List<Map<String,Object>> sites = new ArrayList<>();
        for (Site site : SITES) sites.add(reviewSite(server, level, site));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status", sites.stream().allMatch(s -> "passed".equals(s.get("status"))) ? "passed" : "failed");
        result.put("seed", seed); result.put("scale", scale); result.put("requiredServerTicks", REQUIRED_TICKS); result.put("ticketRadiusChunks", RADIUS_CHUNKS); result.put("sites", sites);
        result.put("reviewLighting", "daytime 6000, clear weather in disposable fixture");
        result.put("scope", "Fixed fresh-world sites; saved FULL-chunk blocks after 100 actual integrated-server ticks plus client scene screenshots. This observes blocks and does not attribute water, vegetation, or terrain to a feature.");
        return result;
    }

    private static Map<String,Object> reviewSite(MinecraftServer server, ServerLevel level, Site site) {
        List<ChunkPos> chunks = chunks(site);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("name", site.name); result.put("center", List.of(site.x, site.z)); result.put("chunks", chunks.stream().map(p -> List.of(p.x, p.z)).toList());
        try {
            server.submit(() -> chunks.forEach(p -> level.getChunkSource().addRegionTicket(TICKET, p, BLOCK_TICKING_DISTANCE, p))).get(60, TimeUnit.SECONDS);
            List<? extends CompletableFuture<?>> futures = server.submit(() -> chunks.stream().map(p -> level.getChunkSource().getChunkFuture(p.x, p.z, ChunkStatus.FULL, true)).toList()).get(30, TimeUnit.SECONDS);
            for (CompletableFuture<?> future : futures) future.get(180, TimeUnit.SECONDS);
            Map<String,Object> eligibility;
            long tickingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            do {
                eligibility = server.submit(() -> tickEligibility(level, chunks)).get(30, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(eligibility.get("allBlockTickEligible"))) break;
                Thread.sleep(50L);
            } while (System.nanoTime() < tickingDeadline);
            result.put("tickEligibility", eligibility);
            if (!Boolean.TRUE.equals(eligibility.get("allBlockTickEligible"))) throw new IllegalStateException("Review chunks are not block-tick eligible at " + site.name);
            teleportForScene(server, level, site);
            long started = server.submit(level::getGameTime).get(30, TimeUnit.SECONDS);
            long reached = awaitServerTicks(server, level, started);
            result.putAll(server.submit(() -> snapshot(level, site, chunks, reached - started)).get(60, TimeUnit.SECONDS));
            result.putAll(captureScene(site)); result.put("status", "passed");
        } catch (Throwable failure) {
            result.put("status", "failed"); result.put("error", failure.toString()); LOG.error("Wetland terrain review failed at {}", site.name, failure);
        } finally {
            try { server.submit(() -> chunks.forEach(p -> level.getChunkSource().removeRegionTicket(TICKET, p, BLOCK_TICKING_DISTANCE, p))).get(60, TimeUnit.SECONDS); }
            catch (Throwable cleanup) { result.put("ticketCleanupError", cleanup.toString()); result.put("status", "failed"); }
        }
        return result;
    }

    private static Map<String,Object> tickEligibility(ServerLevel level, List<ChunkPos> chunks) {
        List<Map<String,Object>> rows = new ArrayList<>(); boolean all = true;
        for (ChunkPos pos : chunks) {
            boolean eligible = level.shouldTickBlocksAt(pos.toLong());
            rows.add(Map.of("chunk", List.of(pos.x, pos.z), "blockTickEligible", eligible, "scheduledTickEligible", eligible)); all &= eligible;
        }
        return Map.of("ticketDistance", BLOCK_TICKING_DISTANCE, "ticketLevel", 31, "allBlockTickEligible", all, "chunks", rows,
                "scheduledTickEligibility", "ServerLevel.shouldTickBlocksAt at the settled ticketed chunk");
    }

    private static long awaitServerTicks(MinecraftServer server, ServerLevel level, long started) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180); long reached;
        do { Thread.sleep(50L); reached = server.submit(level::getGameTime).get(30, TimeUnit.SECONDS); }
        while (reached - started < REQUIRED_TICKS && System.nanoTime() < deadline);
        if (reached - started < REQUIRED_TICKS) throw new IllegalStateException("Server did not advance 100 ticks");
        return reached;
    }

    private static Map<String,Object> snapshot(ServerLevel level, Site site, List<ChunkPos> chunks, long reached) {
        List<Map<String,Object>> snapshots = new ArrayList<>(); int full = 0;
        for (ChunkPos pos : chunks) { ChunkAccess chunk = level.getChunkSource().getChunkNow(pos.x, pos.z); if (chunk != null) { full++; snapshots.add(WetlandSpatialAudit.scanFullChunk(level, pos, chunk)); } }
        if (full != chunks.size()) throw new IllegalStateException("Not every review chunk remained FULL at " + site.name);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("phase", "after_100_actual_server_ticks_before_ticket_release"); result.put("siteChunkCount", chunks.size()); result.put("fullChunkCount", full); result.put("reachedServerTicks", reached); result.put("surfaceWaterMudTreeSupportSnapshots", snapshots);
        return result;
    }

    private static void teleportForScene(MinecraftServer server, ServerLevel level, Site site) throws Exception {
        server.submit(() -> { var players = server.getPlayerList().getPlayers(); if (players.isEmpty()) throw new IllegalStateException("No integrated client player"); players.getFirst().teleportTo(level, site.x + .5, 100D, site.z + .5, 170F, 50F); }).get(30, TimeUnit.SECONDS);
    }

    private static Map<String,Object> captureScene(Site site) throws Exception {
        String filename = "td-wetland-terrain-review-" + site.name + ".png"; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120); long arrivedAt = -1L;
        while (System.nanoTime() < deadline) {
            CompletableFuture<Boolean> ready = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> { try { Minecraft client = Minecraft.getInstance(); boolean arrived = client.level != null && client.player != null && client.level.getChunkSource().getChunk(site.x >> 4, site.z >> 4, ChunkStatus.FULL, false) != null; if (arrived) setClientCamera(client, site); ready.complete(arrived); } catch (Throwable failure) { ready.completeExceptionally(failure); } });
            if (!ready.get(30, TimeUnit.SECONDS)) { Thread.sleep(250L); continue; }
            if (arrivedAt < 0L) { arrivedAt = System.nanoTime(); Thread.sleep(250L); continue; }
            if (System.nanoTime() - arrivedAt < TimeUnit.SECONDS.toNanos(3)) { Thread.sleep(100L); continue; }
            CompletableFuture<String> saved = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> { try { Minecraft client = Minecraft.getInstance(); setClientCamera(client, site); Screenshot.grab(client.gameDirectory, filename, client.getMainRenderTarget(), message -> saved.complete(message.getString())); } catch (Throwable failure) { saved.completeExceptionally(failure); } });
            String message = saved.get(30, TimeUnit.SECONDS); Path path = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve(filename).toAbsolutePath();
            long fileDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (!Files.isRegularFile(path) && System.nanoTime() < fileDeadline) Thread.sleep(50L);
            if (!Files.isRegularFile(path)) throw new IllegalStateException("Screenshot callback completed without file: " + path);
            return Map.of("clientTargetChunkArrived", true, "renderDelaySeconds", 3, "screenshotPath", path.toString(), "screenshotMessage", message);
        }
        throw new IllegalStateException("Client did not receive review target chunk at " + site.name);
    }

    private static void setClientCamera(Minecraft client, Site site) {
        if (client.player == null) throw new IllegalStateException("Client player disappeared");
        client.player.setDeltaMovement(0, 0, 0); client.player.setPos(site.x + .5, 100D, site.z + .5); client.player.setYRot(170F); client.player.setXRot(50F); client.player.yRotO = 170F; client.player.xRotO = 50F;
    }
    private static List<ChunkPos> chunks(Site site) { List<ChunkPos> result = new ArrayList<>(); int cx = site.x >> 4, cz = site.z >> 4; for (int z = cz - RADIUS_CHUNKS; z <= cz + RADIUS_CHUNKS; z++) for (int x = cx - RADIUS_CHUNKS; x <= cx + RADIUS_CHUNKS; x++) result.add(new ChunkPos(x, z)); return result; }
    private record Site(String name, int x, int z) { }
}
