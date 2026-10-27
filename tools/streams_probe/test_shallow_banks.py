"""Compile the real StreamsShallowBanks class against small JDK-only API stubs and differentially test it.

No Gradle, NeoForge, native Streams JAR, or Minecraft server is started.  The Java harness
keeps an independent scalar copy of the former algorithm and compares observable results.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "shallow-bank-tests"
PRODUCTION = ROOT / "neoforge/src/main/java/com/github/xandergos/terraindiffusionmc/compat/streams/StreamsShallowBanks.java"
HARNESS = ROOT / "tools/streams_probe/ShallowBanksHarness.java"
STUBS = ROOT / "tools/streams_probe/shallow_bank_stubs"


def java_home(requested: Path | None) -> Path:
    candidates: list[Path] = []
    if requested:
        candidates.append(requested)
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    candidates += [Path(r"C:\Program Files\Android\Android Studio\jbr"), Path(r"C:\Program Files\Java\jdk-21"), Path(r"C:\Program Files\Microsoft\jdk-21")]
    for candidate in candidates:
        extension = ".exe" if os.name == "nt" else ""
        if all((candidate / "bin" / (tool + extension)).is_file() for tool in ("javac", "java")):
            return candidate
    raise SystemExit("JDK 21 with javac and java is required; pass --java-home")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--benchmark", action="store_true", help="print warmed median timings; no timing threshold is asserted")
    args = parser.parse_args()
    home = java_home(args.java_home)
    extension = ".exe" if os.name == "nt" else ""
    javac, java = (home / "bin" / (tool + extension) for tool in ("javac", "java"))
    OUT.mkdir(parents=True, exist_ok=True)
    sources = [*sorted(STUBS.rglob("*.java")), PRODUCTION, HARNESS]
    subprocess.run([str(javac), "--release", "21", "-d", str(OUT), *map(str, sources)], cwd=ROOT, check=True)
    command = [str(java), "-ea", "-DshallowBanks.expectOptimized=true", "-cp", str(OUT),
               "com.github.xandergos.terraindiffusionmc.diagnostic.ShallowBanksHarness",
               *(["--benchmark"] if args.benchmark else [])]
    result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (OUT / "validation.txt").write_text(result.stdout, encoding="utf-8")
    sys.stdout.write(result.stdout)
    result.check_returncode()


if __name__ == "__main__":
    main()
