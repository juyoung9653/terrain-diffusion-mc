package com.terrain.fpsprobe;

import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;

/**
 * Measures an existing clone of a saved world.  No world is created, and no
 * settings are written to disk.  GPU timestamps are elapsed GPU-timeline time
 * between frame-event boundaries; they are not a GPU-busy counter.
 */
public final class FpsProbeClient {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int RING = 32;
    private static FpsProbeClient INSTANCE;
    private final String world = System.getProperty("fpsprobe.world", "fps-scene");
    private final Path output = Path.of(System.getProperty("fpsprobe.output", "fps-probe-receipt.json"));
    private final long warmupNs = seconds("fpsprobe.warmup", 45) * 1_000_000_000L;
    private final long sampleNs = seconds("fpsprobe.seconds", 90) * 1_000_000_000L;
    private static final long SECTIONS_STABLE_NS = 10_000_000_000L;
    private static final long MAX_EXTRA_SETTLE_NS = 120_000_000_000L;
    private final boolean wantedVsync = Boolean.parseBoolean(System.getProperty("fpsprobe.vsync", "true"));
    private final boolean renderLivingEntities = Boolean.parseBoolean(System.getProperty("fpsprobe.entities", "true"));
    private final boolean spectator = Boolean.parseBoolean(System.getProperty("fpsprobe.spectator", "false"));
    private final boolean nightVision = Boolean.parseBoolean(System.getProperty("fpsprobe.nightVision", "false"));
    private final int wantedCap = integer("fpsprobe.cap", 70, 10, 260);
    private final double x = decimal("fpsprobe.x", 39.587), y = decimal("fpsprobe.y", 99.88247), z = decimal("fpsprobe.z", -572.153);
    private final float yaw = (float)decimal("fpsprobe.yaw", -129.3), pitch = (float)decimal("fpsprobe.pitch", 36.6);
    private final List<Row> rows = new ArrayList<>();
    private final List<Long> swapWallNs = new ArrayList<>(), swapCpuNs = new ArrayList<>();
    private final int[] qStart = new int[RING], qEnd = new int[RING];
    private final boolean[] pending = new boolean[RING];
    private final long[] queryFrame = new long[RING];
    private final ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
    private boolean requested, configured, handledLoadingScreen, measuring, finished, gpuInit, gpuSupported;
    private volatile boolean frozen;
    private volatile long loadedNs;
    private long measureNs, lastPostNs, preWallNs, preCpuNs, sequence;
    private long sectionsTrueSinceNs = -1L, warmupElapsedAtSampleNs = -1L;
    private Instant sampleStartUtc;
    private volatile boolean serverPreFrozen, sceneConfigured;
    private int nativeIntervalBefore = -1, nativeIntervalAfter = -1;
    private volatile String entitySnapshotStartSha = "", entitySnapshotEndSha = "";
    private volatile int entitySnapshotStartCount = -1, entitySnapshotEndCount = -1;
    private long swapWallStart=-1, swapCpuStart=-1;
    private int open = -1, cursor, invalid, inactive, menu, gpuErrors, gpuDropped;
    private boolean originalVsync; private int originalCap; private String failure = "";
    private boolean sectionsSettled, warmupScreenshotRequested;

    static void install() {
        if (INSTANCE != null) return;
        INSTANCE = new FpsProbeClient();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> INSTANCE.tick());
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e) -> INSTANCE.pre());
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post e) -> INSTANCE.post());
        NeoForge.EVENT_BUS.addListener((RenderLivingEvent.Pre<?, ?> e) -> INSTANCE.hideLiving(e));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> INSTANCE.serverStarted(e));
    }

    /** ServerStarted runs before the first normal integrated-server tick after open. */
    private void serverStarted(ServerStartedEvent event) {
        MinecraftServer server=event.getServer();
        if (!requested || !server.isSingleplayer()) return;
        ServerLevel level=server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
        level.setDayTime(6000L); level.setWeatherParameters(0, 0, false, false);
        loadedNs=System.nanoTime(); server.tickRateManager().setFrozen(true); frozen=true; serverPreFrozen=true;
        LOG.info("FPS probe froze integrated server at ServerStartedEvent");
    }

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (finished) return;
        if (mc.screen instanceof net.neoforged.neoforge.client.gui.LoadingErrorScreen && !handledLoadingScreen) {
            handledLoadingScreen = true;
            var issues = net.neoforged.fml.ModLoader.getLoadingIssues();
            for (var issue : issues) LOG.warn("FPS probe loading issue: {} {}", issue.severity(), net.neoforged.fml.i18n.FMLTranslations.translateIssue(issue));
            if (issues.isEmpty() || issues.stream().anyMatch(issue -> issue.severity() == net.neoforged.fml.ModLoadingIssue.Severity.ERROR)) { fail("mod_loading_error"); return; }
            String continueLabel = net.neoforged.fml.i18n.FMLTranslations.parseMessage("fml.button.continue.launch");
            for (var child : mc.screen.children()) if (child instanceof net.minecraft.client.gui.components.Button button && button.active && button.getMessage().getString().equals(continueLabel)) { LOG.warn("FPS probe acknowledging warning-only loading screen"); button.onPress(); return; }
            fail("loading_warning_continue_unavailable"); return;
        }
        if (!requested && mc.level == null && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            if (!mc.getLevelSource().levelExists(world)) { fail("clone_world_missing:" + world); return; }
            requested = true;
            LOG.info("FPS probe opening existing cloned world: {}", world);
            mc.createWorldOpenFlows().openWorld(world, () -> fail("open_world_failed"));
        }
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        if (!configured) {
            configured = true; configure(mc);
            var server = mc.getSingleplayerServer();
            var player = server.getPlayerList().getPlayer(mc.player.getUUID());
            server.execute(() -> stabilize(server.overworld(), player));
        }
        keepCamera(mc);
    }

    private void configure(Minecraft mc) {
        originalVsync = mc.options.enableVsync().get(); originalCap = mc.options.framerateLimit().get();
        mc.options.pauseOnLostFocus = false;
        mc.options.enableVsync().set(wantedVsync); mc.options.framerateLimit().set(wantedCap); initGpu();
        nativeIntervalBefore = nativeSwapInterval();
        mc.getWindow().updateVsync(wantedVsync);
        mc.getWindow().setFramerateLimit(wantedCap);
        nativeIntervalAfter = nativeSwapInterval();
        LOG.info("FPS probe native WGL swap interval: before={} after={}", nativeIntervalBefore, nativeIntervalAfter);
        LOG.info("FPS probe options applied in memory only: vsync={} cap={}", wantedVsync, wantedCap);
    }
    private void hideLiving(RenderLivingEvent.Pre<?, ?> event) { if (configured && !renderLivingEntities) event.setCanceled(true); }
    private void stabilize(ServerLevel level, ServerPlayer player) {
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
        level.setDayTime(6000L); level.setWeatherParameters(0, 0, false, false);
        if (player != null) { if(spectator) player.setGameMode(GameType.SPECTATOR); if(nightVision) player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false)); player.teleportTo(level, x, y, z, yaw, pitch); player.getAbilities().mayfly=true; player.getAbilities().flying=true; player.onUpdateAbilities(); }
        level.getServer().tickRateManager().setFrozen(true);
        loadedNs=System.nanoTime(); frozen=true; sceneConfigured=true;
    }
    private void keepCamera(Minecraft mc) {
        var p=mc.player; p.getAbilities().mayfly=true; p.getAbilities().flying=true; p.setDeltaMovement(0,0,0);
        p.setPos(x,y,z); p.setYRot(yaw); p.setXRot(pitch); p.yRotO=yaw; p.xRotO=pitch;
    }
    private void pre() {
        preWallNs=System.nanoTime(); preCpuNs=cpu();
        if (measuring) beginGpu();
    }
    /** Called at the exact verified glfwSwapBuffers invocation, not inferred from frame residuals. */
    public static void swapBefore() { if (INSTANCE != null && INSTANCE.measuring) { INSTANCE.swapWallStart=System.nanoTime(); INSTANCE.swapCpuStart=cpu(); } }
    public static void swapAfter() { if (INSTANCE != null && INSTANCE.swapWallStart >= 0) { long wall=System.nanoTime()-INSTANCE.swapWallStart, after=cpu(); INSTANCE.swapWallNs.add(wall); INSTANCE.swapCpuNs.add(INSTANCE.swapCpuStart<0||after<INSTANCE.swapCpuStart?-1:after-INSTANCE.swapCpuStart); INSTANCE.swapWallStart=-1; } }
    private void post() {
        Minecraft mc=Minecraft.getInstance(); long now=System.nanoTime(); endGpu();
        if (finished || !configured) return;
        if (!measuring) {
            long warmupElapsed=now-loadedNs;
            if(sceneConfigured&&!warmupScreenshotRequested&&warmupElapsed>=warmupNs/2L){warmupScreenshotRequested=true;Screenshot.grab(mc.gameDirectory,"warmup-scene.png",mc.getMainRenderTarget(),message->LOG.info("FPS probe warmup screenshot: {}",message.getString()));}
            if(sceneConfigured&&frozen&&warmupElapsed>=warmupNs){
                if(mc.levelRenderer.hasRenderedAllSections()){if(sectionsTrueSinceNs<0L)sectionsTrueSinceNs=now;if(now-sectionsTrueSinceNs>=SECTIONS_STABLE_NS)startSample(mc,now,warmupElapsed);}else sectionsTrueSinceNs=-1L;
                if(!measuring&&warmupElapsed>=warmupNs+MAX_EXTRA_SETTLE_NS){failure="render_sections_unsettled_timeout";finish();}
            }
            return;
        }
        if (now-measureNs > sampleNs) { finish(); return; }
        boolean bad=mc.screen!=null||mc.getOverlay()!=null||mc.isPaused()||iconified(mc.getWindow());
        if(bad)invalid++; if(!mc.isWindowActive())inactive++; if(mc.screen!=null)menu++;
        long afterCpu=cpu();
        Row row=new Row(++sequence, now-lastPostNs, now-preWallNs, preCpuNs<0||afterCpu<preCpuNs?-1:afterCpu-preCpuNs); lastPostNs=now;
        rows.add(row); // GPU result is joined later by frame id without forcing completion.
        pollGpu();
    }
    private void startSample(Minecraft mc,long now,long warmupElapsed){sectionsSettled=true;warmupElapsedAtSampleNs=warmupElapsed;snapshotServer(mc.getSingleplayerServer(),"start");if(!failure.isEmpty()){finish();return;}measureNs=System.nanoTime();sampleStartUtc=Instant.now();lastPostNs=measureNs;measuring=true;LOG.info("FPS_PROBE_SAMPLE_START");}
    private void beginGpu() {
        initGpu(); if (!gpuSupported || open >= 0) return;
        for(int i=0;i<RING;i++){int s=(cursor+i)%RING;if(!pending[s]){open=s; queryFrame[s]=sequence+1; GL33.glQueryCounter(qStart[s],GL33.GL_TIMESTAMP);return;}}
        gpuDropped++;
    }
    private void endGpu() { if(open>=0){ GL33.glQueryCounter(qEnd[open],GL33.GL_TIMESTAMP); pending[open]=true; cursor=(open+1)%RING; open=-1; } }
    private void initGpu() { if(gpuInit)return; gpuInit=true; try { if(!GL.getCapabilities().OpenGL33)return; for(int i=0;i<RING;i++){qStart[i]=GL15.glGenQueries();qEnd[i]=GL15.glGenQueries();} gpuSupported=true; }catch(Throwable t){gpuErrors++;cleanupGpu();} }
    private void pollGpu() { if(!gpuSupported)return; for(int i=0;i<RING;i++) if(pending[i]&&GL15.glGetQueryObjecti(qEnd[i],GL15.GL_QUERY_RESULT_AVAILABLE)==GL11.GL_TRUE){try{long a=GL33.glGetQueryObjecti64(qStart[i],GL15.GL_QUERY_RESULT),b=GL33.glGetQueryObjecti64(qEnd[i],GL15.GL_QUERY_RESULT);pending[i]=false; Row target=row(queryFrame[i]); if(b>=a&&target!=null)target.gpuNs=b-a;else if(b<a)gpuErrors++;}catch(Throwable t){pending[i]=false;gpuErrors++;}} }
    private Row row(long n){ for(int i=rows.size()-1;i>=0;i--)if(rows.get(i).frame==n)return rows.get(i); return null; }
    private void finish() { if(finished)return; finished=true; measuring=false; pollGpu(); while(swapWallNs.size()>rows.size()){swapWallNs.removeLast();swapCpuNs.removeLast();} snapshotServer(Minecraft.getInstance().getSingleplayerServer(), "end"); try { write(); } catch(IOException e){LOG.error("FPS probe receipt write failed",e);} try{cleanupGpu();}catch(Throwable ignored){} Minecraft.getInstance().stop(); }
    private void fail(String why){failure=why;finished=true;try{write();}catch(IOException ignored){}LOG.error("FPS probe failed: {}",why);Minecraft.getInstance().stop();}
    private void write() throws IOException {
        Minecraft mc=Minecraft.getInstance(); Window w=mc.getWindow(); Path parent=output.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);
        Path csv=output.toAbsolutePath().resolveSibling("fps-frames.csv"); try(BufferedWriter b=Files.newBufferedWriter(csv,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)){b.write("frame,frame_ms,render_wall_ms,render_thread_cpu_ms,gpu_elapsed_ms\n");for(Row r:rows)b.write(String.format(Locale.ROOT,"%d,%.6f,%.6f,%.6f,%s%n",r.frame,r.frameNs/1e6,r.renderNs/1e6,r.cpuNs/1e6,r.gpuNs<0?"":String.format(Locale.ROOT,"%.6f",r.gpuNs/1e6)));}
        Path swapCsv=output.toAbsolutePath().resolveSibling("swap-times.csv"); try(BufferedWriter b=Files.newBufferedWriter(swapCsv,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)){b.write("swap_wall_ms,swap_thread_cpu_ms\n");for(int i=0;i<swapWallNs.size();i++)b.write(String.format(Locale.ROOT,"%.6f,%s%n",swapWallNs.get(i)/1e6,swapCpuNs.get(i)<0?"":String.format(Locale.ROOT,"%.6f",swapCpuNs.get(i)/1e6)));}
        Path shot=output.toAbsolutePath().resolveSibling("fps-probe.png"); Screenshot.grab(mc.gameDirectory,shot.getFileName().toString(),mc.getMainRenderTarget(),m->{});
        try(BufferedWriter b=Files.newBufferedWriter(output,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)){
            b.write("{\n"); f(b,"success",failure.isEmpty()&&rows.size()>30&&invalid==0&&inactive==0&&frozen&&serverPreFrozen&&sceneConfigured); f(b,"failure",q(failure)); f(b,"created_utc",q(Instant.now().toString())); f(b,"sample_start_utc",sampleStartUtc==null?"null":q(sampleStartUtc.toString())); f(b,"entity_snapshot_start_sha256",q(entitySnapshotStartSha)); f(b,"entity_snapshot_end_sha256",q(entitySnapshotEndSha)); f(b,"entity_snapshot_start_count",entitySnapshotStartCount); f(b,"entity_snapshot_end_count",entitySnapshotEndCount); f(b,"scope",q("existing cloned world; fixed camera; Photon and installed resource packs retained")); f(b,"world",q(world));f(b,"vsync",wantedVsync);f(b,"fps_cap",wantedCap);f(b,"effective_vsync",mc.options.enableVsync().get());f(b,"effective_fps_cap",mc.options.framerateLimit().get());f(b,"effective_window_cap",mc.getWindow().getFramerateLimit());f(b,"native_swap_interval_before_apply",nativeIntervalBefore);f(b,"native_swap_interval_after_apply",nativeIntervalAfter);f(b,"native_swap_interval_at_end",nativeSwapInterval());f(b,"living_entities_rendered",renderLivingEntities);f(b,"world_tick_frozen",frozen);f(b,"server_pre_frozen",serverPreFrozen);f(b,"warmup_seconds",warmupNs/1_000_000_000L); f(b,"render_sections_settled",sectionsSettled); f(b,"render_sections_stable_required_seconds",SECTIONS_STABLE_NS/1_000_000_000L); f(b,"render_sections_max_extra_wait_seconds",MAX_EXTRA_SETTLE_NS/1_000_000_000L); f(b,"warmup_elapsed_at_sample_seconds",warmupElapsedAtSampleNs<0?-1:warmupElapsedAtSampleNs/1_000_000_000.0); f(b,"warmup_screenshot_requested",warmupScreenshotRequested); f(b,"warmup_screenshot_name",q("warmup-scene.png"));f(b,"sample_seconds",sampleNs/1_000_000_000L);f(b,"frame_count",rows.size());f(b,"invalid_frames",invalid);f(b,"inactive_frames",inactive);f(b,"menu_frames",menu);f(b,"framebuffer",q(mc.getMainRenderTarget().width+"x"+mc.getMainRenderTarget().height));f(b,"window",q(w.getScreenWidth()+"x"+w.getScreenHeight()));f(b,"requested_camera",q(x+","+y+","+z+","+yaw+","+pitch));f(b,"actual_camera",q(mc.player==null?"unavailable":mc.player.getX()+","+mc.player.getY()+","+mc.player.getZ()+","+mc.player.getYRot()+","+mc.player.getXRot()));f(b,"world_time",mc.level==null?-1:mc.level.getDayTime());f(b,"raining",mc.level!=null&&mc.level.isRaining());f(b,"loaded_entity_count",mc.level==null?-1:count(mc.level.entitiesForRendering()));f(b,"gpu_timestamp_supported",gpuSupported);f(b,"gpu_timestamp_completed",rows.stream().filter(r->r.gpuNs>=0).count());f(b,"gpu_pending_queries",pendingCount());f(b,"gpu_query_errors",gpuErrors);f(b,"gpu_query_dropped",gpuDropped);f(b,"gpu_elapsed_definition",q("OpenGL timestamp elapsed over the frame-event interval; timeline elapsed, not a GPU-busy counter"));f(b,"swap_present_samples",swapWallNs.size()); f(b,"swap_wall_mean_ms",meanLong(swapWallNs)); f(b,"swap_wall_sum_ms",sumLong(swapWallNs)); f(b,"swap_cpu_valid_samples",swapCpuNs.stream().filter(n->n>=0).count()); f(b,"swap_cpu_mean_ms",meanLong(swapCpuNs)); f(b,"swap_cpu_sum_ms",sumLong(swapCpuNs)); f(b,"swap_cpu_clock_caveat",q("Windows CPU-time granularity can be about 15.625 ms; per-call wall-CPU subtraction is retained but not individually reliable")); f(b,"swap_csv",q(swapCsv.toString()));f(b,"swap_wall_p95_ms",pLong(swapWallNs));f(b,"swap_thread_cpu_p95_ms",pLong(swapCpuNs));f(b,"swap_non_cpu_p95_ms",pDifference(swapWallNs,swapCpuNs));f(b,"swap_present_measurement",q("exact GLFW glfwSwapBuffers wall and render-thread CPU duration; non-CPU includes blocking and OS scheduling"));f(b,"frame_p95_ms",p(rows, r->r.frameNs));f(b,"render_wall_p95_ms",p(rows,r->r.renderNs));f(b,"render_thread_cpu_p95_ms",p(rows,r->r.cpuNs));f(b,"gpu_elapsed_p95_ms",p(rows.stream().filter(r->r.gpuNs>=0).toList(),r->r.gpuNs)); b.write("  \"frames_csv\": "+q(csv.toString())+"\n}\n"); }
    }
    private void cleanupGpu(){for(int i=0;i<RING;i++){if(qStart[i]!=0)GL15.glDeleteQueries(qStart[i]);if(qEnd[i]!=0)GL15.glDeleteQueries(qEnd[i]);pending[i]=false;}}
    private int pendingCount(){int n=0;for(boolean v:pending)if(v)n++;return n;}
    private void snapshotServer(MinecraftServer server, String phase) {
        if (server == null) return;
        try {
            server.submit(() -> snapshotOnServerThread(server, phase)).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            failure="entity_snapshot_dispatch_failed:"+e.getClass().getSimpleName();
        }
    }
    private void snapshotOnServerThread(MinecraftServer server, String phase) {
        try {
                List<String> lines=new ArrayList<>();
                for(Entity entity:server.overworld().getAllEntities()) lines.add(entity.getUUID()+"|"+BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())+"|"+(entity instanceof LivingEntity?"living":"other")+"|"+String.format(Locale.ROOT,"%.6f|%.6f|%.6f",entity.getX(),entity.getY(),entity.getZ()));
                lines.sort(String::compareTo); String body=String.join("\n",lines)+"\n"; Path file=output.toAbsolutePath().resolveSibling("entity-snapshot-"+phase+".txt"); Files.createDirectories(file.getParent()); Files.writeString(file,body,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
                String hash=sha256(body); if(phase.equals("start")){entitySnapshotStartSha=hash;entitySnapshotStartCount=lines.size();}else{entitySnapshotEndSha=hash;entitySnapshotEndCount=lines.size();}
        } catch(Exception e) { LOG.error("FPS probe entity snapshot {} failed",phase,e); }
    }
    private static String sha256(String text) throws Exception { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b));return out.toString(); }
    private static int count(Iterable<?> values){int n=0;for(Object ignored:values)n++;return n;}
    private static boolean iconified(Window w){return GLFW.glfwGetWindowAttrib(w.getWindow(),GLFW.GLFW_ICONIFIED)!=0;}
    private static int nativeSwapInterval() {
        try { return org.lwjgl.opengl.WGLEXTSwapControl.wglGetSwapIntervalEXT(); }
        catch (Throwable unavailable) { return -1; }
    }
    private static long cpu(){ThreadMXBean bean=ManagementFactory.getThreadMXBean();if(!bean.isCurrentThreadCpuTimeSupported())return -1;if(!bean.isThreadCpuTimeEnabled())try{bean.setThreadCpuTimeEnabled(true);}catch(SecurityException ignored){}return bean.isThreadCpuTimeEnabled()?bean.getCurrentThreadCpuTime():-1;}
    private static long seconds(String k,long d){return Math.max(1,Long.getLong(k,d));} private static int integer(String k,int d,int lo,int hi){try{return Math.max(lo,Math.min(hi,Integer.parseInt(System.getProperty(k,""+d))));}catch(Exception e){return d;}} private static double decimal(String k,double d){try{return Double.parseDouble(System.getProperty(k,""+d));}catch(Exception e){return d;}}
    private interface Metric{long get(Row r);} private static double p(List<Row> a,Metric m){if(a.isEmpty())return -1;var v=new ArrayList<Long>();for(Row r:a)if(m.get(r)>=0)v.add(m.get(r));if(v.isEmpty())return -1;v.sort(Comparator.naturalOrder());return v.get(Math.min(v.size()-1,(int)Math.ceil(.95*v.size())-1))/1e6;}
    private static double pLong(List<Long> values){var v=new ArrayList<Long>();for(long n:values)if(n>=0)v.add(n);if(v.isEmpty())return -1;v.sort(Comparator.naturalOrder());return v.get(Math.min(v.size()-1,(int)Math.ceil(.95*v.size())-1))/1e6;}
    private static double meanLong(List<Long> values){long sum=0;int count=0;for(long n:values)if(n>=0){sum+=n;count++;}return count==0?-1:sum/(double)count/1e6;}
    private static double sumLong(List<Long> values){long sum=0;for(long n:values)if(n>=0)sum+=n;return sum/1e6;}
    private static double pDifference(List<Long> wall,List<Long> cpu){var v=new ArrayList<Long>();for(int i=0;i<Math.min(wall.size(),cpu.size());i++)if(cpu.get(i)>=0)v.add(Math.max(0,wall.get(i)-cpu.get(i)));return pLong(v);}
    private static void f(BufferedWriter b,String k,Object v)throws IOException{b.write("  \""+k+"\": "+v+",\n");} private static String q(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static final class Row{final long frame,frameNs,renderNs,cpuNs;long gpuNs=-1;Row(long f,long a,long b,long c){frame=f;frameNs=a;renderNs=b;cpuNs=c;}}
}
