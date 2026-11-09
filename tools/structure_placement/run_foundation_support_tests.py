"""Compile and exercise the bounded natural-floor repair admission policy."""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21")) / "bin"
OUT = ROOT / "build" / "foundation-support-tests"
OUT.mkdir(parents=True, exist_ok=True)
SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure"
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0

def run(command):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, errors="replace", creationflags=FLAGS)
    print(result.stdout, end="", flush=True)
    result.check_returncode()

run([str(JAVA / "javac"), "-encoding", "UTF-8", "-d", str(OUT),
     str(SOURCE / "FoundationSupport.java"),
     str(Path(__file__).with_name("FoundationSupportTest.java"))])
run([str(JAVA / "java"), "-cp", str(OUT), "FoundationSupportTest"])
