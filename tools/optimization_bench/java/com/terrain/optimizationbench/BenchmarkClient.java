package com.terrain.optimizationbench;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import com.sun.management.OperatingSystemMXBean;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import net.minecraft.world.phys.AABB;

/** Records wall-clock frame intervals after a deterministic, static vanilla scene is ready. */
final class BenchmarkClient {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long SEED = 0x4f505442454e4348L;
    private static final long CREATE_TIMEOUT_NS = 300_000_000_000L;
    private static final int GPU_QUERY_RING_SIZE = 16;
    private static final int SCENE_Y = 250;
    private static final ResourceKey<WorldPreset> TERRAIN_DIFFUSION_PRESET = ResourceKey.create(Registries.WORLD_PRESET, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "terrain_diffusion"));
    private static final AtomicBoolean SCENE_READY = new AtomicBoolean();
    private static final AtomicBoolean MODEL_SMOKE_READY = new AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicInteger ACCEPTED_FRAMES = new java.util.concurrent.atomic.AtomicInteger();
    private static BenchmarkClient INSTANCE;

    private final long warmupNs = secondsProperty("optbench.warmup", 30L) * 1_000_000_000L;
    private final long sampleNs = secondsProperty("optbench.seconds", 60L) * 1_000_000_000L;
    private final String worldName = System.getProperty("optbench.world", "optbench-stationary");
    private final boolean terrainDiffusionWorld = Boolean.parseBoolean(System.getProperty("optbench.terrainDiffusionWorld", "false"));
    private final boolean verifyOptimizations = Boolean.parseBoolean(System.getProperty("optbench.verifyOptimizations", "false"));
    private final boolean modelSmoke = Boolean.parseBoolean(System.getProperty("optbench.modelSmoke", "false"));
    private final int terrainRadius = intProperty("optbench.terrainRadius", 0, 0, 8);
    private final Path output = Path.of(System.getProperty("optbench.output", "optbench-receipt.json"));
    private final List<Long> frameTimesNs = new ArrayList<>();
    private final List<Long> renderWorkNs = new ArrayList<>();
    private final List<Long> gpuWorkNs = new ArrayList<>();
    private final boolean gpuTimingRequested = Boolean.parseBoolean(System.getProperty("optbench.gpuTiming", "true"));
    private final int[] gpuStartQueries = new int[GPU_QUERY_RING_SIZE];
    private final int[] gpuEndQueries = new int[GPU_QUERY_RING_SIZE];
    private final boolean[] gpuPending = new boolean[GPU_QUERY_RING_SIZE];
    private final List<String> failures = new ArrayList<>();
    private final Map<String, String> runtimeChecks = new LinkedHashMap<>();
    private final long launchedAtNs = System.nanoTime();
    private boolean requestedWorld;
    private long createRequestedAtNs;
    private long clientWorldReadyAtNs;
    private long sceneReadyAtNs;
    private long heapStartUsed;
    private long heapStartCommitted;
    private boolean installedScene;
    private boolean optimizationChecksRun;
    private boolean measuring;
    private boolean screenshotTaken;
    private boolean modelSmokeStarted;
    private boolean modelSmokeScreenshotRequested;
    private int modelSmokeReadyTicks;
    private boolean finished;
    private int sceneStableTicks;
    private int sampleInvalidFrames;
    private int sampleRenderFrames;
    private int sampleInactiveFrames;
    private boolean screenshotRequested;
    private boolean sampleInvalidStateObserved;
    private int observedClientItemFrames;
    private String lastScreenClass;
    private boolean handledLoadingScreen;
    private long readyAtNs;
    private long lastFrameNs;
    private long renderPreNs;
    private int sampleWindowFpsLimitStart;
    private int sampleWindowFpsLimitEnd;
    private boolean gpuTimingInitialized;
    private boolean gpuTimingSupported;
    private int gpuCursor;
    private int gpuOpenSlot = -1;
    private long gcCountStart;
    private long gcTimeStart;
    private long processCpuStart = -1L;
    private boolean originalPauseOnLostFocus;
    private int originalCap;
    private boolean originalVsync;
    private String originalGraphics;
    private boolean timingEndCaptured;
    private long timingEndUsed;
    private long timingBeforeExplicitGc;
    private long timingPostExplicitGc;
    private long timingEndCommitted;
    private long timingGcCollections;
    private long timingGcMillis;
    private long timingProcessCpuNanos;

    static void install() {
        if (INSTANCE == null) {
            INSTANCE = new BenchmarkClient();
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> INSTANCE.tick());
            NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> INSTANCE.preFrame());
            NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> INSTANCE.frame());
        }
    }

    private BenchmarkClient() {}

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (finished) return;
        String screenClass = mc.screen == null ? "none" : mc.screen.getClass().getName(); if (!screenClass.equals(lastScreenClass)) { lastScreenClass = screenClass; LOG.info("Optimization benchmark client screen: {}", screenClass); }
        if (mc.screen instanceof net.neoforged.neoforge.client.gui.LoadingErrorScreen && !handledLoadingScreen) {
            handledLoadingScreen = true;
            var issues = net.neoforged.fml.ModLoader.getLoadingIssues();
            for (var issue : issues) LOG.warn("Benchmark loading issue: {} {}", issue.severity(), net.neoforged.fml.i18n.FMLTranslations.translateIssue(issue));
            if (issues.isEmpty() || issues.stream().anyMatch(issue -> issue.severity() == net.neoforged.fml.ModLoadingIssue.Severity.ERROR)) {
                fail("mod_loading_error");
                return;
            }
            String continueLabel = net.neoforged.fml.i18n.FMLTranslations.parseMessage("fml.button.continue.launch");
            for (var child : mc.screen.children()) {
                if (child instanceof net.minecraft.client.gui.components.Button button && button.active && button.getMessage().getString().equals(continueLabel)) {
                    LOG.warn("Benchmark acknowledging warning-only loading screen via Continue button");
                    button.onPress();
                    return;
                }
            }
            fail("loading_warning_continue_unavailable");
            return;
        }
        if (measuring && (mc.screen != null || mc.getOverlay() != null || mc.isPaused() || isIconified(mc.getWindow()))) sampleInvalidStateObserved = true;
        if (!requestedWorld && mc.level == null && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            if (!preflightOptimizationTransforms()) {
                fail("runtime_preflight_failed");
                return;
            }
            requestedWorld = true;
            createRequestedAtNs = System.nanoTime();
            LOG.info("Optimization benchmark requesting fresh world: {}", worldName);
            applyMeasurementOptions(mc);
            try {
                GameRules rules = new GameRules();
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
                LevelSettings settings = new LevelSettings(worldName, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(
                    worldName, settings, new WorldOptions(SEED, false, false),
                    registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(terrainDiffusionWorld ? TERRAIN_DIFFUSION_PRESET : WorldPresets.FLAT).value().createWorldDimensions(),
                    mc.screen
                );
            } catch (Throwable t) {
                fail("world_create_exception:" + t.getClass().getSimpleName());
            }
        }
        if (requestedWorld && mc.level == null && System.nanoTime() - createRequestedAtNs > CREATE_TIMEOUT_NS) {
            fail("world_creation_timeout");
            return;
        }
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        if (clientWorldReadyAtNs == 0L) clientWorldReadyAtNs = System.nanoTime();
        if (!installedScene) {
            installedScene = true;
            var server = mc.getSingleplayerServer();
            LOG.info("Optimization benchmark scheduling scene installation");
            server.execute(() -> installScene(server.overworld(), server.getPlayerList().getPlayer(mc.player.getUUID()), terrainRadius));
        }
        if (!SCENE_READY.get()) return;
        if (mc.screen != null || mc.getOverlay() != null || !clientSceneBlocksPresent(mc)) { sceneStableTicks = 0; return; }
        observedClientItemFrames = mc.level.getEntitiesOfClass(ItemFrame.class, new AABB(-20, SCENE_Y - 6, 0, 20, SCENE_Y + 18, 15)).size();
        if (++sceneStableTicks < 50) return;
        // Keep the view stable even if the normal client tick receives a late position update.
        mc.player.getAbilities().flying = true; mc.player.getAbilities().mayfly = true; mc.player.setDeltaMovement(0,0,0); mc.player.setPos(0.5D, SCENE_Y, -20.5D);
        mc.player.setYRot(0.0F);
        mc.player.setXRot(-6.0F);
        mc.player.yRotO = 0.0F;
        mc.player.xRotO = -6.0F;
        if (modelSmokeStarted) {
            if (!MODEL_SMOKE_READY.get()) return;
            mc.player.setPos(0.5D, SCENE_Y + 3, -20.5D);
            mc.player.setXRot(0.0F);
            if (++modelSmokeReadyTicks == 50 && !modelSmokeScreenshotRequested) {
                modelSmokeScreenshotRequested = true;
                Screenshot.grab(mc.gameDirectory, "optimization-bench-model-smoke.png", mc.getMainRenderTarget(), message -> LOG.info("Benchmark model smoke screenshot: {}", message.getString()));
            }
            if (modelSmokeReadyTicks >= 70) {
                finish();
            }
            return;
        }
        if (readyAtNs == 0L) {
            mc.getWindow().setFramerateLimit(260);
            mc.getWindow().updateVsync(false);
            readyAtNs = System.nanoTime();
            sceneReadyAtNs = readyAtNs;
            snapshotStart();
            verifyRuntimeOptimizations();
            LOG.info("Optimization benchmark scene ready; warmup={}s sample={}s", warmupNs / 1_000_000_000L, sampleNs / 1_000_000_000L);
        }
    }

    /** Verifies the loaded runtime state instead of merely trusting mixin declarations. */
    private boolean preflightOptimizationTransforms() {
        if (!verifyOptimizations) return true;
        boolean passed = true;
        passed &= preflightClass("preflight_voxy_world_engine", "me.cortex.voxy.common.world.WorldEngine");
        passed &= preflightClass("preflight_voxy_world_section", "me.cortex.voxy.common.world.WorldSection");
        passed &= preflightClass("preflight_yung_aquifer_override_mask", "com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.aquiferoverride.AquiferOverrideMask");
        return passed;
    }

    private boolean preflightClass(String key, String className) {
        try {
            // initialize=false triggers Mixin transformation without running a
            // constructor or allocating a world-sized Voxy/YUNG data structure.
            Class.forName(className, false, BenchmarkClient.class.getClassLoader());
            recordRuntimeCheck(key, true, "transformed=true");
            return true;
        } catch (Throwable t) {
            recordRuntimeCheck(key, false, "error=" + t.getClass().getSimpleName());
            return false;
        }
    }

    private void verifyRuntimeOptimizations() {
        if (!verifyOptimizations || optimizationChecksRun) return;
        optimizationChecksRun = true;
        verifyYungAquiferMask();
        verifyTerrainDiffusionModelBytes();
        verifyTemplatePositionInterner();
    }

    private void verifyYungAquiferMask() {
        final String key = "yung_aquifer_mask_empty";
        try {
            Class<?> type = Class.forName("com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.aquiferoverride.AquiferOverrideMask");
            Object mask = type.getConstructor(int.class, int.class).newInstance(384, -64);
            var field = type.getDeclaredField("mask");
            field.setAccessible(true);
            BitSet bits = (BitSet) field.get(mask);
            recordRuntimeCheck(key, bits.size() == 0, "bitset_size=" + bits.size());
        } catch (Throwable t) {
            recordRuntimeCheck(key, false, "error=" + t.getClass().getSimpleName());
        }
    }

    private void verifyTerrainDiffusionModelBytes() {
        final String key = "terrain_diffusion_optimized_model_bytes_released";
        try {
            Class<?> modelsType = Class.forName("com.github.xandergos.terraindiffusionmc.pipeline.PipelineModels");
            Object models = modelsType.getMethod("getInstance").invoke(null);
            if (models == null) { recordRuntimeCheck(key, false, "models_instance=null"); return; }
            Class<?> modelType = Class.forName("com.github.xandergos.terraindiffusionmc.pipeline.OnnxModel");
            var bytes = modelType.getDeclaredField("optimizedModelBytes");
            bytes.setAccessible(true);
            int retained = 0;
            for (String getter : List.of("getCoarseModel", "getBaseModel", "getDecoderModel")) {
                if (bytes.get(modelsType.getMethod(getter).invoke(models)) != null) retained++;
            }
            recordRuntimeCheck(key, retained == 0, "retained_models=" + retained);
        } catch (Throwable t) {
            recordRuntimeCheck(key, false, "error=" + t.getClass().getSimpleName());
        }
    }

    private void verifyTemplatePositionInterner() {
        final String key = "template_position_interner_identity";
        try {
            Class<?> type = Class.forName("com.github.xandergos.terraindiffusionmc.world.structure.TemplatePositionInterner");
            var intern = type.getMethod("intern", BlockPos.class);
            Object first = intern.invoke(null, new BlockPos(7, 12, 19));
            Object second = intern.invoke(null, new BlockPos(7, 12, 19));
            recordRuntimeCheck(key, first == second, "same_reference=" + (first == second));
        } catch (Throwable t) {
            recordRuntimeCheck(key, false, "error=" + t.getClass().getSimpleName());
        }
    }

    private void recordRuntimeCheck(String key, boolean passed, String detail) {
        runtimeChecks.put(key, (passed ? "pass:" : "fail:") + detail);
        if (!passed) failures.add("runtime_check:" + key + ":" + detail);
    }

    private void preFrame() { renderPreNs = System.nanoTime(); beginGpuTimestamp(); }

    private void frame() {
        endGpuTimestamp();
        pollGpuTimestamps();
        if (finished || readyAtNs == 0L) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        long now = System.nanoTime();
        long elapsed = now - readyAtNs;
        if (elapsed < warmupNs) { if (!screenshotTaken && elapsed >= warmupNs / 2L) { screenshotTaken = true; screenshotRequested = true; LOG.info("Optimization benchmark requesting screenshot: {}", screenshotPath(mc)); Screenshot.grab(mc.gameDirectory, "optimization-bench-scene.png", mc.getMainRenderTarget(), message -> LOG.info("Benchmark screenshot: {}", message.getString())); } return; }
        if (!measuring) {
            measuring = true;
            snapshotStart();
            LOG.info("Optimization benchmark sample phase started");
            sampleWindowFpsLimitStart = mc.getWindow().getFramerateLimit();
            lastFrameNs = now;
            return;
        }
        boolean invalid = mc.screen != null || mc.getOverlay() != null || mc.isPaused() || isIconified(mc.getWindow());
        sampleRenderFrames++;
        if (!mc.isWindowActive()) sampleInactiveFrames++;
        if (invalid) { sampleInvalidFrames++; lastFrameNs = now; return; }
        long delta = now - lastFrameNs;
        lastFrameNs = now;
        if (elapsed <= warmupNs + sampleNs) {
            frameTimesNs.add(delta);
            if (renderPreNs > 0L && now >= renderPreNs) renderWorkNs.add(now - renderPreNs);
        } else if (modelSmoke) {
            startModelSmoke(mc);
        } else {
            finish();
        }
    }

    /** Runs only after timing/heap capture, so visual compatibility cannot alter A/B metrics. */
    private void startModelSmoke(Minecraft mc) {
        if (modelSmokeStarted) return;
        captureTimingEnd();
        var brass = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "brass_block"));
        boolean brassPresent = brass != null && brass != Blocks.AIR;
        recordRuntimeCheck("model_smoke_create_brass_registered", brassPresent, "registered=" + brassPresent);
        if (brassPresent) {
            boolean nonMissingModel = mc.getBlockRenderer().getBlockModel(brass.defaultBlockState()) != mc.getModelManager().getMissingModel();
            recordRuntimeCheck("model_smoke_create_brass_model", nonMissingModel, "missing=" + !nonMissingModel);
        }
        modelSmokeStarted = true;
        measuring = false;
        mc.getSingleplayerServer().execute(() -> installModelSmoke(mc.getSingleplayerServer().overworld()));
        LOG.info("Optimization benchmark starting post-sample model smoke");
    }

    private void applyMeasurementOptions(Minecraft mc) {
        originalPauseOnLostFocus = mc.options.pauseOnLostFocus;
        originalCap = mc.options.framerateLimit().get();
        originalVsync = mc.options.enableVsync().get();
        originalGraphics = String.valueOf(mc.options.graphicsMode().get());
        mc.options.pauseOnLostFocus = false;
        mc.options.enableVsync().set(false);
        mc.options.framerateLimit().set(260); // Minecraft's maximum slider value represents Unlimited.
    }

    private static void installScene(ServerLevel level, ServerPlayer player, int terrainRadius) {
        // In normal-world mode this exercises deterministic fresh terrain generation before rendering.
        for (int z = -terrainRadius; z <= terrainRadius; z++) for (int x = -terrainRadius; x <= terrainRadius; x++) level.getChunk(x, z);
        // A deliberately static scene: 65x65 opaque floor, leaf-heavy clusters, depth geometry, and 150 item frames.
        for (int x = -32; x <= 32; x++) for (int z = -24; z <= 40; z++) level.setBlock(new BlockPos(x, SCENE_Y - 3, z), Blocks.STONE.defaultBlockState(), 3);
        for (int x = -30; x <= 30; x += 6) for (int z = -2; z <= 36; z += 6) {
            for (int dx = -2; dx <= 2; dx++) for (int dy = 0; dy < 5; dy++) for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy - 2) < 5) level.setBlock(new BlockPos(x + dx, SCENE_Y - 2 + dy, z + dz), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 3);
            }
        }
        for (int x = -28; x <= 28; x += 7) for (int z = 0; z <= 35; z += 7) for (int y = 64; y < 74; y++) level.setBlock(new BlockPos(x, y + (SCENE_Y - 64), z), Blocks.COBBLESTONE.defaultBlockState(), 3);
        int frames = 0;
        for (int row = 0; row < 10; row++) for (int column = 0; column < 15; column++) {
            BlockPos support = new BlockPos(-14 + column * 2, SCENE_Y - 2 + row, 11);
            level.setBlock(support, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
            ItemFrame frame = new ItemFrame(level, support.relative(Direction.NORTH), Direction.NORTH);
            frame.setItem(new ItemStack((column + row) % 2 == 0 ? Items.DIAMOND : Items.ENCHANTED_GOLDEN_APPLE));
            if (level.addFreshEntity(frame)) { frames++; ACCEPTED_FRAMES.incrementAndGet(); }
        }
        level.setDayTime(6000L);
        if (player != null) { player.getAbilities().mayfly=true; player.getAbilities().flying=true; player.onUpdateAbilities(); player.setDeltaMovement(0,0,0); player.teleportTo(0.5D, SCENE_Y, -20.5D); }
        LOG.info("Optimization benchmark scene installed: itemFrames={}, seed={}", frames, SEED);
        SCENE_READY.set(true);
    }

    private static void installModelSmoke(ServerLevel level) {
        // A small grid exercises the selected connected-texture pack without
        // changing the timed leaf/item-frame scene.
        for (int x = -5; x <= 5; x++) for (int y = 0; y < 4; y++) {
            BlockPos at = new BlockPos(x, SCENE_Y + 1 + y, 0);
            level.setBlock(at, (x + y) % 3 == 0 ? Blocks.GLASS.defaultBlockState() : Blocks.BRICKS.defaultBlockState(), 3);
        }
        var modded = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "brass_block"));
        if (modded != null && modded != Blocks.AIR) level.setBlock(new BlockPos(0, SCENE_Y + 5, 0), modded.defaultBlockState(), 3);
        Cow cow = new Cow(EntityType.COW, level); cow.moveTo(-3.0D, SCENE_Y - 2, -7.0D, 20.0F, 0.0F); level.addFreshEntity(cow);
        Villager villager = new Villager(EntityType.VILLAGER, level); villager.moveTo(3.0D, SCENE_Y - 2, -7.0D, -20.0F, 0.0F); level.addFreshEntity(villager);
        MODEL_SMOKE_READY.set(true);
    }

    private void snapshotStart() {
        gcCountStart = gcCount(); gcTimeStart = gcTime(); processCpuStart = processCpu();
        heapStartUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); heapStartCommitted = Runtime.getRuntime().totalMemory();
    }

    private void fail(String reason) { failures.add(reason); finish(); }

    private void captureTimingEnd() {
        if (timingEndCaptured) return;
        timingEndCaptured = true;
        timingEndUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        timingBeforeExplicitGc = timingEndUsed;
        System.gc(); // retention sample; taken before optional visual smoke.
        timingPostExplicitGc = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        timingEndCommitted = Runtime.getRuntime().totalMemory();
        timingGcCollections = gcCount() - gcCountStart;
        timingGcMillis = gcTime() - gcTimeStart;
        timingProcessCpuNanos = processCpuDelta();
    }

    private void finish() {
        if (finished) return;
        captureTimingEnd();
        finished = true;
        try { writeReceipt(); } catch (IOException e) { LOG.error("Could not write benchmark receipt", e); }
        try { cleanupGpuQueries(); } catch (Throwable ignored) {}
        Minecraft.getInstance().stop();
    }

    private void writeReceipt() throws IOException {
        Minecraft mc = Minecraft.getInstance(); Window window = mc.getWindow();
        sampleWindowFpsLimitEnd = window.getFramerateLimit();
        Path parent = output.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);
        boolean expectedResolution = window.getWidth() == 1920 && window.getHeight() == 1080;
        boolean enoughFrames = frameTimesNs.size() >= Math.max(30, (int) (sampleNs / 100_000_000L));
        boolean screenshotExists = screenshotRequested && Files.exists(screenshotPath(mc));
        boolean success = failures.isEmpty() && SCENE_READY.get() && ACCEPTED_FRAMES.get() == 150 && mc.level != null && mc.player != null && enoughFrames && expectedResolution && sampleInvalidFrames == 0 && !sampleInvalidStateObserved && !isIconified(window) && screenshotExists;
        pollGpuTimestamps();
        writeFramesCsv();
        writeRenderWorkCsv();
        try (BufferedWriter w = Files.newBufferedWriter(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write("{\n");
            field(w,"success",String.valueOf(success),false); field(w,"status",quote(success ? "ok" : "failed"),true);
            field(w,"createdAt",quote(Instant.now().toString()),true); field(w,"process_uptime_ms",Long.toString(ManagementFactory.getRuntimeMXBean().getUptime()),false); field(w,"startup_create_requested_ms",Long.toString((createRequestedAtNs-launchedAtNs)/1_000_000L),false); field(w,"startup_client_world_ready_ms",Long.toString((clientWorldReadyAtNs-launchedAtNs)/1_000_000L),false); field(w,"startup_scene_ready_ms",Long.toString((sceneReadyAtNs-launchedAtNs)/1_000_000L),false); field(w,"scope",quote("fixed-seed terrain-diffusion warm-up followed by a stationary client render sample; gameplay workloads excluded"),true);
            field(w,"world",quote(worldName),true); field(w,"seed",Long.toString(SEED),false); field(w,"terrain_diffusion_world",String.valueOf(terrainDiffusionWorld),false); field(w,"terrain_radius_chunks",Integer.toString(terrainRadius),false); field(w,"terrain_requested_chunks",Integer.toString((terrainRadius * 2 + 1) * (terrainRadius * 2 + 1)),false);
            field(w,"sceneReady",String.valueOf(SCENE_READY.get()),false); field(w,"scene",quote("flat_floor_65x65; leaf_clusters; cobblestone_depth_grid; item_frames_expected=150; item_frames_accepted="+ACCEPTED_FRAMES.get()+""),true);
            field(w,"render_work_csv",quote(output.toAbsolutePath().resolveSibling("render-work.csv").toString()),true);
            field(w,"frame_count",Integer.toString(frameTimesNs.size()),false); field(w,"render_work_count",Integer.toString(renderWorkNs.size()),false); field(w,"gpu_timing_requested",String.valueOf(gpuTimingRequested),false); field(w,"gpu_timing_supported",String.valueOf(gpuTimingSupported),false); field(w,"gpu_work_count",Integer.toString(gpuWorkNs.size()),false); field(w,"gpu_pending_queries",Integer.toString(gpuPendingCount()),false); field(w,"mean_gpu_ms",gpuTimingSupported ? Double.toString(meanMs(gpuWorkNs)) : "null",false); field(w,"p95_gpu_ms",gpuTimingSupported ? Double.toString(percentileMs(gpuWorkNs, 0.95)) : "null",false); field(w,"p99_gpu_ms",gpuTimingSupported ? Double.toString(percentileMs(gpuWorkNs, 0.99)) : "null",false); field(w,"mean_render_ms",Double.toString(meanMs(renderWorkNs)),false); field(w,"p95_render_ms",Double.toString(percentileMs(renderWorkNs, 0.95)),false); field(w,"p99_render_ms",Double.toString(percentileMs(renderWorkNs, 0.99)),false); field(w,"mean_fps",Double.toString(meanFps()),false); field(w,"p95_frame_ms",Double.toString(percentileMs(0.95)),false); field(w,"p99_frame_ms",Double.toString(percentileMs(0.99)),false); field(w,"raw_frames_csv",quote(output.toAbsolutePath().resolveSibling("frametimes.csv").toString()),true); field(w,"warmupSeconds",Long.toString(warmupNs / 1_000_000_000L),false); field(w,"sampleSeconds",Long.toString(sampleNs / 1_000_000_000L),false);
            field(w,"heap_start_used_bytes",Long.toString(heapStartUsed),false); field(w,"heap_start_committed_bytes",Long.toString(heapStartCommitted),false); field(w,"heap_end_used_bytes",Long.toString(timingEndUsed),false); field(w,"heap_before_explicit_gc_bytes",Long.toString(timingBeforeExplicitGc),false); field(w,"heap_post_explicit_gc_bytes",Long.toString(timingPostExplicitGc),false); field(w,"heap_end_committed_bytes",Long.toString(timingEndCommitted),false); field(w,"gcCollectionsDelta",Long.toString(timingGcCollections),false); field(w,"gcTimeMillisDelta",Long.toString(timingGcMillis),false); field(w,"processCpuNanosDelta",Long.toString(timingProcessCpuNanos),false);
            field(w,"framebuffer",quote(mc.getMainRenderTarget().width+"x"+mc.getMainRenderTarget().height),true); field(w,"window",quote(window.getScreenWidth()+"x"+window.getScreenHeight()),true); field(w,"expected_resolution",String.valueOf(expectedResolution),false); field(w,"sample_invalid_frames",Integer.toString(sampleInvalidFrames),false); field(w,"sample_invalid_state_observed",String.valueOf(sampleInvalidStateObserved),false); field(w,"sample_render_frames",Integer.toString(sampleRenderFrames),false); field(w,"sample_inactive_frames",Integer.toString(sampleInactiveFrames),false); field(w,"screenshot_path",quote(screenshotPath(mc).toString()),true); field(w,"screenshot_exists",String.valueOf(screenshotExists),false); field(w,"model_smoke_requested",String.valueOf(modelSmoke),false); field(w,"model_smoke_screenshot_path",quote(modelSmokeScreenshotPath(mc).toString()),true); field(w,"model_smoke_screenshot_exists",String.valueOf(modelSmokeScreenshotRequested && Files.exists(modelSmokeScreenshotPath(mc))),false); field(w,"accepted_item_frames",Integer.toString(ACCEPTED_FRAMES.get()),false); field(w,"observed_client_item_frames",Integer.toString(observedClientItemFrames),false); field(w,"windowActive",String.valueOf(mc.isWindowActive()),false); field(w,"paused",String.valueOf(mc.isPaused()),false);
            field(w,"window_fps_limit_sample_start",Integer.toString(sampleWindowFpsLimitStart),false); field(w,"window_fps_limit_sample_end",Integer.toString(sampleWindowFpsLimitEnd),false); field(w,"minecraft_fps_final",Integer.toString(mc.getFps()),false); field(w,"effectiveVsync",String.valueOf(mc.options.enableVsync().get()),false); field(w,"effectiveFpsLimit",Integer.toString(mc.options.framerateLimit().get()),false); field(w,"originalVsync",String.valueOf(originalVsync),false); field(w,"originalFpsLimit",Integer.toString(originalCap),false); field(w,"originalGraphics",quote(originalGraphics),true);
            field(w,"gl_context_handle",Long.toUnsignedString(GLFW.glfwGetCurrentContext()),true); field(w,"glVendor",quote(safeGl(7936)),true); field(w,"glRenderer",quote(safeGl(7937)),true); field(w,"glVersion",quote(safeGl(7938)),true);
            w.write("  \"runtime_checks\": {"); int runtimeCheckIndex = 0; for (var entry : runtimeChecks.entrySet()) { if (runtimeCheckIndex++ > 0) w.write(','); w.write("\n    " + quote(entry.getKey()) + ": " + quote(entry.getValue())); } if (!runtimeChecks.isEmpty()) w.write("\n  "); w.write("},\n");
            w.write("  \"failures\": ["); for(int i=0;i<failures.size();i++){if(i>0)w.write(',');w.write(quote(failures.get(i)));} w.write("],\n  \"frameTimesNs\": ["); for(int i=0;i<frameTimesNs.size();i++){if(i>0)w.write(',');w.write(Long.toString(frameTimesNs.get(i)));} w.write("],\n  \"renderWorkNs\": ["); for(int i=0;i<renderWorkNs.size();i++){if(i>0)w.write(',');w.write(Long.toString(renderWorkNs.get(i)));} w.write("],\n  \"gpuWorkNs\": ["); for(int i=0;i<gpuWorkNs.size();i++){if(i>0)w.write(',');w.write(Long.toString(gpuWorkNs.get(i)));} w.write("]\n}\n");
        }
    }
    private void beginGpuTimestamp() {
        if (!measuring || finished || !gpuTimingRequested) return;
        initializeGpuTiming();
        if (!gpuTimingSupported || gpuOpenSlot >= 0) return;
        for (int i = 0; i < GPU_QUERY_RING_SIZE; i++) {
            int slot = (gpuCursor + i) % GPU_QUERY_RING_SIZE;
            if (!gpuPending[slot]) { gpuOpenSlot = slot; GL33.glQueryCounter(gpuStartQueries[slot], GL33.GL_TIMESTAMP); return; }
        }
    }
    private void endGpuTimestamp() {
        if (gpuOpenSlot >= 0) { GL33.glQueryCounter(gpuEndQueries[gpuOpenSlot], GL33.GL_TIMESTAMP); gpuPending[gpuOpenSlot] = true; gpuCursor = (gpuOpenSlot + 1) % GPU_QUERY_RING_SIZE; gpuOpenSlot = -1; }
    }
    private void initializeGpuTiming() {
        if (gpuTimingInitialized) return;
        gpuTimingInitialized = true;
        try {
            if (!GL.getCapabilities().OpenGL33) return;
            for (int i = 0; i < GPU_QUERY_RING_SIZE; i++) { gpuStartQueries[i] = GL15.glGenQueries(); gpuEndQueries[i] = GL15.glGenQueries(); }
            gpuTimingSupported = true;
        } catch (Throwable ignored) { cleanupGpuQueries(); gpuTimingSupported = false; }
    }
    private void pollGpuTimestamps() {
        if (!gpuTimingSupported) return;
        for (int i = 0; i < GPU_QUERY_RING_SIZE; i++) if (gpuPending[i] && GL15.glGetQueryObjecti(gpuEndQueries[i], GL15.GL_QUERY_RESULT_AVAILABLE) == GL11.GL_TRUE) {
            long start = GL33.glGetQueryObjecti64(gpuStartQueries[i], GL15.GL_QUERY_RESULT);
            long end = GL33.glGetQueryObjecti64(gpuEndQueries[i], GL15.GL_QUERY_RESULT);
            gpuPending[i] = false; if (end >= start) gpuWorkNs.add(end - start);
        }
    }
    private int gpuPendingCount() { int count=0; for (boolean pending : gpuPending) if (pending) count++; return count; }
    private void cleanupGpuQueries() { for (int i=0;i<GPU_QUERY_RING_SIZE;i++) { if (gpuStartQueries[i] != 0) GL15.glDeleteQueries(gpuStartQueries[i]); if (gpuEndQueries[i] != 0) GL15.glDeleteQueries(gpuEndQueries[i]); gpuStartQueries[i]=0; gpuEndQueries[i]=0; gpuPending[i]=false; } }

    private static boolean clientSceneBlocksPresent(Minecraft mc) { return mc.level.getBlockState(new BlockPos(0,SCENE_Y - 3,0)).is(Blocks.STONE) && mc.level.getBlockState(new BlockPos(-30,SCENE_Y,-2)).is(Blocks.OAK_LEAVES); }
    private static boolean isIconified(Window window) { return GLFW.glfwGetWindowAttrib(window.getWindow(), GLFW.GLFW_ICONIFIED) != 0; }
    private static Path screenshotPath(Minecraft mc) { return mc.gameDirectory.toPath().resolve("screenshots").resolve("optimization-bench-scene.png"); }
    private static Path modelSmokeScreenshotPath(Minecraft mc) { return mc.gameDirectory.toPath().resolve("screenshots").resolve("optimization-bench-model-smoke.png"); }
    private static double meanMs(List<Long> values){ if(values.isEmpty())return 0; long total=0;for(long ns:values)total+=ns;return total/(double)values.size()/1_000_000.0; }
    private static double percentileMs(List<Long> values,double p){ if(values.isEmpty())return 0; List<Long> s=new ArrayList<>(values);s.sort(Long::compare);return s.get(Math.min(s.size()-1,(int)Math.ceil(p*s.size())-1))/1_000_000.0; }
    private void writeFramesCsv() throws IOException { Path csv=output.toAbsolutePath().resolveSibling("frametimes.csv"); try (BufferedWriter w=Files.newBufferedWriter(csv,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)) { w.write("frame_ms\n"); for (long ns : frameTimesNs) w.write(String.format(Locale.ROOT,"%.6f\n",ns/1_000_000.0)); } }
    private void writeRenderWorkCsv() throws IOException { Path csv=output.toAbsolutePath().resolveSibling("render-work.csv"); try (BufferedWriter w=Files.newBufferedWriter(csv,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)) { w.write("render_ms\n"); for (long ns : renderWorkNs) w.write(String.format(Locale.ROOT,"%.6f\n",ns/1_000_000.0)); } }
    private double meanFps(){ if(frameTimesNs.isEmpty())return 0; long total=0;for(long ns:frameTimesNs)total+=ns;return frameTimesNs.size()*1_000_000_000.0/total; }
    private double percentileMs(double p){ if(frameTimesNs.isEmpty())return 0; List<Long> s=new ArrayList<>(frameTimesNs);s.sort(Long::compare);return s.get(Math.min(s.size()-1,(int)Math.ceil(p*s.size())-1))/1_000_000.0; }
    private static void field(BufferedWriter w,String key,String value,boolean string) throws IOException { w.write("  \""+key+"\": "+value+",\n"); }
    private static String quote(String s){ return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\""; }
    private static String safeGl(int what){ try{return String.valueOf(GlStateManager._getString(what));}catch(Throwable t){return "unavailable:"+t.getClass().getSimpleName();} }
    private static long secondsProperty(String key,long fallback){try{return Math.max(1L,Long.parseLong(System.getProperty(key,Long.toString(fallback))));}catch(NumberFormatException e){return fallback;}}
    private static int intProperty(String key, int fallback, int minimum, int maximum) { try { return Math.max(minimum, Math.min(maximum, Integer.parseInt(System.getProperty(key, Integer.toString(fallback))))); } catch (NumberFormatException ignored) { return fallback; } }
    private static long gcCount(){long n=0;for(GarbageCollectorMXBean b:ManagementFactory.getGarbageCollectorMXBeans())if(b.getCollectionCount()>0)n+=b.getCollectionCount();return n;}
    private static long gcTime(){long n=0;for(GarbageCollectorMXBean b:ManagementFactory.getGarbageCollectorMXBeans())if(b.getCollectionTime()>0)n+=b.getCollectionTime();return n;}
    private static long processCpu(){var os=ManagementFactory.getOperatingSystemMXBean();return os instanceof OperatingSystemMXBean x ? x.getProcessCpuTime() : -1L;}
    private long processCpuDelta(){long now=processCpu();return processCpuStart<0||now<0?-1L:now-processCpuStart;}
}
