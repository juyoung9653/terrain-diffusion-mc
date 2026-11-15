"""JDK21 preflight for the pure conventions used by RiverRegressionAudit."""
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

from test_2162_bed_hook import java_tool

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "tools/streams_probe/java/com/github/xandergos/terraindiffusionmc/diagnostic/RiverRegressionContract.java"
AUDIT = ROOT / "tools/streams_probe/java/com/github/xandergos/terraindiffusionmc/diagnostic/RiverRegressionAudit.java"
CLASSIFIER = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/EcologyClassifier.java"
NOISE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/FastNoiseLite.java"
WETLAND = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/WetlandEcology.java"

STUBS = {
    "com/github/xandergos/terraindiffusionmc/pipeline/TerralithBiomeClassifier.java": r'''
package com.github.xandergos.terraindiffusionmc.pipeline;
final class TerralithBiomeClassifier {
  static short selectSurface(int x, int z, short fallback, float elevation, float slope,
                             float temperature, float rainfall, float waterDistance,
                             boolean underground) { return fallback; }
}
''',
    "com/github/xandergos/terraindiffusionmc/pipeline/ShoreClassifier.java": r'''
package com.github.xandergos.terraindiffusionmc.pipeline;
final class ShoreClassifier {
  static short select(int x, int z, float elevation, float slope, float temperature,
                      float moisture, float waterDistance, float openWater, float relief) { return 0; }
}
''',
    "com/github/xandergos/terraindiffusionmc/diagnostic/RiverRegressionContractHarness.java": r'''
package com.github.xandergos.terraindiffusionmc.diagnostic;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
public final class RiverRegressionContractHarness {
  static void equal(int actual, int expected, String name) {
    if (actual != expected) throw new AssertionError(name + ": " + actual + " != " + expected);
  }
  static void yes(boolean actual, String name) {
    if (!actual) throw new AssertionError(name);
  }
  static void no(boolean actual, String name) { if (actual) throw new AssertionError(name); }
  public static void main(String[] args) {
    // Ground Y71 has its first-air boundary at Y72.  The source biome must therefore
    // use quart Y18 rather than the solid-ground quart Y17.
    equal(RiverRegressionContract.sourceBiomeQuartY(71), 18, "first-air quart");
    equal(Math.floorDiv(71, 4), 17, "solid-ground control");
    equal(RiverRegressionContract.desertEcologyId(), 11, "desert ID reserves zero");
    equal(EcologyClassifier.BIOMES.indexOf("desert") + 1,
          RiverRegressionContract.desertEcologyId(), "classifier list source");
    yes(RiverRegressionContract.isDesertSourceAndEcology(
            "terrain-diffusion-mc:ecology/desert", 11), "source desert with saved river overlay");
    no(RiverRegressionContract.isDesertSourceAndEcology(
            "terrain-diffusion-mc:ecology/desert", 10), "zero-based ecology ID rejected");
    no(RiverRegressionContract.isDesertSourceAndEcology("minecraft:river", 11), "saved overlay not source");
    System.out.println("RiverRegressionContractHarness: PASS");
  }
}
''',
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--out", type=Path, default=ROOT / "build/streams-2173-review/river-regression-contract")
    args = parser.parse_args()
    javac = java_tool("javac", args.java_home)
    java = java_tool("java", args.java_home)
    assert javac and java, "JDK21 javac/java are required"
    assert "RiverRegressionContract.sourceBiomeQuartY(originalGroundY)" in AUDIT.read_text(encoding="utf-8")
    assert "RiverRegressionContract.isDesertSourceAndEcology(" in AUDIT.read_text(encoding="utf-8")
    args.out.mkdir(parents=True, exist_ok=True)
    sources = [CONTRACT, CLASSIFIER, NOISE, WETLAND]
    for relative, body in STUBS.items():
        path = args.out / "src" / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
        sources.append(path)
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    subprocess.run([javac, "--release", "21", "-d", str(args.out), *map(str, sources)],
                   cwd=ROOT, check=True, creationflags=flags)
    result = subprocess.run([java, "-ea", "-cp", str(args.out),
                             "com.github.xandergos.terraindiffusionmc.diagnostic.RiverRegressionContractHarness"],
                            cwd=ROOT, text=True, capture_output=True, check=True, creationflags=flags)
    assert "RiverRegressionContractHarness: PASS" in result.stdout, result.stdout + result.stderr
    print(result.stdout.strip())


if __name__ == "__main__":
    main()
