package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;

/** Bounded, copied-world-only client benchmark. Never requests chunks synchronously. */
@EventBusSubscriber(modid = "terrain_diffusion_mc", bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientPerformanceProbe {
    private static final Logger LOG = LoggerFactory.getLogger("TerrainDiffusionClientPerformanceProbe");
    private static final long STATIONARY_NANOS = 30_000_000_000L;
    private static final long STEP_NANOS = 60_000_000_000L;
    private static final long WATCHDOG_MILLIS = 20 * 60 * 1000L;
    private static final int STEPS = 8;
    private static final int STEP_BLOCKS = 64;

    private enum Stage { STARTING, STATIONARY, TELEPORTING, WAITING_CHUNK, COMPLETE }
    private static Stage stage = Stage.STARTING;
    private static boolean started;
    private static long stageStartNanos;
    private static long stepStartNanos;
    private static long clientTicks;
    private static long startTick;
    private static long lastServerPollNanos;
    private static boolean serverPollPending;
    private static boolean serverChunkReady;
    private static int serverReadyTick;
    private static int step;
    private static int targetX;
    private static int targetZ;
    private static double originX;
    private static double originZ;
    private static boolean preloaded;
    private static String baseline = "null";

    private ClientPerformanceProbe() { }

    @SubscribeEvent
    public static void afterClientTick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("td.clientPerformanceProbe") || stage == Stage.COMPLETE) return;
        Minecraft mc = Minecraft.getInstance();
        if (!started) {
            started = true;
            if (!fixtureMatches(mc)) {
                receipt("disabled", "\"reason\":\"fixture_mismatch\"");
                stage = Stage.COMPLETE;
                return;
            }
            startWatchdog(mc);
            receipt("started", "\"uptime_ms\":" + ManagementFactory.getRuntimeMXBean().getUptime());
        }
        clientTicks++;
        long now = System.nanoTime();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        MinecraftServer server = mc.getSingleplayerServer();
        if (stage == Stage.STARTING) {
            if (level == null || player == null || server == null || mc.screen != null) return;
            originX = player.getX();
            originZ = player.getZ();
            stageStartNanos = now;
            startTick = clientTicks;
            baseline = providerSnapshot();
            receipt("load_ready", "\"uptime_ms\":" + ManagementFactory.getRuntimeMXBean().getUptime()
                    + ",\"x\":" + number(originX) + ",\"z\":" + number(originZ)
                    + ",\"provider\":" + baseline);
            stage = Stage.STATIONARY;
            return;
        }
        if (level == null || player == null || server == null) {
            finish(mc, "failure", "\"reason\":\"world_closed\"");
            return;
        }
        if (stage == Stage.STATIONARY) {
            if (now - stageStartNanos < STATIONARY_NANOS) return;
            receipt("stationary", "\"elapsed_ms\":" + elapsedMs(now, stageStartNanos)
                    + ",\"client_ticks\":" + (clientTicks - startTick)
                    + ",\"horizontal_drift_blocks\":" + number(Math.hypot(player.getX() - originX, player.getZ() - originZ))
                    + ",\"provider_baseline\":" + baseline
                    + ",\"provider_after\":" + providerSnapshot());
            beginStep(mc, server, player, now);
            return;
        }
        if (now - stepStartNanos > STEP_NANOS) {
            finish(mc, "failure", "\"reason\":\"chunk_timeout\",\"step\":" + step
                    + ",\"target_chunk_x\":" + (targetX >> 4) + ",\"target_chunk_z\":" + (targetZ >> 4));
            return;
        }
        if (stage == Stage.WAITING_CHUNK) {
            if (!serverChunkReady && !serverPollPending && now - lastServerPollNanos >= 250_000_000L) {
                pollServerChunk(mc, server, player.getUUID(), now);
            }
            if (serverChunkReady && level.hasChunk(targetX >> 4, targetZ >> 4)
                    && Math.abs(player.getX() - (targetX + 0.5)) < 2.0
                    && Math.abs(player.getZ() - (targetZ + 0.5)) < 2.0) {
                receipt("step_ready", "\"step\":" + step + ",\"direction\":\"east\""
                        + ",\"x\":" + targetX + ",\"y\":220,\"z\":" + targetZ
                        + ",\"preloaded\":" + preloaded
                        + ",\"elapsed_ms\":" + elapsedMs(now, stepStartNanos)
                        + ",\"client_ticks\":" + (clientTicks - startTick)
                        + ",\"server_tick\":" + serverReadyTick
                        + ",\"provider\":" + providerSnapshot());
                if (step == STEPS) {
                    finish(mc, "complete", "\"steps\":" + STEPS + ",\"provider_final\":" + providerSnapshot());
                } else {
                    beginStep(mc, server, player, now);
                }
            }
        }
    }

    private static void beginStep(Minecraft mc, MinecraftServer server, LocalPlayer player, long now) {
        step++;
        targetX = (int) Math.floor(originX) + step * STEP_BLOCKS;
        targetZ = (int) Math.floor(originZ);
        stepStartNanos = now;
        startTick = clientTicks;
        serverChunkReady = false;
        serverPollPending = false;
        stage = Stage.TELEPORTING;
        UUID playerId = player.getUUID();
        int x = targetX;
        int z = targetZ;
        server.execute(() -> {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(playerId);
            if (serverPlayer == null) {
                mc.execute(() -> finish(mc, "failure", "\"reason\":\"server_player_missing\",\"step\":" + step));
                return;
            }
            boolean wasLoaded = serverPlayer.serverLevel().getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
            int tick = server.getTickCount();
            serverPlayer.setGameMode(GameType.SPECTATOR);
            serverPlayer.teleportTo(x + 0.5, 220.0, z + 0.5);
            mc.execute(() -> {
                if (stage != Stage.TELEPORTING) return;
                preloaded = wasLoaded;
                receipt("step_start", "\"step\":" + step + ",\"direction\":\"east\""
                        + ",\"x\":" + x + ",\"y\":220,\"z\":" + z
                        + ",\"preloaded\":" + wasLoaded + ",\"server_tick\":" + tick);
                stage = Stage.WAITING_CHUNK;
            });
        });
    }

    private static void pollServerChunk(Minecraft mc, MinecraftServer server, UUID playerId, long now) {
        serverPollPending = true;
        lastServerPollNanos = now;
        int x = targetX;
        int z = targetZ;
        int currentStep = step;
        server.execute(() -> {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(playerId);
            boolean ready = serverPlayer != null
                    && serverPlayer.serverLevel().getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
            int tick = server.getTickCount();
            mc.execute(() -> {
                if (step != currentStep || stage != Stage.WAITING_CHUNK) return;
                serverPollPending = false;
                if (ready) {
                    serverChunkReady = true;
                    serverReadyTick = tick;
                }
            });
        });
    }

    private static boolean fixtureMatches(Minecraft mc) {
        try {
            String configured = System.getProperty("td.clientPerformanceProbe.gameDir", "");
            if (configured.isBlank()) return false;
            File actual = mc.gameDirectory.getCanonicalFile();
            File expected = new File(configured).getCanonicalFile();
            return actual.equals(expected)
                    && "game".equals(actual.getName())
                    && "full-client-test".equals(actual.getParentFile().getName())
                    && Files.isRegularFile(actual.toPath().resolve("saves/Benchmark World/level.dat"))
                    && Files.isRegularFile(actual.toPath().resolveSibling("fixture.json"));
        } catch (Exception exception) {
            LOG.warn("[TD-CLIENT-PERF] fixture validation failed", exception);
            return false;
        }
    }

    private static String providerSnapshot() {
        try {
            Field field = LocalTerrainProvider.class.getDeclaredField("INSTANCE");
            field.setAccessible(true);
            LocalTerrainProvider provider = (LocalTerrainProvider) field.get(null);
            return provider == null ? "null" : quote(provider.performanceSnapshot().toString());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return quote("unavailable:" + exception.getClass().getSimpleName());
        }
    }

    private static void startWatchdog(Minecraft mc) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(WATCHDOG_MILLIS);
            } catch (InterruptedException ignored) {
                return;
            }
            mc.execute(() -> {
                if (stage != Stage.COMPLETE) finish(mc, "failure", "\"reason\":\"watchdog_timeout\"");
            });
        }, "td-client-performance-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void finish(Minecraft mc, String outcome, String details) {
        if (stage == Stage.COMPLETE) return;
        stage = Stage.COMPLETE;
        receipt(outcome, details + ",\"uptime_ms\":" + ManagementFactory.getRuntimeMXBean().getUptime());
        mc.stop();
    }

    private static long elapsedMs(long now, long since) { return (now - since) / 1_000_000L; }
    private static String number(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
    private static void receipt(String stageName, String fields) {
        LOG.info("[TD-CLIENT-PERF] {\"stage\":\"" + stageName + "\"," + fields + "}");
    }
}
