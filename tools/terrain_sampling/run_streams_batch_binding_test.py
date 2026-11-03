"""Compile the legacy 2.15.2 batch fixture and audit a shipped sampler contract.

Streams 2.16.2 deliberately hides its grid/cache implementation names.  Its
public TerrainSampler methods are still sufficient to prove that TD can bind the
sampler boundary, but not to claim the old cache or LowResGrid batch path works.
"""
from pathlib import Path
import os
import subprocess
import sys
import argparse
import re
import zipfile

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


def streams_version(jar: Path) -> str:
    with zipfile.ZipFile(jar) as archive:
        metadata = archive.read("META-INF/neoforge.mods.toml").decode("utf-8")
    match = re.search(r'(?m)^version\s*=\s*"([^"]+)"(?:\s*#.*)?$', metadata)
    if match is None:
        raise ValueError("Streams JAR has no mod version in META-INF/neoforge.mods.toml")
    return match.group(1)


def audit_public_sampler(java_home: Path, jar: Path, receipt: Path) -> None:
    """Check the actual public sampler ABI without inferring obfuscated internals."""
    command = [str(java_home / "bin/javap.exe"), "-p", "-s", "-classpath", str(jar),
               "dev.streamsreflowing.core.terrain.TerrainSampler"]
    done = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    with receipt.open("a", encoding="utf-8") as log:
        log.write("$ " + " ".join(command) + "\n" + done.stdout)
    done.check_returncode()
    required = (
        "double height(int, int);\n    descriptor: (II)D",
        "boolean batchCapable();\n    descriptor: ()Z",
        "void heightsBatch(int[], int[], double[], int);\n    descriptor: ([I[I[DI)V",
    )
    missing = [signature for signature in required if signature not in done.stdout]
    if missing:
        raise AssertionError("Streams TerrainSampler public ABI changed: " + "; ".join(missing))

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path,
                        default=ROOT / "build/chunk-profile/retained-artifacts/a6b720af2b211ad6b2bc8ec607415d99d066607908340fc25f540c79213ba15c/StreamsReflowing-1.21.1-neoforge-2.15.2.jar",
                        help="Streams Reflowing JAR for public sampler and legacy 2.15.2 contracts")
    args = parser.parse_args()
    java_home = next((candidate for candidate in (
        Path("C:/Program Files/Java/jdk-21"),
        Path("C:/Program Files/Android/Android Studio/jbr"),
    ) if (candidate / "bin/javac.exe").is_file()), None)
    if java_home is None:
        raise RuntimeError("JDK 21 with javac and javap is required")
    target = ROOT / "build/chunk-performance/streams-batch-binding-test"
    generated = target / "fixture-src"
    target.mkdir(parents=True, exist_ok=True)
    receipt = target / "validation.txt"
    receipt.write_text("", encoding="utf-8")
    jar = args.jar.resolve()
    if not jar.is_file():
        raise FileNotFoundError(f"Streams native contract JAR not found: {jar}")
    version = streams_version(jar)
    audit_public_sampler(java_home, jar, receipt)
    if version == "2.16.2":
        print("PASS native 2.16.2 TerrainSampler public ABI: height, batch capability and batch method")
        print("SKIP legacy CachingTerrainSampler/LowResGrid checks: 2.16.2 does not expose their audited names")
        return
    if version != "2.15.2":
        raise RuntimeError("native cache/grid contract is audited only for 2.15.2; "
                           f"got Streams {version}")
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
