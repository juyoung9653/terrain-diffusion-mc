"""Compile the production village seed geometry without a Minecraft process."""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21")) / "bin"
OUT = ROOT / "build" / "village-distribution-tests"
OUT.mkdir(parents=True, exist_ok=True)
SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure"
subprocess.run([str(JAVA / "javac"), "-encoding", "UTF-8", "-d", str(OUT),
                str(SOURCE / "PlacementGeometry.java"), str(SOURCE / "VillageDistributionGeometry.java"),
                str(Path(__file__).with_name("VillageDistributionTest.java"))], check=True)
subprocess.run([str(JAVA / "java"), "-cp", str(OUT), "VillageDistributionTest"], check=True)
