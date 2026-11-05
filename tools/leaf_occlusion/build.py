"""Build the standalone client-only leaf-occlusion JAR without Gradle."""
from __future__ import annotations
import argparse, shutil, subprocess, sys, time, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/optimization_bench"))
import launch

HERE = Path(__file__).resolve().parent
OUT = HERE / "build"

def main() -> None:
    parser = argparse.ArgumentParser(); parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    classes = OUT / ("classes-" + str(time.time_ns())); classes.mkdir(parents=True, exist_ok=False)
    sources = sorted((HERE / "java").rglob("*.java"))
    command = [str(launch.DEFAULT_JAVA.with_name("javac.exe")), "-encoding", "UTF-8", "--release", "21", "-proc:none",
               "-cp", launch.compile_classpath(launch.DEFAULT_LIBRARIES), "-d", str(classes), *map(str, sources)]
    result = subprocess.run(command, text=True, capture_output=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if result.returncode:
        print(result.stdout); print(result.stderr, file=sys.stderr); raise SystemExit(result.returncode)
    output = (args.output or OUT / "td-leaf-occlusion.jar").resolve(); output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists(): output.unlink()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for source in classes.rglob("*"):
            if source.is_file(): archive.write(source, source.relative_to(classes).as_posix())
        for source in (HERE / "resources").rglob("*"):
            if source.is_file(): archive.write(source, source.relative_to(HERE / "resources").as_posix())
    print(output)

if __name__ == "__main__": main()
