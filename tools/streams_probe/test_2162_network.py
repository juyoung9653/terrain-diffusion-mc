"""JDK-only contract and native graph-reassembly regression for Streams 2.16.2."""
from __future__ import annotations

import argparse
import os
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "streams-2162-review" / "network-test"
SOURCES = (
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverMorphology.java",
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverPlanform.java",
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsRecordAccess.java",
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/Streams2162NetworkAccess.java",
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsNaturalNetwork.java",
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsPlanformNetwork.java",
    ROOT / "tools/streams_probe/Streams2162NetworkHarness.java",
)


def find_jdk(requested: Path | None) -> Path:
    candidates = ([requested] if requested else []) + [
        Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None,
        Path(r"C:\Program Files\Android\Android Studio\jbr"), Path(r"C:\Program Files\Java\jdk-21")]
    for candidate in candidates:
        if candidate and (candidate / "bin/javac.exe").is_file() and (candidate / "bin/javap.exe").is_file():
            return candidate
    raise SystemExit("JDK 21 with javac and javap is required")


def inspect(jdk: Path, jar: Path, name: str) -> str:
    result = subprocess.run([str(jdk / "bin/javap.exe"), "-p", "-s", "-classpath", str(jar), name],
                            cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"javap {name} failed: {result.stderr[-1000:]}")
    return result.stdout


def require(value: bool, message: str) -> None:
    if not value:
        raise AssertionError(message)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--jar", required=True, type=Path)
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        raise SystemExit(f"missing JAR: {jar}")
    jdk = find_jdk(args.java_home)

    network = inspect(jdk, jar, "dev.streamsreflowing.core.river.RiverNetwork")
    builder = inspect(jdk, jar, "dev.streamsreflowing.core.river.NoDecompilePermissionEver_001")
    segment = inspect(jdk, jar, "dev.streamsreflowing.core.river.LicensedCodeNotForAnalysis_001")
    sampler = inspect(jdk, jar, "dev.streamsreflowing.core.terrain.TerrainSampler")
    for method in ("sourceGivenToAuthorizedOnly1", "noDecompilePermission1", "allRightsReserved1", "sinks", "niceJohn1"):
        require(method in network, f"RiverNetwork is missing {method}")
    require("proprietary1(int, int, int)" in network, "RiverNetwork lake-near contract changed")
    require("proprietary1(dev.streamsreflowing.core.plate.PlateKey," in builder
            and "dev.streamsreflowing.core.NoDecompilePermissionEver_001" in builder,
            "native six-argument graph builder changed")
    require("descriptor: (DDDDDDDDDD)V" in segment, "native segment constructor changed")
    require("noDecompilePermission1(int, int)" in sampler and "descriptor: (II)D" in sampler,
            "2.16.2 native ground sampler changed")
    require("groundHeight(int, int)" not in sampler, "unexpected legacy groundHeight present")
    require("groundHeightsBatch" in sampler, "2.16.2 native ground batch method missing")

    OUT.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(jdk / "bin/javac.exe"), "--release", "21", "-cp", str(jar), "-d", str(OUT),
                    *map(str, SOURCES)], cwd=ROOT, check=True)
    subprocess.run([str(jdk / "bin/java.exe"), "-ea", "-cp", os.pathsep.join((str(OUT), str(jar))),
                    "com.github.xandergos.terraindiffusionmc.compat.streams.Streams2162NetworkHarness"],
                   cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
