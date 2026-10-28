"""Compile and run the focused Streams 2.15.2 batch-binding contract fixture."""
from pathlib import Path
import os
import subprocess
import sys
import argparse

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from test_terrain_sampling import STUBS  # reuses the deterministic provider fixture

EXTRA_STUBS = {
    "net/neoforged/fml/loading/FMLLoader.java": r'''package net.neoforged.fml.loading;
import java.util.List;
public final class FMLLoader {
    private static LoadingModList list = new LoadingModList(List.of(new ModInfo("streamsreflowing", "2.15.2")));
    public static LoadingModList getLoadingModList(){ return list; }
    public static void setMods(List<ModInfo> mods){ list = new LoadingModList(mods); }
    public record LoadingModList(List<ModInfo> mods){ public List<ModInfo> getMods(){ return mods; } }
    public record ModInfo(String modId, String version){ public String getModId(){ return modId; } public String getVersion(){ return version; } }
}''',
    "dev/streamsreflowing/core/terrain/TerrainSampler.java": r'''package dev.streamsreflowing.core.terrain;
public interface TerrainSampler {
    double height(int x, int z);
    boolean batchCapable();
    void heightsBatch(int[] xs, int[] zs, double[] out, int count);
}''',
    "dev/streamsreflowing/core/terrain/CachingTerrainSampler.java": r'''package dev.streamsreflowing.core.terrain;
/** Faithful CachingTerrainSampler hop: it forwards batching to the world sampler. */
public final class CachingTerrainSampler implements TerrainSampler {
    private final TerrainSampler delegate;
    public int batchCalls;
    public CachingTerrainSampler(TerrainSampler delegate){ this.delegate=delegate; }
    public double height(int x,int z){ return delegate.height(x,z); }
    public boolean batchCapable(){ return delegate.batchCapable(); }
    public void heightsBatch(int[] xs,int[] zs,double[] out,int count){
        batchCalls++;
        delegate.heightsBatch(xs,zs,out,count);
    }
}''',
}

PACKAGE = "com/github/xandergos/terraindiffusionmc"

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path,
                        default=ROOT / "build/chunk-profile/retained-artifacts/a6b720af2b211ad6b2bc8ec607415d99d066607908340fc25f540c79213ba15c/StreamsReflowing-1.21.1-neoforge-2.15.2.jar",
                        help="Streams Reflowing 2.15.2 JAR for native contracts")
    args = parser.parse_args()
    java_home = Path("C:/Program Files/Java/jdk-21")
    target = ROOT / "build/chunk-performance/streams-batch-binding-test"
    generated = target / "fixture-src"
    target.mkdir(parents=True, exist_ok=True)
    receipt = target / "validation.txt"
    receipt.write_text("", encoding="utf-8")
    def run(command: list[str]) -> None:
        done = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        with receipt.open("a", encoding="utf-8") as log:
            log.write("$ " + " ".join(command) + "\n" + done.stdout)
        print(done.stdout, end="")
        done.check_returncode()
    sources = []
    for name, content in {**STUBS, **EXTRA_STUBS}.items():
        path = generated / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        sources.append(path)
    for name in ("LocalTerrainProvider", "InferenceExecutor", "ExactTerrainSampling", "EcologyData",
                 "EcologyDataCache", "FastNoiseLite", "LaplacianUtils", "BiomeClassifier",
                 "TerralithBiomeClassifier", "MacroBiomeContext", "BroadEcologyClimate",
                 "EcologyClassifier", "ShoreClassifier", "ShoreContext"):
        sources.append(ROOT / "common/src/main/java" / PACKAGE / "pipeline" / (name + ".java"))
    sources += [
        ROOT / "neoforge/src/main/java" / PACKAGE / "compat/streams/StreamsInferenceBinding.java",
        ROOT / "neoforge/src/main/java" / PACKAGE / "compat/streams/StreamsSamplerPriority.java",
        ROOT / "tools/terrain_sampling/StreamsBatchBindingTest.java",
    ]
    run([str(java_home / "bin/javac.exe"), "--release", "21", "-encoding", "UTF-8", "-d", str(target),
         *map(str, sources)])
    run([str(java_home / "bin/java.exe"), "-cp", os.pathsep.join((str(target), str(ROOT / "common/src/main/resources"))),
         "com.github.xandergos.terraindiffusionmc.pipeline.StreamsBatchBindingTest"])
    jar = args.jar.resolve()
    if not jar.is_file():
        raise FileNotFoundError(f"Streams native contract JAR not found: {jar}")
    native_target = target / "native"
    native_target.mkdir(exist_ok=True)
    native_stub = target / "native-fixture-src/org/slf4j"
    native_stub.mkdir(parents=True, exist_ok=True)
    (native_stub / "Logger.java").write_text(
        "package org.slf4j; public interface Logger { default void info(String s,Object... a){} default void debug(String s,Object... a){} default void warn(String s,Object... a){} default void error(String s,Object... a){} }",
        encoding="utf-8")
    (native_stub / "LoggerFactory.java").write_text(
        "package org.slf4j; public final class LoggerFactory { private static final Logger L=new Logger(){}; public static Logger getLogger(Class<?> c){return L;} public static Logger getLogger(String s){return L;} }",
        encoding="utf-8")
    native_sources = [native_stub / "Logger.java", native_stub / "LoggerFactory.java",
                      ROOT / "tools/terrain_sampling/StreamsNativeCachingBatchContractTest.java",
                      ROOT / "tools/terrain_sampling/StreamsNativeLowResGridParityTest.java"]
    run([str(java_home / "bin/javac.exe"), "--release", "21", "-cp", str(jar), "-d", str(native_target),
         *map(str, native_sources)])
    run([str(java_home / "bin/java.exe"), "-cp", os.pathsep.join((str(native_target), str(jar))),
         "StreamsNativeCachingBatchContractTest"])
    run([str(java_home / "bin/java.exe"), "-cp", os.pathsep.join((str(native_target), str(jar))),
         "StreamsNativeLowResGridParityTest"])

if __name__ == "__main__":
    main()
