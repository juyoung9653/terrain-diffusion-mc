"""Compile and run the JDK-only natural river planform regression test."""
from __future__ import annotations
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "river-planner-tests" / "natural-planform"
SOURCES = [
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverMorphology.java",
    ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverPlanform.java",
    ROOT / "tools/river_planner/NaturalRiverPlanformTest.java",
]

def java_home() -> Path:
    for value in (os.environ.get("JAVA_HOME"), r"C:\Program Files\Java\jdk-21", r"C:\Program Files\Android\Android Studio\jbr"):
        if value and (Path(value) / "bin/javac.exe").is_file(): return Path(value)
    raise SystemExit("JDK 21 is required")

def main() -> None:
    home = java_home(); OUT.mkdir(parents=True, exist_ok=True)
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    for command in ([str(home / "bin/javac"), "--release", "21", "-d", str(OUT), *map(str, SOURCES)],
                    [str(home / "bin/java"), "-ea", "-cp", str(OUT), "NaturalRiverPlanformTest"]):
        result = subprocess.run(command, capture_output=True, text=True, creationflags=flags)
        print(result.stdout, end="")
        print(result.stderr, end="", file=sys.stderr)
        result.check_returncode()

if __name__ == "__main__": main()
