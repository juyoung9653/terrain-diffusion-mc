"""Compile production OnnxModel against stubs and exercise five-minute GPU idling.

This unit-style harness does not start Minecraft or native ONNX Runtime. It
checks that persistent GPU sessions and the shared offload slot are released
once globally idle, loaded lazily on their next inference, CPU sessions are
untouched, and closed models cannot be resurrected.
"""
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from model_retention_java_test import ROOT, ONNX_MODEL, SOURCES as BASE_SOURCES

SOURCES = dict(BASE_SOURCES)
SOURCES["ai/onnxruntime/OrtSession.java"] = r'''
package ai.onnxruntime;
import java.util.*; import java.util.concurrent.*; import ai.onnxruntime.providers.*;
public class OrtSession implements AutoCloseable {
  public static int creates, closes; public static volatile CountDownLatch runEntered, allowRun; public boolean closed;
  private final byte[] bytes; public OrtSession(byte[] b) { bytes=b.clone(); creates++; }
  public Result run(Map<String, OnnxTensor> feed) { if (closed) throw new AssertionError("run closed session"); CountDownLatch entered=runEntered, allowed=allowRun; if (entered != null) { entered.countDown(); try { if (!allowed.await(5, TimeUnit.SECONDS)) throw new AssertionError("run latch timeout"); } catch (InterruptedException e) { throw new AssertionError(e); } } return new Result(feed.get("x")); }
  public void close() throws OrtException { if (!closed) { closed=true; closes++; } }
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
'''
SOURCES["Harness.java"] = r'''
import ai.onnxruntime.*; import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.*; import java.lang.reflect.*; import java.nio.file.*; import java.util.concurrent.*; import java.util.concurrent.atomic.AtomicReference;
public final class Harness {
  static Field field(String n) throws Exception { Field f=OnnxModel.class.getDeclaredField(n); f.setAccessible(true); return f; }
  static Object get(OnnxModel m,String n) throws Exception { return field(n).get(m); }
  static void ageGpu() throws Exception { field("lastGpuActivityNanos").setLong(null, System.nanoTime()-TimeUnit.SECONDS.toNanos(301)); }
  static void evict() throws Exception { Method m=OnnxModel.class.getDeclaredMethod("evictIdleGpuSessions"); m.setAccessible(true); m.invoke(null); }
  static float[] input(float x) { return new float[]{x}; }
  static Object[][] args(float x) { return new Object[][]{{"x",input(x),new long[]{1}}}; }
  static void assertRun(OnnxModel m, float x) { if(m.run(args(x))[0]!=x) throw new AssertionError("lazy run failed"); }
  static void await(CountDownLatch latch, String name) throws Exception { if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError(name+" timed out"); }
  static void join(Thread thread, String name) throws Exception { thread.join(2000); if(thread.isAlive()) throw new AssertionError(name+" did not finish"); }
  static void assertBlocked(Thread thread, String name) throws Exception { thread.join(100); if(!thread.isAlive()) throw new AssertionError(name+" did not wait for inference"); }
  static void noWorkerFailure(AtomicReference<Throwable> failure) { if(failure.get()!=null) throw new AssertionError("worker failed", failure.get()); }
  static void waitForUnload(OnnxModel model) throws Exception { for (int i=0; i<60 && get(model,"gpuSession")!=null; i++) Thread.sleep(50); if(get(model,"gpuSession")!=null) throw new AssertionError("scheduled idle eviction did not run"); }
  public static void main(String[] ignored) throws Exception {
    Path root=Path.of(System.getProperty("java.io.tmpdir")).resolve("onnx-idle-eviction"); Files.createDirectories(root); ModelAssetManager.root=root;
    Path source=root.resolve("source.onnx"); Files.writeString(source,"source");
    TerrainDiffusionConfig.device="cpu"; TerrainDiffusionConfig.offload=false;
    OnnxModel cpu=new OnnxModel(source,"cpu"); Object cpuSession=get(cpu,"cpuSession");
    TerrainDiffusionConfig.device="gpu"; TerrainDiffusionConfig.offload=false;
    OnnxModel resident=new OnnxModel(source,"resident");
    if(get(resident,"gpuSession")==null) throw new AssertionError("persistent GPU not loaded");
    TerrainDiffusionConfig.offload=true;
    OnnxModel slot=new OnnxModel(source,"slot"); assertRun(slot,3f);
    if(get(resident,"gpuSession")==null) throw new AssertionError("slot affected resident model");
    field("lastGpuActivityNanos").setLong(null, System.nanoTime()-TimeUnit.SECONDS.toNanos(299)); evict();
    if(get(resident,"gpuSession")==null) throw new AssertionError("evicted before five minutes");
    if(field("GPU_IDLE_NANOS").getLong(null)!=TimeUnit.MINUTES.toNanos(5)) throw new AssertionError("wrong idle duration");
    ageGpu(); waitForUnload(resident);
    if(field("activeGpuSession").get(null)!=null) throw new AssertionError("scheduled shared GPU slot retained");
    assertRun(resident,4f); TerrainDiffusionConfig.offload=true; assertRun(slot,4f);
    ageGpu(); evict();
    if(get(cpu,"cpuSession")!=cpuSession) throw new AssertionError("CPU session evicted");
    if(get(resident,"gpuSession")!=null) throw new AssertionError("idle persistent GPU session retained");
    if(field("activeGpuSession").get(null)!=null) throw new AssertionError("idle shared GPU slot retained");
    TerrainDiffusionConfig.offload=false; assertRun(resident,5f);
    if(get(resident,"gpuSession")==null) throw new AssertionError("persistent GPU did not reload lazily");
    // A failed reload must leave the model retryable on the next chunk request.
    ageGpu(); evict(); OrtEnvironment.failNext=1;
    try { resident.run(args(6f)); throw new AssertionError("expected persistent reload failure"); }
    catch(RuntimeException expected) {}
    if(get(resident,"gpuSession")!=null) throw new AssertionError("failed reload retained a session");
    assertRun(resident,6f);
    TerrainDiffusionConfig.offload=true; assertRun(slot,7f);
    if(field("activeGpuSession").get(null)==null) throw new AssertionError("slot did not reload lazily");
    TerrainDiffusionConfig.offload=false; CountDownLatch entered=new CountDownLatch(1), allow=new CountDownLatch(1);
    OrtSession.runEntered=entered; OrtSession.allowRun=allow;
    AtomicReference<Throwable> workerFailure=new AtomicReference<>();
    Thread inference=new Thread(() -> { try { assertRun(resident,8f); } catch(Throwable e) { workerFailure.set(e); } }, "inference"); inference.start(); await(entered,"inference");
    ageGpu(); Thread eviction=new Thread(() -> { try { evict(); } catch(Throwable e) { workerFailure.compareAndSet(null,e); } }, "eviction"); eviction.start(); assertBlocked(eviction,"eviction");
    allow.countDown(); join(inference,"inference"); join(eviction,"eviction");
    noWorkerFailure(workerFailure);
    if(get(resident,"gpuSession")==null) throw new AssertionError("eviction closed a running session");
    OrtSession.runEntered=null; OrtSession.allowRun=null;
    entered=new CountDownLatch(1); allow=new CountDownLatch(1); OrtSession.runEntered=entered; OrtSession.allowRun=allow;
    workerFailure.set(null); Thread closingInference=new Thread(() -> { try { assertRun(resident,9f); } catch(Throwable e) { workerFailure.set(e); } }, "close-inference"); closingInference.start(); await(entered,"close-inference");
    Thread closer=new Thread(resident::close,"close"); closer.start(); assertBlocked(closer,"close");
    allow.countDown(); join(closingInference,"close-inference"); join(closer,"close");
    noWorkerFailure(workerFailure);
    OrtSession.runEntered=null; OrtSession.allowRun=null;
    try { resident.run(args(10f)); throw new AssertionError("closed model resurrected"); }
    catch(IllegalStateException expected) {}
    slot.close(); cpu.close();
    System.out.println("gpu_idle_eviction_java_test: PASS");
  }
}
'''

def main() -> None:
    java_home = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-21"))
    javac = str(java_home / "bin" / "javac.exe") if (java_home / "bin" / "javac.exe").exists() else shutil.which("javac")
    java = str(java_home / "bin" / "java.exe") if (java_home / "bin" / "java.exe").exists() else shutil.which("java")
    if not javac or not java: raise SystemExit("JDK javac/java required")
    scratch = ROOT / ".tmp"; scratch.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="td-gpu-idle-", dir=scratch) as temp:
        work=Path(temp)
        for relative, content in SOURCES.items():
            path=work / relative; path.parent.mkdir(parents=True,exist_ok=True); path.write_text(content,encoding="utf-8")
        result=subprocess.run([javac,"-d",str(work/"classes"),str(ONNX_MODEL),*map(str,work.rglob("*.java"))],text=True,capture_output=True)
        if result.returncode: raise SystemExit(result.stdout+result.stderr)
        result=subprocess.run([java, "-Djava.io.tmpdir=" + str(work / "jvm-tmp"), "-cp",str(work/"classes"),"Harness"],text=True,capture_output=True)
        if result.returncode: raise SystemExit(result.stdout+result.stderr)
        print(result.stdout,end="")
if __name__ == "__main__": main()


