"""Compile the production OnnxModel against minimal offline ONNX/config stubs.

The harness executes the production constructor, release helper, persistent
`run` branch, and offload session-retry branch.  It needs only a JDK; neither
Gradle nor a Minecraft/ONNX native process is started.
"""

import shutil
import subprocess
import tempfile
import os
from pathlib import Path


ROOT = Path(__file__).parents[2]
ONNX_MODEL = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/OnnxModel.java"

SOURCES = {
    "org/slf4j/Logger.java": """
        package org.slf4j; public interface Logger {
          default void info(String x, Object... a) {} default void warn(String x, Object... a) {}
          default void debug(String x, Object... a) {}
        }
    """,
    "org/slf4j/LoggerFactory.java": """
        package org.slf4j; public final class LoggerFactory {
          private static final Logger L = new Logger() {}; public static Logger getLogger(Class<?> c) { return L; }
        }
    """,
    "com/github/xandergos/terraindiffusionmc/config/TerrainDiffusionConfig.java": """
        package com.github.xandergos.terraindiffusionmc.config;
        public final class TerrainDiffusionConfig {
          public static String device = "cpu"; public static boolean offload;
          public static String inferenceDevice() { return device; }
          public static boolean offloadModels() { return offload; }
        }
    """,
    "com/github/xandergos/terraindiffusionmc/pipeline/ModelAssetManager.java": """
        package com.github.xandergos.terraindiffusionmc.pipeline;
        import java.nio.file.*;
        public final class ModelAssetManager {
          public static Path root;
          public static void ensureAssetsReady() {}
          public static Path resolveAssetPath(String name) { return root.resolve(name); }
        }
    """,
    "ai/onnxruntime/OrtException.java": """
        package ai.onnxruntime; public class OrtException extends Exception { public OrtException(String m) { super(m); } }
    """,
    "ai/onnxruntime/OrtLoggingLevel.java": """
        package ai.onnxruntime; public enum OrtLoggingLevel { ORT_LOGGING_LEVEL_ERROR }
    """,
    "ai/onnxruntime/OrtEnvironment.java": """
        package ai.onnxruntime;
        import java.nio.file.*;
        public final class OrtEnvironment {
          private static final OrtEnvironment E = new OrtEnvironment(); public static int failNext;
          public static OrtEnvironment getEnvironment() { return E; }
          public static OrtEnvironment getEnvironment(OrtLoggingLevel level) { return E; }
          public OrtSession createSession(byte[] bytes, OrtSession.SessionOptions options) throws OrtException {
            if (failNext-- > 0) throw new OrtException("planned create failure");
            if (options.optimizedPath != null) try { Files.writeString(Path.of(options.optimizedPath), "optimized"); }
            catch (Exception ex) { throw new OrtException(ex.toString()); }
            return new OrtSession(bytes);
          }
        }
    """,
    "ai/onnxruntime/OrtSession.java": """
        package ai.onnxruntime;
        import java.util.*; import ai.onnxruntime.providers.*;
        public class OrtSession implements AutoCloseable {
          private final byte[] bytes; public OrtSession(byte[] b) { bytes = b.clone(); }
          public Result run(Map<String, OnnxTensor> feed) { return new Result(feed.get("x")); }
          public void close() throws OrtException {}
          public static class SessionOptions implements AutoCloseable {
            public enum OptLevel { EXTENDED_OPT, ALL_OPT } public String optimizedPath;
            public void setOptimizationLevel(OptLevel x) {} public void setOptimizedModelFilePath(String x) { optimizedPath=x; }
            public void addCUDA(OrtCUDAProviderOptions x) {} public void addDirectML(int x) {}
            public void addCoreML(java.util.EnumSet<CoreMLFlags> x) {} public void close() {}
          }
          public static class Result implements AutoCloseable { private final OnnxTensor out; Result(OnnxTensor x) { out=x; }
            public Object get(int i) { return out; } public void close() {}
          }
        }
    """,
    "ai/onnxruntime/OnnxTensor.java": """
        package ai.onnxruntime; import java.nio.*;
        public class OnnxTensor implements AutoCloseable { private final float[] data;
          public OnnxTensor(float[] d) { data=d.clone(); }
          public static OnnxTensor createTensor(OrtEnvironment e, FloatBuffer b, long[] s) throws OrtException {
            float[] d=new float[b.remaining()]; b.get(d); return new OnnxTensor(d); }
          public FloatBuffer getFloatBuffer() { return FloatBuffer.wrap(data.clone()); } public void close() {}
        }
    """,
    "ai/onnxruntime/providers/CoreMLFlags.java": """
        package ai.onnxruntime.providers; public enum CoreMLFlags { ENABLE_ON_SUBGRAPH }
    """,
    "ai/onnxruntime/providers/OrtCUDAProviderOptions.java": """
        package ai.onnxruntime.providers; public final class OrtCUDAProviderOptions implements AutoCloseable {
          public OrtCUDAProviderOptions(int x) {} public void add(String k,String v) {} public void close() {}
        }
    """,
    "Harness.java": """
        import ai.onnxruntime.*; import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
        import com.github.xandergos.terraindiffusionmc.pipeline.*; import java.lang.reflect.*; import java.nio.file.*;
        public final class Harness {
          static Field f(String name) throws Exception { Field f=OnnxModel.class.getDeclaredField(name); f.setAccessible(true); return f; }
          static Object value(OnnxModel m,String n) throws Exception { return f(n).get(m); }
          public static void main(String[] args) throws Exception {
            Path root=Files.createTempDirectory("onnx-model-retention"); ModelAssetManager.root=root;
            Path source=root.resolve("source.onnx"); Files.writeString(source,"source");
            TerrainDiffusionConfig.device="cpu"; TerrainDiffusionConfig.offload=false;
            OnnxModel persistent=new OnnxModel(source,"persistent");
            if(value(persistent,"optimizedModelBytes")!=null) throw new AssertionError("persistent bytes retained");
            float[] out=persistent.run(new Object[][]{{"x",new float[]{7f},new long[]{1}}});
            if(out.length!=1 || out[0]!=7f) throw new AssertionError("persistent run failed");
            // Existing optimized cache now exists. Force its first load to fail; production must rebuild and retry.
            OrtEnvironment.failNext=1; OnnxModel rebuilt=new OnnxModel(source,"persistent");
            if(value(rebuilt,"optimizedModelBytes")!=null) throw new AssertionError("rebuilt persistent bytes retained");
            if(rebuilt.run(new Object[][]{{"x",new float[]{8f},new long[]{1}}})[0]!=8f) throw new AssertionError("rebuilt run failed");
            TerrainDiffusionConfig.device="gpu"; TerrainDiffusionConfig.offload=true;
            OnnxModel offloaded=new OnnxModel(source,"offloaded");
            if(value(offloaded,"optimizedModelBytes")==null) throw new AssertionError("offload bytes released");
            OrtEnvironment.failNext=1;
            try { offloaded.run(new Object[][]{{"x",new float[]{9f},new long[]{1}}}); throw new AssertionError("expected offload failure"); }
            catch(RuntimeException expected) {}
            if(value(offloaded,"optimizedModelBytes")==null) throw new AssertionError("retry bytes lost");
            if(offloaded.run(new Object[][]{{"x",new float[]{9f},new long[]{1}}})[0]!=9f) throw new AssertionError("offload retry failed");
            offloaded.close(); persistent.close(); rebuilt.close();
            try { persistent.run(new Object[][]{{"x",new float[]{1f},new long[]{1}}}); throw new AssertionError("closed persistent model reopened"); }
            catch(IllegalStateException expected) {}
            System.out.println("model_retention_java_test: PASS");
          }
        }
    """,
}


def main() -> None:
    java_home = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-21"))
    javac = str(java_home / "bin" / "javac.exe") if (java_home / "bin" / "javac.exe").exists() else shutil.which("javac")
    java = str(java_home / "bin" / "java.exe") if (java_home / "bin" / "java.exe").exists() else shutil.which("java")
    if not javac or not java:
        raise SystemExit("JDK javac/java required")
    scratch = ROOT / ".tmp"
    scratch.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="td-model-retention-", dir=scratch) as temp:
        work = Path(temp)
        for relative, content in SOURCES.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        result = subprocess.run(
            [javac, "-d", str(work / "classes"), str(ONNX_MODEL), *map(str, work.rglob("*.java"))],
            text=True, capture_output=True,
        )
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        result = subprocess.run([java, "-cp", str(work / "classes"), "Harness"], text=True, capture_output=True)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        print(result.stdout, end="")


if __name__ == "__main__":
    main()
