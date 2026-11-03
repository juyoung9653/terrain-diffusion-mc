"""Compile and run the dependency-free underground geology contract test."""
from __future__ import annotations
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "geology-test"
SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/geology/GeologyModel.java"
TEST = ROOT / "tools/geology/GeologyModelTest.java"

def main() -> None:
    homes = [Path(os.environ["JAVA_HOME"])] if os.environ.get("JAVA_HOME") else []
    homes += [Path(r"C:\Program Files\Android\Android Studio\jbr"), Path(r"C:\Program Files\Java\jdk-21")]
    jdk = next((home for home in homes if (home / "bin/javac.exe").is_file()), None)
    if jdk is None: raise SystemExit("JDK 21 with javac is required")
    OUT.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(jdk / "bin/javac.exe"), "--release", "21", "-d", str(OUT), str(SOURCE), str(TEST)], check=True, cwd=ROOT)
    subprocess.run([str(jdk / "bin/java.exe"), "-ea", "-cp", str(OUT), "GeologyModelTest"], check=True, cwd=ROOT)

if __name__ == "__main__": main()
