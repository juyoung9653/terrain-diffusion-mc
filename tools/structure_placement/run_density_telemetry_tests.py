"""Compile and run the dependency-free density telemetry unit checks."""

from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21")) / "bin"
OUT = ROOT / "build" / "density-telemetry-tests"
OUT.mkdir(parents=True, exist_ok=True)
SOURCE = ROOT / "tools/structure_placement/probe-java/com/github/xandergos/terraindiffusionmc/diagnostic/DensityProbeTelemetry.java"
TEST = Path(__file__).with_name("DensityProbeTelemetryTest.java")
WINDOWLESS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0

def run(command):
    result = subprocess.run(command, capture_output=True, text=True, creationflags=WINDOWLESS)
    if result.stdout:
        print(result.stdout, end="")
    if result.stderr:
        print(result.stderr, end="")
    result.check_returncode()

run([str(JAVA / "javac"), "-encoding", "UTF-8", "-d", str(OUT), str(SOURCE), str(TEST)])
run([str(JAVA / "java"), "-cp", str(OUT), "DensityProbeTelemetryTest"])
