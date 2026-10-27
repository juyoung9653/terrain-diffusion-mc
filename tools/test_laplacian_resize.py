"""Compile and run exact bilinear-resize parity checks without Gradle."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--benchmark", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    home = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21"))
    suffix = ".exe" if os.name == "nt" else ""
    javac = home / "bin" / ("javac" + suffix)
    java = home / "bin" / ("java" + suffix)
    if not javac.exists(): javac = Path(shutil.which("javac") or "javac")
    if not java.exists(): java = Path(shutil.which("java") or "java")
    output = root / "build/laplacian-resize-tests"
    output.mkdir(parents=True, exist_ok=True)
    package = root / "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline"
    commands = [[javac, "--release", "21", "-encoding", "UTF-8", "-d", output,
                 package / "LaplacianUtils.java", package / "ExactTerrainSampling.java",
                 root / "tools/terrain_sampling/LaplacianResizeParityTest.java"],
                [java, "-cp", output, "com.github.xandergos.terraindiffusionmc.pipeline.LaplacianResizeParityTest"]]
    if args.benchmark: commands[-1].append("--benchmark")
    receipt = output / "validation.txt"
    with receipt.open("w", encoding="utf-8") as log:
        for command in commands:
            log.write("$ " + " ".join(map(str, command)) + "\n")
            result = subprocess.run(list(map(str, command)), cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            log.write(result.stdout)
            if result.returncode: raise SystemExit(result.returncode)
    print(receipt.read_text(encoding="utf-8"), end="")

if __name__ == "__main__": main()
