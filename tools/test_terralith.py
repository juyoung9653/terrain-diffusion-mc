"""Run Terralith checks without downloading models or starting Minecraft.

Requires a JDK, Python, the source Terralith JAR and a vanilla 1.21.1 client JAR.
The integration validator supports --vanilla when the Gradle cache is elsewhere.
"""
import argparse
from functools import partial
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JAVA_HOME = Path("C:/Program Files/Java/jdk-21")
SCRATCH = ROOT / ".tmp"


def fail(message: str) -> None:
    raise ValueError(message)


def resolve_java_home(explicit: Path | None) -> Path:
    """Choose a real JDK, never Windows' java.exe forwarding shim."""
    if explicit is not None:
        candidates = [explicit]
    else:
        candidates = [DEFAULT_JAVA_HOME] if os.name == "nt" else []
        if os.environ.get("JAVA_HOME"):
            candidates.append(Path(os.environ["JAVA_HOME"]))
        compiler = shutil.which("javac")
        if compiler:
            candidates.append(Path(compiler).resolve().parent.parent)
    suffix = ".exe" if os.name == "nt" else ""
    for candidate in candidates:
        if candidate is None:
            continue
        home = candidate.resolve()
        release = home / "release"
        if ((home / "bin" / ("java" + suffix)).is_file()
                and (home / "bin" / ("javac" + suffix)).is_file()
                and release.is_file()
                and re.search(r'^JAVA_VERSION="21(?:[.\"]|$)', release.read_text(encoding="latin-1"), re.MULTILINE)):
            return home
    requested = explicit if explicit is not None else DEFAULT_JAVA_HOME
    fail(f"JDK 21 with java and javac is required: {requested}")


def validate_terralith_jar(path: Path) -> Path:
    path = path.resolve()
    if not path.is_file():
        fail(f"Terralith JAR does not exist: {path}")
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
    except (OSError, zipfile.BadZipFile) as error:
        fail(f"invalid Terralith JAR: {path}: {error}")
    if not any(name.startswith("data/terralith/worldgen/biome/") for name in names):
        fail(f"JAR lacks Terralith worldgen biome data: {path}")
    return path


def writable_scratch() -> Path:
    scratch = SCRATCH.resolve()
    try:
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=scratch, prefix="terralith-preflight-", delete=False) as stream:
            probe = Path(stream.name)
            stream.write(b"ok")
        if probe.read_bytes() != b"ok":
            fail(f"temporary-file readback failed: {scratch}")
        probe.unlink()
    except OSError as error:
        fail(f"workspace temporary directory is not writable: {scratch}: {error}")
    return scratch


def child_environment(java_home: Path, scratch: Path) -> dict[str, str]:
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(java_home)
    environment["TMP"] = environment["TEMP"] = str(scratch)
    option = '-Djava.io.tmpdir="' + str(scratch).replace('"', '\\"') + '"'
    environment["JAVA_TOOL_OPTIONS"] = (environment.get("JAVA_TOOL_OPTIONS", "") + " " + option).strip()
    environment["PATH"] = str(java_home / "bin") + os.pathsep + environment.get("PATH", "")
    return environment


def run(*args, environment: dict[str, str] | None = None):
    if environment is None:
        raise RuntimeError("test environment has not passed preflight")
    completed = subprocess.run([str(arg) for arg in args], cwd=ROOT, env=environment,
                               check=False, text=True, capture_output=True,
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if completed.stdout:
        print(completed.stdout, end="")
    if completed.stderr:
        print(completed.stderr, end="", file=sys.stderr)
    completed.check_returncode()


def main(argv: list[str] | None = None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--vanilla", type=Path)
    args = parser.parse_args(argv)
    try:
        terralith = validate_terralith_jar(args.jar)
        java_home = resolve_java_home(args.java_home)
        environment = child_environment(java_home, writable_scratch())
    except ValueError as error:
        parser.error(str(error))
    suffix = ".exe" if os.name == "nt" else ""
    javac = java_home / "bin" / ("javac" + suffix)
    java = java_home / "bin" / ("java" + suffix)
    target = ROOT / "build" / "terralith-tests"
    target.mkdir(parents=True, exist_ok=True)
    package = Path("com/github/xandergos/terraindiffusionmc/pipeline")
    sources = [ROOT / "common/src/main/java" / package / (name + ".java")
               for name in ("FastNoiseLite", "TerralithBiomeClassifier", "BiomeClassifier", "MacroBiomeContext",
                            "BroadEcologyClimate", "EcologyClassifier", "EcologyData", "EcologyDataCache", "EcologyShowcaseProfile", "ShoreClassifier", "ShoreContext")]
    for name in ("ShoreClassifierTest", "ShoreContextTest"):
        sources.append(ROOT / "common/src/test/java" / package / (name + ".java"))
    sources.append(ROOT / "common/src/test/java" / package / "TerralithBiomeClassifierTest.java")
    sources.append(ROOT / "common/src/test/java" / package / "MacroBiomeContextTest.java")
    sources.append(ROOT / "common/src/test/java" / package / "BroadEcologyClimateTest.java")
    sources.append(ROOT / "common/src/test/java" / package / "EcologyClassifierTest.java")
    sources.append(ROOT / "common/src/test/java" / package / "SnowPatchGeometryTest.java")
    sources.append(ROOT / "common/src/test/java" / package / "SnowTerrainRetentionTest.java")
    sources.append(ROOT / "common/src/test/java" / package / "EcologyShowcaseProfileTest.java")
    world_package = Path("com/github/xandergos/terraindiffusionmc/world")
    sources.append(ROOT / "common/src/main/java" / world_package / "LowlandCarvingGuard.java")
    sources.append(ROOT / "common/src/test/java" / world_package / "LowlandCarvingGuardTest.java")
    sources.append(ROOT / "common/src/main/java" / world_package / "ExactBiomeSearch.java")
    sources.append(ROOT / "common/src/test/java" / world_package / "ExactBiomeSearchTest.java")
    ecology_package = world_package / "ecology"
    sources.append(ROOT / "common/src/main/java" / ecology_package / "FeatureEcologyMath.java")
    sources.append(ROOT / "common/src/main/java" / ecology_package / "SurfaceBoundaryDither.java")
    sources.append(ROOT / "common/src/test/java" / ecology_package / "FeatureEcologyMathTest.java")
    sources.append(ROOT / "common/src/test/java" / ecology_package / "SurfaceBoundaryDitherTest.java")
    sources.append(ROOT / "common/src/test/java" / ecology_package / "EcotoneContinuityTest.java")
    sources.append(ROOT / "common/src/main/java" / ecology_package / "ShorePlantPolicy.java")
    sources.append(ROOT / "common/src/test/java" / ecology_package / "ShorePlantPolicyTest.java")
    sources.append(ROOT / "common/src/test/java" / ecology_package / "ReliefSurfaceTest.java")
    sources.append(ROOT / "common/src/main/java" / ecology_package / "SubmergedPlantPolicy.java")
    sources.append(ROOT / "common/src/test/java" / ecology_package / "SubmergedPlantPolicyTest.java")
    child = partial(run, environment=environment)
    child(javac, "--release", "21", "-encoding", "UTF-8", "-d", target, *sources)
    classpath = os.pathsep.join([str(target), str(ROOT / "common/src/main/resources")])
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeClassifierTest", terralith)
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.pipeline.MacroBiomeContextTest")
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.pipeline.BroadEcologyClimateTest")
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifierTest")
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.pipeline.SnowPatchGeometryTest")
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.pipeline.SnowTerrainRetentionTest")
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.pipeline.EcologyShowcaseProfileTest")
    for name in ("ShoreClassifierTest", "ShoreContextTest"):
        child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.pipeline." + name)
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.world.LowlandCarvingGuardTest")
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.world.ExactBiomeSearchTest")
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.world.ecology.FeatureEcologyMathTest")
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.world.ecology.SurfaceBoundaryDitherTest")
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.world.ecology.EcotoneContinuityTest")
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.world.ecology.ShorePlantPolicyTest")
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.world.ecology.ReliefSurfaceTest")
    child(java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.world.ecology.SubmergedPlantPolicyTest")
    child(sys.executable, "-B", "-m", "unittest", "discover", "-s", "tools/terralith/tests", "-v")
    child(sys.executable, "-B", "-m", "unittest", "discover", "-s", "tools/terralith_integration", "-v")
    child(sys.executable, "-B", "-m", "unittest", "discover", "-s", "tools/ecology", "-v")
    for inverse in (False, True):
        command = [sys.executable, "-B", "tools/terralith_integration/validate_resources.py", "--terralith", terralith]
        if inverse:
            command.append("--common-before-terralith")
        if args.vanilla:
            command.extend(["--vanilla", args.vanilla.resolve()])
        child(*command)
    print("Terralith checks: PASS (engine startup and scenery require a separate game check)")


if __name__ == "__main__":
    main()
