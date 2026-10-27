"""Compile and execute the production Hybrid Aquatic marine profile."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/hybridaquatic/HybridAquaticMarineProfile.java"

HARNESS = """
import com.github.xandergos.terraindiffusionmc.compat.hybridaquatic.HybridAquaticMarineProfile;

public class HybridAquaticMarineProfileTest {
    private static final double EPS = 1.0e-12;
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void close(double actual, double expected, String message) {
        require(Math.abs(actual - expected) <= EPS, message + ": " + actual);
    }
    private static boolean trench(double value) {
        return value >= -.71 - EPS && value <= -.63 + EPS;
    }
    private static void alias(String namespace, String path, int y, String expected) {
        String actual = HybridAquaticMarineProfile.oceanAlias(namespace, path, y);
        require(java.util.Objects.equals(actual, expected), namespace + ":" + path + " at " + y + " -> " + actual);
    }
    public static void main(String[] args) {
        close(HybridAquaticMarineProfile.continentalnessForHeight(63), -.19, "sea anchor");
        close(HybridAquaticMarineProfile.continentalnessForHeight(39), -.455, "depth 24 anchor");
        close(HybridAquaticMarineProfile.continentalnessForHeight(15), -.63, "depth 48 anchor");
        close(HybridAquaticMarineProfile.continentalnessForHeight(-1), -.71, "depth 64 anchor");
        close(HybridAquaticMarineProfile.continentalnessForHeight(-33), -1.05, "depth 96 anchor");
        double previous = -Double.MAX_VALUE;
        for (int y = -2048; y <= 2048; y++) {
            double value = HybridAquaticMarineProfile.continentalnessForHeight(y);
            require(value >= -1.05 - EPS && value <= 1 + EPS, "bounded at " + y);
            require(value + EPS >= previous, "monotonic at " + y);
            previous = value;
        }
        for (int depth = 0; depth <= 400; depth++) {
            boolean expected = depth >= 48 && depth <= 64;
            require(trench(HybridAquaticMarineProfile.continentalnessForHeight(63 - depth)) == expected,
                    "trench band at depth " + depth);
        }
        for (String namespace : new String[]{"minecraft", "terralith"}) {
            alias(namespace, "warm_ocean", 39, "warm_ocean");
            alias(namespace, "deep_warm_ocean", 39, "warm_ocean");
            alias(namespace, "cold_ocean", 39, "deep_cold_ocean");
            alias(namespace, "frozen_ocean", 39, "deep_frozen_ocean");
            alias(namespace, "ocean", 39, "deep_ocean");
            alias(namespace, "lukewarm_ocean", 39, "deep_lukewarm_ocean");
            alias(namespace, "cold_ocean", 40, "cold_ocean");
            alias(namespace, "frozen_ocean", 40, "frozen_ocean");
            alias(namespace, "ocean", 40, "ocean");
            alias(namespace, "lukewarm_ocean", 40, "lukewarm_ocean");
        }
        alias("minecraft", "ocean", 63, null);
        alias("hybrid_aquatic", "ocean", 39, null);
        alias("minecraft", "river", 39, null);
        System.out.println("HybridAquaticMarineProfileTest: PASS (anchors, continuity, trench band, aliases)");
    }
}
"""


def main() -> None:
    java_home = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None
    suffix = ".exe" if os.name == "nt" else ""
    javac = str(java_home / "bin" / ("javac" + suffix)) if java_home else shutil.which("javac")
    java = str(java_home / "bin" / ("java" + suffix)) if java_home else shutil.which("java")
    if not javac or not java:
        raise SystemExit("JDK required; set JAVA_HOME")
    scratch = ROOT / ".tmp"
    scratch.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="hybrid-aquatic-profile-", dir=scratch) as directory:
        harness = Path(directory) / "HybridAquaticMarineProfileTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        subprocess.run([javac, "--release", "21", "-d", directory, str(SOURCE), str(harness)], check=True)
        subprocess.run([java, "-cp", directory, "HybridAquaticMarineProfileTest"], check=True)


if __name__ == "__main__":
    main()
