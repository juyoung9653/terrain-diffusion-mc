"""Regression using the inspected Streams Reflowing JAR supplied as first argument."""
from pathlib import Path
import os, subprocess, sys
root=Path(__file__).resolve().parents[2]
java=Path(os.environ.get('JAVA_HOME','C:/Program Files/Java/jdk-21'))/'bin'
jar=Path(sys.argv[1]).resolve()
assert jar.is_file()
out=root/'build/structure-placement/preflight-tests'
out.mkdir(parents=True,exist_ok=True)
source=root/'common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure'
subprocess.run([str(java/'javac'),'-encoding','UTF-8','-d',str(out),str(source/'PlacementGeometry.java'),str(source/'StructurePreflightControl.java'),str(Path(__file__).with_name('PreflightControlTest.java'))],check=True)
subprocess.run([str(java/'java'),'-cp',os.pathsep.join([str(out),str(jar)]),'PreflightControlTest'],check=True)
