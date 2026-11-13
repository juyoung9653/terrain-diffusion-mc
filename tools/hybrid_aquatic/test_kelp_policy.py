"""Compile and exercise Hybrid Aquatic's pure TD kelp placement policy."""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/hybridaquatic/HybridAquaticKelpPlacementPolicy.java"

HARNESS = """
import com.github.xandergos.terraindiffusionmc.compat.hybridaquatic.HybridAquaticKelpPlacementPolicy;

public final class HybridAquaticKelpPlacementPolicyTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        require(HybridAquaticKelpPlacementPolicy.CANDIDATES_PER_CHUNK == 48, "candidate bound");
        require(HybridAquaticKelpPlacementPolicy.ACCEPTANCE == 0.8, "acceptance");
        for (int depth = 0; depth <= 6; depth++)
            require(HybridAquaticKelpPlacementPolicy.shallowWaterFactor(depth) == 0.0,
                    "shallow bull-kelp shelf rejected at " + depth);
        require(HybridAquaticKelpPlacementPolicy.shallowWaterFactor(7) > 0.0,
                "bull-kelp transition begins above shelf");
        require(HybridAquaticKelpPlacementPolicy.shallowWaterFactor(11) < 1.0,
                "bull-kelp transition remains gradual");
        require(HybridAquaticKelpPlacementPolicy.shallowWaterFactor(12) == 1.0,
                "deep bull-kelp unchanged");
        long seed = 0x51A7E5EEDL;
        long salt = 0x44CBA31A0D1237E1L;
        int accepted = 0, differentSeed = 0, differentSpecies = 0;
        double minProbability = 1, maxProbability = 0;
        for (int x = -240; x <= 240; x++) for (int z = -240; z <= 240; z++) {
            double value = HybridAquaticKelpPlacementPolicy.density(seed, salt, x, z);
            require(value >= 0.0 && value < 1.0, "density bounds");
            double probability = HybridAquaticKelpPlacementPolicy.probability(seed, salt, x, z);
            minProbability = Math.min(minProbability, probability);
            maxProbability = Math.max(maxProbability, probability);
            boolean selected = HybridAquaticKelpPlacementPolicy.accept(seed, salt, x, z);
            if (selected) accepted++;
            if (selected != HybridAquaticKelpPlacementPolicy.accept(seed + 1, salt, x, z)) differentSeed++;
            if (selected != HybridAquaticKelpPlacementPolicy.accept(seed, salt + 1, x, z)) differentSpecies++;
            require(probability >= 0.0 && probability <= 0.8, "probability bounds");
            if (x < 240) {
                double adjacentProbability = HybridAquaticKelpPlacementPolicy.probability(seed, salt, x + 1, z);
                require(Math.abs(probability - adjacentProbability) < 0.14, "x probability continuity at " + x + "," + z);
            }
            if (z < 240) {
                double adjacentProbability = HybridAquaticKelpPlacementPolicy.probability(seed, salt, x, z + 1);
                require(Math.abs(probability - adjacentProbability) < 0.14, "z probability continuity at " + x + "," + z);
            }
        }
        int shallowAccepted = 0, deepAccepted = 0;
        for (int x = -240; x <= 240; x++) for (int z = -240; z <= 240; z++) {
            if (HybridAquaticKelpPlacementPolicy.accept(seed, salt, 6, x, z)) shallowAccepted++;
            if (HybridAquaticKelpPlacementPolicy.accept(seed, salt, 12, x, z)) deepAccepted++;
        }
        require(shallowAccepted == 0, "bull kelp absent from shallow shelf");
        require(deepAccepted > 100, "bull kelp remains in deep water");
        double mean = accepted / (481.0 * 481.0);
        require(mean > .15 && mean < .65, "neither empty nor dense carpet: " + mean);
        require(minProbability < .01 && maxProbability > .79, "beds and sparse gaps");
        require(differentSeed > 10000 && differentSpecies > 10000, "seed/species independence");
        System.out.println("HybridAquaticKelpPlacementPolicyTest: PASS (bounded, seed/species independence, adjacent continuity; mean=" + mean + ")");
    }
}
"""


def tool(name: str) -> str:
    java_home = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None
    candidate = java_home / "bin" / f"{name}.exe" if java_home else None
    if candidate and candidate.is_file():
        return str(candidate)
    # Match the other local kelp harness; Windows' javapath shim is not the JDK.
    for known in (Path(r"C:\Program Files\Java\jdk-21"),
                  Path(r"C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot")):
        candidate = known / "bin" / f"{name}.exe"
        if candidate.is_file():
            return str(candidate)
    return shutil.which(name) or ""


def run(*args: str) -> str:
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout


def main() -> None:
    javac, java = tool("javac"), tool("java")
    assert javac and java and SOURCE.is_file()
    with tempfile.TemporaryDirectory(prefix="hybrid-aquatic-kelp-", dir=ROOT / ".tmp") as directory:
        root = Path(directory)
        harness = root / "HybridAquaticKelpPlacementPolicyTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        classes = root / "classes"
        run(javac, "-d", str(classes), str(SOURCE), str(harness))
        result = run(java, "-cp", str(classes), "HybridAquaticKelpPlacementPolicyTest")
        assert result.strip().startswith("HybridAquaticKelpPlacementPolicyTest: PASS"), result
        print(result.strip())
    print("Hybrid Aquatic kelp policy tests: PASS")


if __name__ == "__main__":
    main()
