"""Compile and exercise the pure shallow-river-bed correction policy."""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / (
    "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/"
    "ShallowRiverBedPolicy.java"
)

HARNESS = r'''
import com.github.xandergos.terraindiffusionmc.world.river.ShallowRiverBedPolicy;

public final class ShallowRiverBedPolicyTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static int depth(int returnedDepth, boolean active, int version,
            boolean channel, boolean source, boolean terminal, int plannedWater,
            int plannedBed, int carverWater) {
        return ShallowRiverBedPolicy.normalizeDepth(returnedDepth, active, version,
                channel, source, terminal, plannedWater, plannedBed, carverWater);
    }

    public static void main(String[] args) {
        require(depth(2, true, 12, true, false, false, 80, 79, 80) == 1,
                "eligible planned one-block bed must remain one block");
        require(depth(2, true, 13, true, false, false, 80, 79, 80) == 1,
                "future natural profile must retain the correction");

        for (int returned : new int[] {-3, -1, 0, 1, 3, 4}) {
            require(depth(returned, true, 12, true, false, false, 80, 79, 80) == returned,
                    "only native depth two may change: " + returned);
        }
        require(depth(2, false, 12, true, false, false, 80, 79, 80) == 2,
                "native/non-natural configuration changed");
        for (int version : new int[] {0, 1, 11}) {
            require(depth(2, true, version, true, false, false, 80, 79, 80) == 2,
                    "pre-V12 profile changed: " + version);
        }
        require(depth(2, true, 12, false, false, false, 80, 79, 80) == 2,
                "non-channel column changed");
        require(depth(2, true, 12, true, true, false, 80, 79, 80) == 2,
                "source column changed");
        require(depth(2, true, 12, true, false, true, 80, 79, 80) == 2,
                "terminal column changed");
        require(depth(2, true, 12, true, false, false, 80, 80, 80) == 2,
                "flat plan changed");
        require(depth(2, true, 12, true, false, false, 80, 78, 80) == 2,
                "planned depth two changed");
        require(depth(2, true, 12, true, false, false, 80, 79, 79) == 2,
                "different native waterline changed");
        System.out.println("ShallowRiverBedPolicyTest: PASS");
    }
}
'''


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
    with tempfile.TemporaryDirectory(prefix="shallow-river-bed-", dir=ROOT / ".tmp") as directory:
        temporary = Path(directory)
        harness = temporary / "ShallowRiverBedPolicyTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        classes = temporary / "classes"
        subprocess.run([javac, "-d", str(classes), str(SOURCE), str(harness)], check=True)
        subprocess.run([java, "-cp", str(classes), "ShallowRiverBedPolicyTest"], check=True)


if __name__ == "__main__":
    main()
