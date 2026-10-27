"""JDK-only differential test for StreamsRiverRaster reflection metadata caching."""
from __future__ import annotations
import argparse
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "river-raster-tests"
sys.path.insert(0, str(ROOT / "tools"))
from probe_suite import default_java_home

STUBS = ROOT / "tools" / "streams_probe" / "raster_stubs"
SOURCES = [
    ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsRiverRaster.java",
    ROOT / "tools/streams_probe/RiverRasterHarness.java",
    *sorted(STUBS.rglob("*.java")),
]

def java_home(requested: Path | None) -> Path:
    executable = ".exe" if os.name == "nt" else ""
    configured = default_java_home()
    candidates = [requested]
    if configured:
        candidates.append(Path(configured))
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    candidates += [Path(r"C:\Program Files\Java\jdk-21"),
                   Path(r"C:\Program Files\Android\Android Studio\jbr")]
    for candidate in candidates:
        if candidate and (candidate / "bin" / ("javac" + executable)).is_file() and (candidate / "bin" / ("java" + executable)).is_file():
            return candidate
    raise SystemExit("JDK 21 with javac and java is required; use --java-home")

def run(command: list[str]) -> str:
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError("command failed: " + " ".join(command) + "\n" + result.stdout + result.stderr)
    return result.stdout + result.stderr

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path)
    args = parser.parse_args()
    home = java_home(args.java_home)
    OUT.mkdir(parents=True, exist_ok=True)
    executable = ".exe" if os.name == "nt" else ""
    run([str(home / "bin" / ("javac" + executable)), "--release", "21", "-d", str(OUT), *map(str, SOURCES)])
    print(run([str(home / "bin" / ("java" + executable)), "-ea", "-cp", str(OUT),
               "com.github.xandergos.terraindiffusionmc.diagnostic.RiverRasterHarness"]).strip())

if __name__ == "__main__":
    main()
