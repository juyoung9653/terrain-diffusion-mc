"""Compile and run the frozen MacroBiomeContext parity check without Gradle."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--benchmark", action="store_true",
                        help="report warmed old/new 256x256 median build times")
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    java_home = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21"))
    suffix = ".exe" if os.name == "nt" else ""
    javac = java_home / "bin" / ("javac" + suffix)
    java = java_home / "bin" / ("java" + suffix)
    if not javac.exists():
        javac = Path(shutil.which("javac") or "javac")
    if not java.exists():
        java = Path(shutil.which("java") or "java")

    package = root / "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline"
    output = root / "build/macro-context-tests"
    sources = [package / (name + ".java") for name in (
        "FastNoiseLite", "BiomeClassifier", "TerralithBiomeClassifier", "ShoreContext", "MacroBiomeContext")]
    sources.append(root / "tools/terrain_sampling/MacroBiomeContextParityTest.java")
    output.mkdir(parents=True, exist_ok=True)

    compile_command = [javac, "--release", "21", "-encoding", "UTF-8", "-d", output, *sources]
    run_command = [java, "-cp", output,
                   "com.github.xandergos.terraindiffusionmc.pipeline.MacroBiomeContextParityTest"]
    if args.benchmark:
        run_command.append("--benchmark")
    receipt = output / "validation.txt"
    with receipt.open("w", encoding="utf-8") as log:
        for command in (compile_command, run_command):
            log.write("$ " + " ".join(map(str, command)) + "\n")
            completed = subprocess.run(list(map(str, command)), cwd=root, text=True,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            log.write(completed.stdout)
            if completed.returncode:
                raise SystemExit(completed.returncode)
    print(receipt.read_text(encoding="utf-8"), end="")


if __name__ == "__main__":
    main()
