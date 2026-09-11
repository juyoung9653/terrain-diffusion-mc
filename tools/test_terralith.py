"""Run Terralith checks without downloading models or starting Minecraft.

Requires a JDK, Python, the source Terralith JAR and a vanilla 1.21.1 client JAR.
The integration validator supports --vanilla when the Gradle cache is elsewhere.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--vanilla", type=Path)
    args = parser.parse_args()
    java_home = args.java_home or (Path(os.environ["JAVA_HOME"]) if "JAVA_HOME" in os.environ else None)
    suffix = ".exe" if os.name == "nt" else ""
    javac = java_home / "bin" / ("javac" + suffix) if java_home else shutil.which("javac")
    java = java_home / "bin" / ("java" + suffix) if java_home else shutil.which("java")
    if not javac or not java:
        parser.error("JDK missing; supply --java-home")
    target = ROOT / "build" / "terralith-tests"
    target.mkdir(parents=True, exist_ok=True)
    package = Path("com/github/xandergos/terraindiffusionmc/pipeline")
    sources = [ROOT / "common/src/main/java" / package / (name + ".java")
               for name in ("FastNoiseLite", "TerralithBiomeClassifier", "BiomeClassifier", "MacroBiomeContext")]
    sources.append(ROOT / "common/src/test/java" / package / "TerralithBiomeClassifierTest.java")
    sources.append(ROOT / "common/src/test/java" / package / "MacroBiomeContextTest.java")
    world_package = Path("com/github/xandergos/terraindiffusionmc/world")
    sources.append(ROOT / "common/src/main/java" / world_package / "LowlandCarvingGuard.java")
    sources.append(ROOT / "common/src/test/java" / world_package / "LowlandCarvingGuardTest.java")
    run(javac, "--release", "21", "-encoding", "UTF-8", "-d", target, *sources)
    classpath = os.pathsep.join([str(target), str(ROOT / "common/src/main/resources")])
    run(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeClassifierTest", args.jar.resolve())
    run(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.pipeline.MacroBiomeContextTest")
    run(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.world.LowlandCarvingGuardTest")
    run(sys.executable, "-B", "-m", "unittest", "discover", "-s", "tools/terralith/tests", "-v")
    run(sys.executable, "-B", "-m", "unittest", "discover", "-s", "tools/terralith_integration", "-v")
    for inverse in (False, True):
        command = [sys.executable, "-B", "tools/terralith_integration/validate_resources.py", "--terralith", args.jar.resolve()]
        if inverse:
            command.append("--common-before-terralith")
        if args.vanilla:
            command.extend(["--vanilla", args.vanilla.resolve()])
        run(*command)
    print("Terralith checks: PASS (engine startup and scenery require a separate game check)")


if __name__ == "__main__":
    main()
