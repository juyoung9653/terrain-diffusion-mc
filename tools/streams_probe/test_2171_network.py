"""Read-only Streams 2.17.1 V14 network-boundary audit."""
from __future__ import annotations
import argparse
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "streams-2171-review" / "network-adapter-test"

def need(value: bool, message: str) -> None:
    if not value:
        raise AssertionError(message)

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        raise SystemExit(f"missing JAR: {jar}")
    javap = args.java_home / "bin" / "javap.exe"
    result = subprocess.run([str(javap), "-p", "-s", "-classpath", str(jar),
                             "dev.streamsreflowing.core.river.NoDecompilePermissionEver_001"],
                            cwd=ROOT, text=True, capture_output=True)
    if result.returncode and "AccessDeniedException:" not in result.stderr:
        raise RuntimeError(result.stderr[-2000:])
    descriptor = ("(Ldev/streamsreflowing/core/plate/PlateKey;Ljava/util/function/Function;"
                  "Ldev/streamsreflowing/core/plate/PlateLattice;"
                  "Ldev/streamsreflowing/core/terrain/TerrainSampler;"
                  "Ldev/streamsreflowing/core/config/StreamsConfig;J)"
                  "Ldev/streamsreflowing/core/river/RiverNetwork;")
    need(descriptor in result.stdout, "2.17.1 graph-builder descriptor changed")
    mixin = (ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/StreamsNaturalNetwork2171Mixin.java").read_text(encoding="utf-8")
    adapter = (ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/Streams2171NetworkAdapter.java").read_text(encoding="utf-8")
    need("at = @At(\"RETURN\")" in mixin and "Streams2171NetworkAdapter.refine(domain" in mixin
         and "ProprietaryNiceJohn_001;" in mixin,
         "V14 is not attached to completed native graphs")
    for call in ("selectWatersheds", "StreamsNaturalNetwork.refine",
                 "StreamsConfluenceNetwork.refine", "StreamsShallowNetwork.refine"):
        need(call in adapter, f"V14 pipeline missing {call}")
    need("StreamsPlanformNetwork.refine" not in adapter, "unverified planform entered builder")
    # Compile the new helper with a small world-free adapter fa챌ade, while using
    # the actual Streams records/JAR.  This reaches the helper's real domain
    # constructor and all three native reassembly refiners without NeoForge.
    source = OUT / "src"; source.mkdir(parents=True, exist_ok=True)
    stubs = {
        "org/slf4j/Logger.java": "package org.slf4j; public interface Logger { default void info(String s,Object...a){} }",
        "org/slf4j/LoggerFactory.java": "package org.slf4j; public final class LoggerFactory { static final Logger L=new Logger(){}; public static Logger getLogger(String n){return L;} }",
        "com/github/xandergos/terraindiffusionmc/compat/streams/StreamsNaturalRiverAdapter.java": "package com.github.xandergos.terraindiffusionmc.compat.streams; import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverMorphology; public final class StreamsNaturalRiverAdapter { public static boolean on=true; public static boolean active(Object c){return on;} public static int version(Object c){return 14;} public static Object selectWatersheds(Object n,Object d,Object t,Object c){Object x=StreamsWatershedNetwork.select(n,d,t,c,(a,b)->false); return StreamsHierarchyNetwork.select(x,d,c);} public static NaturalRiverMorphology.Ground originalTerrain(Object c){return (x,z)->80D;} }",
        "NetworkAdapterHarness.java": r'''package com.github.xandergos.terraindiffusionmc.compat.streams; import dev.streamsreflowing.core.config.*; import dev.streamsreflowing.core.plate.*; import dev.streamsreflowing.core.river.*; import dev.streamsreflowing.core.api.*; import java.lang.reflect.*; import java.util.*; public class NetworkAdapterHarness { static void ok(boolean x,String s){if(!x)throw new AssertionError(s);} public static void main(String[] q){ StreamsConfig c=StreamsConfig.proprietary1(); PlateKey k=new PlateKey(0,0); var s=new LicensedCodeNotForAnalysis_001(1000,2000,80,2,2000,2000,70,3,1,1); var lake=new Lake(62,1,1,10,10,1,1,new long[]{1}); var sink=new Sink(2000,2000,70,3,1,1); var n=new RiverNetwork(k,List.of(s),null,List.of(lake),List.of(sink),null); Object terrain=Proxy.newProxyInstance(NetworkAdapterHarness.class.getClassLoader(),new Class[]{dev.streamsreflowing.core.terrain.TerrainSampler.class},(p,m,a)->m.getReturnType()==double.class?80D:m.getReturnType()==int.class?2:m.getReturnType()==boolean.class?false:null); Streams2171NetworkAdapter.resetCounts(); Object r=Streams2171NetworkAdapter.refine(new dev.streamsreflowing.core.plate.ProprietaryNiceJohn_001(new PlateLattice(1L,5120D,0D),k),terrain,c,1L,n); ok(r instanceof RiverNetwork,"not native network"); RiverNetwork out=(RiverNetwork)r; ok(out.allRightsReserved1().equals(List.of(lake)),"lake lost"); ok(out.sinks().equals(List.of(sink)),"sink lost"); var counts=Streams2171NetworkAdapter.counts(); ok(counts.invocations()==1&&counts.completed()==1&&counts.failed()==0,"counts wrong"); StreamsNaturalRiverAdapter.on=false; ok(Streams2171NetworkAdapter.refine(new dev.streamsreflowing.core.plate.ProprietaryNiceJohn_001(new PlateLattice(1L,5120D,0D),k),terrain,c,1L,n)==n,"inactive changed"); System.out.println("NetworkAdapterHarness: PASS active native refine and inactive pass-through"); }}'''
    }
    files=[]
    for name, text in stubs.items():
        target=source/name; target.parent.mkdir(parents=True, exist_ok=True); target.write_text(text,encoding="utf-8"); files.append(target)
    compiled = [
        ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverMorphology.java",
        ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverProfile.java",
        ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverWatersheds.java",
        ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverHierarchy.java",
        ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverConfluenceWidths.java",
        ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverShallowProfile.java",
        ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsRecordAccess.java",
        ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/Streams2162NetworkAccess.java",
        ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsNaturalNetwork.java",
        ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsWatershedNetwork.java",
        ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsHierarchyNetwork.java",
        ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsConfluenceNetwork.java",
        ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsShallowNetwork.java",
        ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/Streams2171NetworkAdapter.java"]
    subprocess.run([str(args.java_home / "bin" / "javac.exe"), "--release", "21", "-cp", str(jar), "-d", str(OUT), *map(str, compiled), *map(str, files)], cwd=ROOT, check=True)
    subprocess.run([str(args.java_home / "bin" / "java.exe"), "-ea", "-cp", os.pathsep.join((str(OUT), str(jar))), "com.github.xandergos.terraindiffusionmc.compat.streams.NetworkAdapterHarness"], cwd=ROOT, check=True)
    # Reuse the existing actual-JAR reassembly harness: it exercises the retained
    # six-argument graph boundary, lakes, sinks and null relief on this JAR.
    subprocess.run(["python", "-B", "tools/streams_probe/test_2162_network.py",
                    "--java-home", str(args.java_home), "--jar", str(jar)], cwd=ROOT, check=True)
    print("test_2171_network: PASS V14 helper refine, inactive pass-through, native reassembly")

if __name__ == "__main__":
    main()



