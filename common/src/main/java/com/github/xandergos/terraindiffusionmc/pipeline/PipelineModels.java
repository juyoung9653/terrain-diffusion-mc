package com.github.xandergos.terraindiffusionmc.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Holds the three ONNX models used by WorldPipeline. Loaded once at mod init
 * and shared across pipeline instances. Load runs on a background thread so
 * the game thread is not blocked during startup.
 */
public final class PipelineModels implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineModels.class);

    private static final String COARSE_FILE_NAME = "coarse_model.onnx";
    private static final String BASE_FILE_NAME = "base_model.onnx";
    private static final String DECODER_FILE_NAME = "decoder_model.onnx";

    private static volatile PipelineModels INSTANCE;
    private static volatile CountDownLatch loadDone;
    private static boolean loadStarted;
    private static boolean stopping;
    private static volatile Throwable loadFailure;

    private final OnnxModel coarseModel;
    private final OnnxModel baseModel;
    private final OnnxModel decoderModel;

    /** Starts loading models on a background thread. Returns immediately. */
    public static synchronized void load() {
        if (stopping) return;
        if (INSTANCE != null) return;
        if (loadStarted) return;
        // A failed native runtime cannot be recovered by immediately trying the
        // same load again. Keep its cause available to every caller for this JVM.
        if (loadFailure != null) return;
        loadStarted = true;
        CountDownLatch completion = new CountDownLatch(1);
        loadDone = completion;
        Thread t = new Thread(() -> {
            try {
                LOG.info("Loading terrain-diffusion ML models (background)...");
                long start = System.currentTimeMillis();
                PipelineModels models = new PipelineModels();
                long elapsed = System.currentTimeMillis() - start;
                completeLoad(completion, models, null);
                LOG.info("Terrain-diffusion ML models loaded in {} ms", elapsed);
            } catch (Throwable e) {
                completeLoad(completion, null, e);
                LOG.error("Failed to load terrain-diffusion models", e);
            }
        }, "terrain-diffusion-models");
        t.setDaemon(true);
        t.start();
    }

    /** Completes exactly the load attempt that owns {@code completion}. */
    private static void completeLoad(CountDownLatch completion, PipelineModels models, Throwable failure) {
        synchronized (PipelineModels.class) {
            if (failure == null) {
                INSTANCE = models;
            } else {
                loadFailure = failure;
            }
            loadStarted = false;
            completion.countDown();
        }
    }

    /** Blocks until models are loaded (or load failed). Starts load if not started. */
    public static void awaitLoad() {
        CountDownLatch latch;
        synchronized (PipelineModels.class) {
            if (INSTANCE != null) return;
            if (!loadStarted) load();
            latch = loadDone;
        }
        if (latch != null) {
            try {
                if (!latch.await(10, TimeUnit.MINUTES)) {
                    throw new RuntimeException("Timed out waiting for terrain-diffusion models");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for terrain-diffusion models", e);
            }
        }
        synchronized (PipelineModels.class) {
            if (INSTANCE != null) return;
            Throwable failureCause = loadFailure;
            if (failureCause != null) {
                throw new IllegalStateException("Failed to load terrain-diffusion models", failureCause);
            }
            throw new IllegalStateException("Terrain-diffusion models did not load");
        }
    }

    public static PipelineModels getInstance() {
        return INSTANCE;
    }

    /** No new native session construction during process teardown; wait outside the model lock. */
    public static void stopLoadingAndAwait() {
        CountDownLatch completion;
        synchronized (PipelineModels.class) {
            stopping = true;
            completion = loadDone;
        }
        if (completion == null) return;
        boolean interrupted = false;
        for (;;) {
            try { completion.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private PipelineModels() {
        ModelAssetManager.ensureAssetsReady();
        this.coarseModel = new OnnxModel(ModelAssetManager.resolveAssetPath(COARSE_FILE_NAME), "coarse");
        this.baseModel = new OnnxModel(ModelAssetManager.resolveAssetPath(BASE_FILE_NAME), "base");
        this.decoderModel = new OnnxModel(ModelAssetManager.resolveAssetPath(DECODER_FILE_NAME), "decoder");
    }

    public OnnxModel getCoarseModel() { return coarseModel; }
    public OnnxModel getBaseModel() { return baseModel; }
    public OnnxModel getDecoderModel() { return decoderModel; }

    @Override
    public void close() {
        synchronized (PipelineModels.class) {
            if (INSTANCE != this) return;
            LOG.info("Closing terrain-diffusion ML models");
            try {
                coarseModel.close();
                baseModel.close();
                decoderModel.close();
            } finally {
                INSTANCE = null;
                loadStarted = false;
                loadFailure = null;
                loadDone = null;
            }
        }
    }
}
