"""Compile TerrainDiffusionConfig and exercise isolated tile-size static-init cases."""
from pathlib import Path
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    home = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21"))
    suffix = ".exe" if os.name == "nt" else ""
    javac = home / "bin" / ("javac" + suffix)
    java = home / "bin" / ("java" + suffix)
    if not javac.exists(): javac = Path(shutil.which("javac") or "javac")
    if not java.exists(): java = Path(shutil.which("java") or "java")
    output = ROOT / "build/config-tile-size-tests"
    output.mkdir(parents=True, exist_ok=True)
    package = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc"
    sources = [package / "platform/PlatformPaths.java", package / "config/TerrainDiffusionConfig.java",
               ROOT / "tools/terrain_sampling/ConfigTileSizeTest.java"]
    classpath = os.pathsep.join((str(output), str(ROOT / "common/src/main/resources")))
    cases = {"default": None, "valid": "tile_size=512\n", "non-power-of-two": "tile_size=300\n",
             "invalid-int": "tile_size=not-a-number\n"}
    receipt = output / "validation.txt"
    with receipt.open("w", encoding="utf-8") as log:
        def run(command):
            completed = subprocess.run(list(map(str, command)), cwd=ROOT, text=True,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            log.write("$ " + " ".join(map(str, command)) + "\n" + completed.stdout)
            if completed.returncode: raise SystemExit(completed.returncode)
        run([javac, "--release", "21", "-encoding", "UTF-8", "-d", output, *sources])
        for name, properties in cases.items():
            directory = output / "cases" / name
            directory.mkdir(parents=True, exist_ok=True)
            if properties is not None:
                (directory / "terrain-diffusion-mc.properties").write_text(properties, encoding="utf-8")
            run([java, "-cp", classpath, "com.github.xandergos.terraindiffusionmc.config.ConfigTileSizeTest",
                 directory, "512" if name == "valid" else "256"])
    print(receipt.read_text(encoding="utf-8"), end="")


if __name__ == "__main__":
    main()
