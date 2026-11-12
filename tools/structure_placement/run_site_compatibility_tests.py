"""Compile and run the pure Better Ocean Monuments site-compatibility guard."""
from pathlib import Path
import os, subprocess
ROOT=Path(__file__).resolve().parents[2]
JAVA=Path(os.environ.get("JAVA_HOME","C:/Program Files/Java/jdk-21"))/"bin"
OUT=ROOT/"build"/"structure-site-compatibility-tests"; OUT.mkdir(parents=True,exist_ok=True)
SOURCE=ROOT/"common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure/StructureSiteCompatibility.java"
TEST=Path(__file__).with_name("StructureSiteCompatibilityTest.java")
subprocess.run([str(JAVA/"javac"),"-encoding","UTF-8","-d",str(OUT),str(SOURCE),str(TEST)],check=True)
subprocess.run([str(JAVA/"java"),"-cp",str(OUT),"StructureSiteCompatibilityTest"],check=True)
