"""Verify the production tensor overlap-add against independent scalar enumeration."""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21")))
    parser.add_argument("--benchmark", action="store_true")
    args = parser.parse_args()
    out = ROOT / "build/tensor-add-tests"
    out.mkdir(parents=True, exist_ok=True)
    binary = lambda name: str(args.java_home / "bin" / (name + (".exe" if os.name == "nt" else "")))
    subprocess.run([binary("javac"), "-d", str(out),
                    str(ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/infinitetensor/FloatTensor.java"),
                    str(ROOT / "tools/terrain_sampling/FloatTensorAddTest.java")], check=True)
    subprocess.run([binary("java"), "-cp", str(out), "FloatTensorAddTest", *(["--benchmark"] if args.benchmark else [])], check=True)


if __name__ == "__main__":
    main()
