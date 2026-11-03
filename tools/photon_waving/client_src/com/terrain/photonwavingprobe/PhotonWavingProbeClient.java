package com.terrain.photonwavingprobe;

import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/** Clone-only fixed-time scene: validates live Iris rendering bindings, not FPS. */
public final class PhotonWavingProbeClient {
    private static final Logger LOG = LogUtils.getLogger();
    private static PhotonWavingProbeClient instance;
    private static final String WORLD = System.getProperty("photonwaving.world", "photon-waving-scene");
    private static final Path OUTPUT = Path.of(System.getProperty("photonwaving.output", "photon-waving-receipt.json"));
    /** Existing mode never writes scene blocks; it is only for a copied world. */
    private static final boolean EXISTING_SCENE = "existing".equals(System.getProperty("photonwaving.scene", "synthetic"));
    // Player chunk=(2,-36); lime inner=(3,-37), orange outer=(4,-36), two chunks east.
    private static final double X = propertyDouble("photonwaving.x", 40.5);
    private static final double Y = propertyDouble("photonwaving.y", 166.0);
    private static final double Z = propertyDouble("photonwaving.z", -572.5);
    private static final float YAW = (float) propertyDouble("photonwaving.yaw", -90.0);
    private static final float PITCH = (float) propertyDouble("photonwaving.pitch", 7.0);
    private static final int FLOOR_Y = 160;
    private static final boolean BENCHMARK = Boolean.getBoolean("photonwaving.benchmark");
    private static final boolean EXPORT_REGISTRY = Boolean.getBoolean("photonwaving.exportRegistry");
    private static final boolean VERIFY_CASTER = Boolean.getBoolean("photonwaving.verifyCaster");
    private static final String CASTER_MODE = System.getProperty("photonwaving.casterMode", "baseline");
    private static final String CASTER_METADATA = System.getProperty("photonwaving.casterMetadata", "");
    private static final long BENCHMARK_NS = Long.getLong("photonwaving.benchmarkSeconds", 60L) * 1_000_000_000L;
    // Loaded sections alone do not imply that temporal lighting/history has settled.
    private static final long SETTLE_NS = 45_000_000_000L;
    private static final int SETTLE_FRAMES = 256;
    private static final long SHOT_GAP_NS = 6_000_000_000L;
    private static final long RENDER_TIMEOUT_NS = 180_000_000_000L;
    private static final long FOCUS_RETRY_NS = 3_000_000_000L;

    private volatile boolean sceneReady, frozen, finished;
    private boolean requested, configured, captureStarted, focusRequested, benchmarkGlfwFocusedAtStart;
    private boolean registryExported, casterVerified;
    private long sceneStartedNs, phaseStartedNs, stableSinceNs, captureStartedNs, lastCaptureNs, lastFocusRequestNs;
    private int focusRequestCount;
    private int shots, stableFrames, renderedFrames;
    private String failure = "";
    private final List<Capture> captures = new ArrayList<>();

    static void install() {
        if (instance != null) return;
        instance = new PhotonWavingProbeClient();
        // Register first so its Post callback closes the frame before finish().
        ShadowGpuTimer.install();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> instance.tick());
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> instance.frame());
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> instance.serverStarted(event));
    }

    private void serverStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (!requested || !server.isSingleplayer()) return;
        ServerLevel level = server.overworld();
        prepareRules(level);
        if (!EXISTING_SCENE) buildScene(level);
        server.tickRateManager().setFrozen(true);
        frozen = true;
    }

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (finished) return;
        if (!requested && mc.level == null && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            if (!mc.getLevelSource().levelExists(WORLD)) { fail("clone_world_missing"); return; }
            requested = true;
            mc.createWorldOpenFlows().openWorld(WORLD, () -> fail("open_world_failed"));
            return;
        }
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null
                || mc.getCameraEntity() == null) return;
        mc.options.hideGui = true;
        // Wait for normal level/camera initialization; do not dismiss loading screens.
        if (mc.screen != null) return;
        if (!configured) {
            configured = true;
            MinecraftServer server = mc.getSingleplayerServer();
            ServerPlayer player = server.getPlayerList().getPlayer(mc.player.getUUID());
            server.execute(() -> configureServer(server.overworld(), player));
        }
        mc.player.setDeltaMovement(0, 0, 0);
        mc.player.setPos(X, Y, Z);
        mc.player.setYRot(YAW); mc.player.setXRot(PITCH);
        mc.player.yRotO = YAW; mc.player.xRotO = PITCH;
    }

    private void configureServer(ServerLevel level, ServerPlayer player) {
        prepareRules(level);
        if (!EXISTING_SCENE) buildScene(level);
        if (player != null) {
            player.teleportTo(level, X, Y, Z, YAW, PITCH);
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }
        level.getServer().tickRateManager().setFrozen(true);
        frozen = true;
        sceneStartedNs = System.nanoTime();
        phaseStartedNs = sceneStartedNs;
        // Publish this last: the render thread must never observe a zero start time.
        sceneReady = true;
        LOG.info("Photon waving probe scene ready: fixed camera, frozen time/weather/server, GUI hidden");
    }

    private static void prepareRules(ServerLevel level) {
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
        level.setDayTime(6000L);
        level.setWeatherParameters(0, 0, false, false);
    }

    private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, 3);
    }

    private static void buildScene(ServerLevel level) {
        for (int x = 16; x < 96; x++) for (int z = -596; z < -548; z++) {
            set(level, x, FLOOR_Y, z, Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 8; y++) set(level, x, y, z, Blocks.AIR.defaultBlockState());
        }
        // The clusters are offset in Z so neither hides the other from the fixed east-facing view.
        cluster(level, 52, -580, true);
        cluster(level, 68, -566, false);
        if (BENCHMARK) CasterBenchmarkScene.build(level);
    }

    private static void cluster(ServerLevel level, int x, int z, boolean inner) {
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            if (Math.abs(dx) + Math.abs(dz) <= 4) set(level, x + dx, FLOOR_Y + 1, z + dz, Blocks.SHORT_GRASS.defaultBlockState());
        }
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            set(level, x + dx, FLOOR_Y + 3 + ((dx + dz) & 1), z + dz, leaves);
        }
        set(level, x, FLOOR_Y + 1, z, inner ? Blocks.LIME_CONCRETE.defaultBlockState() : Blocks.ORANGE_CONCRETE.defaultBlockState());
    }

    private void frame() {
        if (finished || !sceneReady || !frozen) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        renderedFrames++;
        if (!captureStarted && now - phaseStartedNs >= RENDER_TIMEOUT_NS) {
            fail(BENCHMARK && focusRequested && !glfwFocused(mc)
                ? "window_focus_timeout" : "render_sections_unsettled_timeout");
            return;
        }
        boolean cleanFrame = mc.screen == null && mc.getOverlay() == null && mc.levelRenderer.hasRenderedAllSections();
        if (!cleanFrame) { stableSinceNs = 0L; stableFrames = 0; return; }
        if (stableSinceNs == 0L) stableSinceNs = now;
        stableFrames++;
        if (!captureStarted) {
            if (now - stableSinceNs < SETTLE_NS || stableFrames < SETTLE_FRAMES) return;
            boolean verificationPhaseChanged = false;
            if (EXPORT_REGISTRY && !registryExported) {
                try { CasterRegistry.export(OUTPUT.resolveSibling("caster-registry.json")); }
                catch (Exception error) { LOG.error("Caster registry export failed", error); fail("caster_registry_export_failed"); return; }
                registryExported = true;
                verificationPhaseChanged = true;
            }
            if (VERIFY_CASTER && !casterVerified) {
                Path metadata = CASTER_METADATA.isEmpty()
                    ? OUTPUT.resolveSibling("caster-registry.json") : Path.of(CASTER_METADATA);
                try { CasterRegistry.verifyActive(metadata, CASTER_MODE, OUTPUT.resolveSibling("caster-verification.json")); }
                catch (Exception error) { LOG.error("Caster registry verification failed", error); fail("caster_registry_verification_failed"); return; }
                casterVerified = true;
                verificationPhaseChanged = true;
            }
            if (verificationPhaseChanged) {
                stableSinceNs = System.nanoTime();
                phaseStartedNs = stableSinceNs;
                stableFrames = 0;
                return;
            }
            if (BENCHMARK && !glfwFocused(mc)) {
                if (!focusRequested || now - lastFocusRequestNs >= FOCUS_RETRY_NS) {
                    try { GLFW.glfwFocusWindow(mc.getWindow().getWindow()); }
                    catch (Throwable error) { LOG.error("Benchmark window focus request failed", error); fail("window_focus_request_failed"); return; }
                    focusRequested = true;
                    lastFocusRequestNs = now;
                    focusRequestCount++;
                    LOG.info("Photon waving benchmark requested GLFW window focus before timing ({})", focusRequestCount);
                }
                return;
            }
            captureStarted = true;
            captureStartedNs = now;
            if (BENCHMARK) {
                benchmarkGlfwFocusedAtStart = glfwFocused(mc);
                if (!benchmarkGlfwFocusedAtStart) { fail("window_unfocused_at_benchmark_start"); return; }
                ShadowGpuTimer.startMeasurement();
            }
        }
        if (BENCHMARK) {
            if (!glfwFocused(mc)) { fail("window_unfocused_during_benchmark"); return; }
            // Keep screenshot readback outside the measured interval.
            if (now - captureStartedNs < BENCHMARK_NS) return;
            if (shots == 0) {
                try { ShadowGpuTimer.stopAndWrite(OUTPUT.resolveSibling("timing")); }
                catch (IOException error) { LOG.error("GPU timing write failed", error); fail("gpu_timing_write_failed"); return; }
            }
        }
        if (shots < 3 && (shots == 0 || now - lastCaptureNs >= SHOT_GAP_NS)) capture(mc, now);
        if (shots == 3 && now - lastCaptureNs >= 3_000_000_000L) finish();
    }

    private void capture(Minecraft mc, long now) {
        String name = "photon-waving-" + shots + ".png";
        captures.add(new Capture(name, (now - captureStartedNs) / 1_000_000L, mc.level.getDayTime(), mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot(), (now - sceneStartedNs) / 1_000_000L, renderedFrames, stableFrames, mc.isWindowActive()));
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> LOG.info("Photon waving screenshot: {}", message.getString()));
        lastCaptureNs = now;
        shots++;
    }

    private void fail(String reason) { failure = reason; finish(); }
    private void finish() {
        if (finished) return;
        finished = true;
        try { writeReceipt(); } catch (IOException error) { LOG.error("Photon waving receipt failed", error); }
        Minecraft.getInstance().stop();
    }

    private void writeReceipt() throws IOException {
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        Path parent = OUTPUT.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        StringBuilder capturesJson = new StringBuilder("[");
        for (int i = 0; i < captures.size(); i++) {
            if (i > 0) capturesJson.append(',');
            capturesJson.append(captures.get(i).json());
        }
        capturesJson.append(']');
        String json = "{\n" + field("success", failure.isEmpty() && shots == 3 && sceneReady && frozen) +
            field("failure", quote(failure)) + field("created_utc", quote(Instant.now().toString())) +
            field("world", quote(WORLD)) +
            field("scope", quote("clone-only fixed-camera visual diagnostic; time/weather/server ticks frozen")) +
            field("world", quote(WORLD)) + field("scene_kind", quote(EXISTING_SCENE ? "existing" : "synthetic")) +
            field("world", quote(WORLD)) +
            field("benchmark", BENCHMARK) + field("benchmark_seconds", BENCHMARK_NS / 1_000_000_000L) +
            field("benchmark_focus_requested", focusRequested) +
            field("benchmark_focus_request_count", focusRequestCount) +
            field("benchmark_glfw_focused_at_start", !BENCHMARK || benchmarkGlfwFocusedAtStart) +
            field("caster_verification_requested", VERIFY_CASTER) +
            field("caster_verification_passed", !VERIFY_CASTER || casterVerified) +
            field("caster_verification_mode", quote(CASTER_MODE)) +
            field("caster_verification_report", quote(OUTPUT.resolveSibling("caster-verification.json").toString())) +
            field("caster_metadata", quote(CASTER_METADATA.isEmpty() ? OUTPUT.resolveSibling("caster-registry.json").toString() : CASTER_METADATA)) +
            field("window_active_at_finish", mc.isWindowActive()) +
            field("settle_ms", SETTLE_NS / 1_000_000L) + field("settle_frames", SETTLE_FRAMES) +
            field("render_distance_chunks", mc.options.renderDistance().get()) +
            field("simulation_distance_chunks", mc.options.simulationDistance().get()) +
            field("player_chunk", "{\"x\":" + floorChunk(X) + ",\"z\":" + floorChunk(Z) + "}") +
            field("inside_cluster", EXISTING_SCENE ? "null" : "{\"marker\":\"lime\",\"x\":52,\"z\":-580,\"chunk_x\":3,\"chunk_z\":-37}") +
            field("outside_cluster", EXISTING_SCENE ? "null" : "{\"marker\":\"orange\",\"x\":68,\"z\":-566,\"chunk_x\":4,\"chunk_z\":-36}") +
            field("captures", capturesJson.toString()) +
            field("window", "{\"width\":" + window.getWidth() + ",\"height\":" + window.getHeight() + "}", false) + "}\n";
        Files.writeString(OUTPUT, json, StandardCharsets.UTF_8);
    }

    private static String field(String name, Object value) { return field(name, value, true); }
    private static String field(String name, Object value, boolean comma) { return "  \"" + name + "\": " + value + (comma ? ",\n" : "\n"); }
    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static double propertyDouble(String name, double fallback) {
        String value = System.getProperty(name);
        if (value == null) return fallback;
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) throw new NumberFormatException("nonfinite");
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value, error);
        }
    }
    private static int floorChunk(double coordinate) { return (int) Math.floor(coordinate / 16.0); }
    private static boolean glfwFocused(Minecraft mc) { return GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE; }

    private record Capture(String file, long elapsed_ms, long day_time, double x, double y, double z, float yaw, float pitch, long scene_ms, int rendered_frames, int stable_frames, boolean window_active) {
        String json() { return String.format(Locale.ROOT, "{\"file\":%s,\"elapsed_ms\":%d,\"day_time\":%d,\"camera\":{\"x\":%.3f,\"y\":%.3f,\"z\":%.3f,\"yaw\":%.3f,\"pitch\":%.3f},\"scene_ms\":%d,\"rendered_frames\":%d,\"stable_frames\":%d,\"window_active\":%b}", quote(file), elapsed_ms, day_time, x, y, z, yaw, pitch, scene_ms, rendered_frames, stable_frames, window_active); }
    }
}
