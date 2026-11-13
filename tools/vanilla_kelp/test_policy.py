"""Compile and exercise the pure vanilla-kelp coordinate policy."""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/vanillakelp/VanillaKelpPlacementPolicy.java"

HARNESS = """
import com.github.xandergos.terraindiffusionmc.compat.vanillakelp.VanillaKelpPlacementPolicy;

public final class VanillaKelpPlacementPolicyTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        require(VanillaKelpPlacementPolicy.CANDIDATES_PER_CHUNK == 64, "candidate bound");
        for (int depth = 0; depth <= 6; depth++)
            require(VanillaKelpPlacementPolicy.shallowWaterFactor(depth) == 0.0,
                    "shallow shelf rejected at " + depth);
        require(VanillaKelpPlacementPolicy.shallowWaterFactor(7) > 0.0,
                "transition begins above shelf");
        require(VanillaKelpPlacementPolicy.shallowWaterFactor(8)
                > VanillaKelpPlacementPolicy.shallowWaterFactor(7), "transition rises");
        require(VanillaKelpPlacementPolicy.shallowWaterFactor(11) < 1.0,
                "transition remains below deep-ocean density");
        require(VanillaKelpPlacementPolicy.shallowWaterFactor(12) == 1.0,
                "deep water unchanged");
        long seed = 0x5EED1234L;
        long cold = 0x3EAE9B59A7F116C1L;
        long warm = 0x76D4F9082C5AB137L;
        int coldAccepted = 0, warmAccepted = 0, seedDifferences = 0, kindDifferences = 0;
        for (int x = -240; x <= 240; x++) for (int z = -240; z <= 240; z++) {
            double value = VanillaKelpPlacementPolicy.density(seed, cold, x, z);
            require(value >= 0.0 && value < 1.0, "density bounds");
            double probability = VanillaKelpPlacementPolicy.probability(seed, cold,
                    VanillaKelpPlacementPolicy.COLD_ACCEPTANCE, x, z);
            require(probability >= 0.0 && probability <= VanillaKelpPlacementPolicy.COLD_ACCEPTANCE,
                    "cold probability bounds");
            if (x < 240) require(Math.abs(probability - VanillaKelpPlacementPolicy.probability(
                    seed, cold, VanillaKelpPlacementPolicy.COLD_ACCEPTANCE, x + 1, z)) < .11,
                    "x continuity at " + x + "," + z);
            if (z < 240) require(Math.abs(probability - VanillaKelpPlacementPolicy.probability(
                    seed, cold, VanillaKelpPlacementPolicy.COLD_ACCEPTANCE, x, z + 1)) < .11,
                    "z continuity at " + x + "," + z);
            boolean selectedCold = VanillaKelpPlacementPolicy.accept(seed, cold,
                    VanillaKelpPlacementPolicy.COLD_ACCEPTANCE, x, z);
            boolean selectedWarm = VanillaKelpPlacementPolicy.accept(seed, warm,
                    VanillaKelpPlacementPolicy.WARM_ACCEPTANCE, x, z);
            if (selectedCold) coldAccepted++;
            if (selectedWarm) warmAccepted++;
            if (selectedCold != VanillaKelpPlacementPolicy.accept(seed + 1, cold,
                    VanillaKelpPlacementPolicy.COLD_ACCEPTANCE, x, z)) seedDifferences++;
            if (selectedCold != selectedWarm) kindDifferences++;
        }
        double coldMean = coldAccepted / (481.0 * 481.0);
        double warmMean = warmAccepted / (481.0 * 481.0);
        require(coldMean > .10 && coldMean < .50, "cold density: " + coldMean);
        require(warmMean > .06 && warmMean < coldMean, "warm density: " + warmMean);
        require(seedDifferences > 5000 && kindDifferences > 5000, "seed/type independence");
        int shallowAccepted = 0, deepAccepted = 0;
        for (int x = -240; x <= 240; x++) for (int z = -240; z <= 240; z++) {
            if (VanillaKelpPlacementPolicy.accept(seed, cold,
                    VanillaKelpPlacementPolicy.COLD_ACCEPTANCE, 6, x, z)) shallowAccepted++;
            if (VanillaKelpPlacementPolicy.accept(seed, cold,
                    VanillaKelpPlacementPolicy.COLD_ACCEPTANCE, 12, x, z)) deepAccepted++;
        }
        require(shallowAccepted == 0, "six-block shallow shelf is kelp-free");
        require(deepAccepted > 1000, "deep water retains a nonzero kelp bed");
        System.out.println("VanillaKelpPlacementPolicyTest: PASS cold=" + coldMean
                + " warm=" + warmMean + " shallow=" + shallowAccepted + " deep=" + deepAccepted);
    }
}
"""


def tool(name: str) -> str:
    home = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None
    candidate = home / "bin" / f"{name}.exe" if home else None
    if candidate and candidate.is_file():
        return str(candidate)
    for known in (Path(r"C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.8.9-hotspot"),
                  Path(r"C:\\Program Files\\Java\\jdk-21")):
        candidate = known / "bin" / f"{name}.exe"
        if candidate.is_file():
            return str(candidate)
    return shutil.which(name) or ""


def main() -> None:
    javac, java = tool("javac"), tool("java")
    assert javac and java and SOURCE.is_file()
    with tempfile.TemporaryDirectory(prefix="vanilla-kelp-", dir=ROOT / ".tmp") as directory:
        temporary = Path(directory)
        harness = temporary / "VanillaKelpPlacementPolicyTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        classes = temporary / "classes"
        subprocess.run([javac, "-d", str(classes), str(SOURCE), str(harness)], check=True)
        subprocess.run([java, "-cp", str(classes), "VanillaKelpPlacementPolicyTest"], check=True)


if __name__ == "__main__":
    main()
