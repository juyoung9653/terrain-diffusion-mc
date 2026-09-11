#!/usr/bin/env python3
"""Compile and run the standalone RingBiomeSearch behavioral tests with JDK 21."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/RingBiomeSearch.java"
TEST = ROOT / "tools/ring_biome_search/RingBiomeSearchTest.java"
COARSE_SOURCES = [
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/infinitetensor/FloatTensor.java",
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/FastNoiseLite.java",
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/BiomeClassifier.java",
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/TerralithBiomeClassifier.java",
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/CoarseBiomeHints.java",
]
COARSE_TEST = ROOT / "tools/ring_biome_search/CoarseBiomeHintsTest.java"
RESOURCES = ROOT / "common/src/main/resources"


def executable(java_home: Path | None, name: str) -> str:
    if java_home is not None:
        suffix = ".exe" if os.name == "nt" else ""
        candidate = java_home / "bin" / f"{name}{suffix}"
        if candidate.is_file():
            return str(candidate)
        raise RuntimeError(f"{candidate} does not exist")
    found = shutil.which(name)
    if found:
        return found
    raise RuntimeError(f"could not find {name}; pass --java-home pointing to JDK 21")


def java_major(javac: str) -> int:
    completed = subprocess.run([javac, "-version"], text=True, capture_output=True, check=True)
    match = re.search(r"(\d+)(?:\.\d+)?", completed.stdout + completed.stderr)
    if not match:
        raise RuntimeError("could not parse javac version")
    return int(match.group(1))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, default=Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    args = parser.parse_args()
    javac = executable(args.java_home, "javac")
    java = executable(args.java_home, "java")
    major = java_major(javac)
    if major != 21:
        raise RuntimeError(f"JDK 21 is required; found javac {major} at {javac}")
    with tempfile.TemporaryDirectory(prefix="ring-biome-search-") as output:
        subprocess.run([javac, "--release", "21", "-d", output, str(SOURCE), str(TEST),
                        *(str(source) for source in COARSE_SOURCES), str(COARSE_TEST)], check=True)
        subprocess.run([java, "-ea", "-cp", output, "RingBiomeSearchTest"], check=True)
        subprocess.run([java, "-ea", "-cp", os.pathsep.join([output, str(RESOURCES)]),
                       "CoarseBiomeHintsTest"], check=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
        print(f"test_ring_biome_search.py: {error}", file=sys.stderr)
        raise SystemExit(1)
