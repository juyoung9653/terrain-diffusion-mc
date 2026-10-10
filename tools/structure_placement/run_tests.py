"""Compile and run the production pure geometry without starting Minecraft."""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21")) / "bin"
OUT = ROOT / "build" / "structure-placement-tests"
OUT.mkdir(parents=True, exist_ok=True)
SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure/PlacementGeometry.java"
subprocess.run([str(JAVA / "javac"), "-d", str(OUT), str(SOURCE), str(Path(__file__).with_name("PlacementGeometryTest.java"))], check=True)
subprocess.run([str(JAVA / "java"), "-cp", str(OUT), "PlacementGeometryTest"], check=True)
