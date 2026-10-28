package com.github.xandergos.terraindiffusionmc.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.security.MessageDigest;

/** Filesystem-only asset resolver for the isolated capture copy. */
public final class ModelAssetManager {
    private static final Path MODELS=Path.of(System.getProperty("td.capture.models", "")).toAbsolutePath();
    private static volatile boolean ready;
    private ModelAssetManager() { }
    public static synchronized void ensureAssetsReady() {
        if (ready) return;
        for (String name : new String[]{"coarse_model.onnx", "base_model.onnx", "decoder_model.onnx",
                "world_pipeline_config.json", "pipeline_data.json"}) {
            Path file=resolveAssetPath(name);
            if (!Files.isRegularFile(file)) throw new IllegalStateException("missing pinned capture asset: "+file);
            String expected=System.getProperty("td.capture.sha."+name);
            if (expected!=null && !expected.equals(sha256(file)))
                throw new IllegalStateException("pinned capture asset hash mismatch: "+name);
        }
        ready=true;
    }
    public static Path resolveAssetPath(String fileName) { return MODELS.resolve(fileName); }
    public static Path resolveDecoderAssetPath() { return resolveAssetPath("decoder_model.onnx"); }
    public static boolean decoderUsesPreoptimizedGpuGraph() { return Boolean.getBoolean("td.capture.preoptimizedDecoder"); }
    private static String sha256(Path file) {
        try {
            MessageDigest algorithm=MessageDigest.getInstance("SHA-256");
            try (InputStream stream=Files.newInputStream(file)) {
                byte[] bytes=new byte[1024*1024]; int read;
                while ((read=stream.read(bytes))!=-1) algorithm.update(bytes,0,read);
            }
            byte[] digest=algorithm.digest();
            StringBuilder text=new StringBuilder(); for (byte value:digest) text.append(String.format("%02x", value));
            return text.toString();
        } catch (Exception failure) { throw new IllegalStateException("cannot hash capture asset: "+file, failure); }
    }
}
