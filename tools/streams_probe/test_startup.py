"""JDK-only persisted startup-policy regression and native Streams hook audit."""
from __future__ import annotations
import argparse
import json
import re
import subprocess
import tempfile
from pathlib import Path
from audit_streams_compat import ROOT, DEFAULT_JARS, javap, javap_path, method_body, find_java_home


def audit(java_home: Path, jar: Path, output: Path) -> None:
    worldgen = javap(java_home, jar, "dev.streamsreflowing.worldgen.StreamsWorldgen", "-p", "-c", "-s")
    output.write_text(worldgen, encoding="utf-8")
    sampler = method_body(worldgen, "private static dev.streamsreflowing.core.terrain.TerrainSampler a(dev.streamsreflowing.core.terrain.TerrainSampler, net.minecraft.world.level.chunk.ChunkGenerator, net.minecraft.world.level.levelgen.RandomState, net.minecraft.server.level.ServerLevel, long);")
    assert "TerrainModeStore.a:" in sampler and "FormSampler" in sampler
    assert "RiverFlowFields.classifierFor:" in sampler
    judge = method_body(worldgen, "private static boolean a(dev.streamsreflowing.core.terrain.TerrainSampler, dev.streamsreflowing.core.terrain.TerrainSampler, net.minecraft.world.level.levelgen.DensityFunction, boolean, dev.streamsreflowing.core.river.RiverFlowField$BiomeClass);")
    assert "sipush        211" in judge and "sipush        512" in judge and "sipush        1800" in judge
    assert "RiverFlowField$BiomeClass.classify:(II)I" in judge
    fast_signature = "private static dev.streamsreflowing.worldgen.StreamsWorldgen$FastCal a(net.minecraft.world.level.chunk.ChunkGenerator, net.minecraft.world.level.levelgen.RandomState, net.minecraft.world.level.biome.BiomeSource, net.minecraft.world.level.biome.Climate$Sampler, int, dev.streamsreflowing.core.terrain.TerrainSampler, net.minecraft.server.level.ServerLevel"
    fast = method_body(worldgen, fast_signature + ");") or method_body(worldgen, fast_signature + ", boolean);")
    # Check the actual exact-mode branch, including its jump target and all six constructor args.
    code = re.sub(r"\s+", " ", fast)
    assert code.count("IPlatformHelper.fastTerrainEstimate:()Z") == 1
    assert "20: ifeq 34" in code and "34: iconst_2 35: istore" in code
    assert "39: iconst_2 40: if_icmpge 59" in code
    assert "63: aload 5 65: aconst_null 66: iconst_0 67: iconst_0 68: aconst_null 69: aconst_null" in code
    assert 'FastCal."<init>":(Ldev/streamsreflowing/core/terrain/TerrainSampler;Ldev/streamsreflowing/core/terrain/TerrainSampler;ZILdev/streamsreflowing/worldgen/StreamsWorldgen$BandCorrection;Ldev/streamsreflowing/core/terrain/TerrainSampler;)V' in code
    assert "73: areturn" in code
    mixin = (ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/mixin/StreamsStartupMixin.java").read_text()
    for block in (sampler, fast):
        descriptor = re.search(r"descriptor: (\S+)", block).group(1)
        assert '"a' + descriptor + '"' in mixin
    registration = json.loads((ROOT / "neoforge/src/main/resources/terrain-diffusion-mc.neoforge.mixins.json").read_text())
    assert {"StreamsStartupMixin", "StreamsStartupLifecycleMixin"} <= set(registration["mixins"])
    print(f"{jar.name}: both exact descriptors, calibration lattice, native exact branch and registration passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--jar", type=Path, default=DEFAULT_JARS["2.15.2"])
    args = parser.parse_args()
    output = ROOT / "build/startup-repair/tests"
    output.mkdir(parents=True, exist_ok=True)
    args.java_home = find_java_home(args.java_home)
    audit(args.java_home, args.jar, output / (args.jar.stem + ".javap.txt"))
    source = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsStartupState.java"
    harness = ROOT / "tools/streams_probe/StartupStateHarness.java"
    # Use short Windows paths for this JDK's Unicode filesystem issue.
    subprocess.run([str(args.java_home / "bin/javac.exe"), "-d", str(output), str(source), str(harness)], check=True)
    with tempfile.TemporaryDirectory(prefix="state-", dir=output) as state:
        subprocess.run([str(args.java_home / "bin/java.exe"), "-cp", javap_path(output), "StartupStateHarness", javap_path(Path(state))], check=True)


if __name__ == "__main__":
    main()
