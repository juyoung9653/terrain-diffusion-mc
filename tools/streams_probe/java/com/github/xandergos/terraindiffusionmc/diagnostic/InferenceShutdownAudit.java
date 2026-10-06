package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in post-save CUDA shutdown regression; excluded from production builds. */
@EventBusSubscriber(modid = "terrain_diffusion_mc", bus = EventBusSubscriber.Bus.GAME)
public final class InferenceShutdownAudit {
    private static final Logger LOG = LoggerFactory.getLogger("TerrainDiffusionShutdownAudit");
    private static LocalTerrainProvider oldProvider;
    private static boolean armed;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeStopped(ServerStoppedEvent event) {
        if (!Boolean.getBoolean("td.streamsProbe.inferenceShutdownAudit")) return;
        oldProvider = LocalTerrainProvider.getInstance();
        request(oldProvider, 123392);
        request(oldProvider, 124416);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            var state = LocalTerrainProvider.inferenceSnapshot();
            if (state.active() > 0 && state.queued() > 0) {
                armed = true;
                LOG.info("[TD-SHUTDOWN-AUDIT] armed active={} queued={} afterWorldSave=true", state.active(), state.queued());
                return;
            }
            pause();
        }
        throw new IllegalStateException("Shutdown audit did not observe active and queued inference");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterStopped(ServerStoppedEvent event) {
        if (!Boolean.getBoolean("td.streamsProbe.inferenceShutdownAudit")) return;
        var state = LocalTerrainProvider.inferenceSnapshot();
        if (!armed || state.active() != 0 || state.queued() != 0)
            throw new IllegalStateException("Inference was not drained after server stop: " + state);
        assertRejected(oldProvider);
        LOG.info("[TD-SHUTDOWN-AUDIT] gameplayDrained=true staleProviderRejected=true active=0 queued=0");
        if (Boolean.getBoolean("td.streamsProbe.inferenceReopenAudit")) {
            LocalTerrainProvider.beginGameplay();
            LocalTerrainProvider.init(event.getServer().overworld().getSeed() + 1L);
            LocalTerrainProvider reopened = LocalTerrainProvider.getInstance();
            if (reopened == oldProvider) throw new IllegalStateException("Reopen reused old provider");
            reopened.fetchHeightmap(256, 256, 512, 512);
            assertRejected(oldProvider);
            LocalTerrainProvider.shutdownGameplay();
            LOG.info("[TD-SHUTDOWN-AUDIT] sameJvmReopen=true differentSeed=true staleProviderRejected=true");
        }
        try (var preview = LocalTerrainProvider.createPreviewSession(987654321L, 2)) {
            Thread caller = new Thread(() -> preview.run(() -> {
                try { LocalTerrainProvider.getInstance().fetchHeightmap(125440, 125440, 125696, 125696); }
                catch (RuntimeException expectedCancellation) { LOG.info("[TD-SHUTDOWN-AUDIT] preview caller ended: {}", expectedCancellation.toString()); }
            }), "td-shutdown-preview-probe");
            caller.setDaemon(true);
            caller.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (LocalTerrainProvider.inferenceSnapshot().active() == 0 && System.nanoTime() < deadline) pause();
            if (LocalTerrainProvider.inferenceSnapshot().active() == 0)
                throw new IllegalStateException("Preview inference did not start");
            LocalTerrainProvider.shutdownAll();
        }
        state = LocalTerrainProvider.inferenceSnapshot();
        if (state.active() != 0 || state.queued() != 0 || state.accepting())
            throw new IllegalStateException("Application inference was not drained: " + state);
        LOG.info("[TD-SHUTDOWN-AUDIT] passed previewExitDrained=true active=0 queued=0 accepting=false");
    }

    private static void request(LocalTerrainProvider provider, int coordinate) {
        Thread caller = new Thread(() -> {
            try { provider.fetchHeightmap(coordinate, coordinate, coordinate + 256, coordinate + 256); }
            catch (RuntimeException expectedCancellation) { LOG.info("[TD-SHUTDOWN-AUDIT] caller ended: {}", expectedCancellation.toString()); }
        }, "td-shutdown-caller-" + coordinate);
        caller.setDaemon(true);
        caller.start();
    }

    private static void assertRejected(LocalTerrainProvider provider) {
        try { provider.fetchHeightmap(127488, 127488, 127744, 127744); }
        catch (java.util.concurrent.CancellationException expected) { return; }
        throw new IllegalStateException("Stopped provider accepted new work");
    }

    private static void pause() {
        try { Thread.sleep(5); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Shutdown audit interrupted", interrupted);
        }
    }
}
