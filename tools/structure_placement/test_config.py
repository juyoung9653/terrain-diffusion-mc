"""Compile the actual config implementation against cached Gson, without Minecraft."""
from pathlib import Path
import os
import subprocess
import shutil

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21")) / "bin"
target = ROOT / "build/structure-placement/config-tests"
target.mkdir(parents=True, exist_ok=True)
caches = [ROOT / ".gradle", ROOT / ".gradle-user-home", Path.home() / ".gradle"]
gson = next(p for c in caches for p in c.glob("caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar"))
local_gson = target / "gson.jar"
shutil.copyfile(gson, local_gson)
gson = local_gson
package = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc"
subprocess.run([str(JAVA / "javac"), "-encoding", "UTF-8", "-cp", str(gson), "-d", str(target),
                str(package / "platform/PlatformPaths.java"), str(package / "config/StructurePlacementConfig.java"),
                str(Path(__file__).with_name("StructureConfigTest.java"))], check=True)
subprocess.run([str(JAVA / "java"), "-ea", "-cp", os.pathsep.join([str(target), str(gson)]),
                "StructureConfigTest", str(target)], check=True)
