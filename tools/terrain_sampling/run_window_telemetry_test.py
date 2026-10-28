"""Compile and run the pure-JDK tensor-window telemetry regression."""
from __future__ import annotations

import subprocess
import tempfile
import os
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/infinitetensor"
TEST = ROOT / "tools/terrain_sampling/WindowTelemetryTest.java"


def main() -> None:
    sources = [PACKAGE / name for name in (
        "FloatTensor.java", "TensorWindow.java", "TensorFunction.java",
        "BatchTensorFunction.java", "InfiniteTensor.java", "MemoryTileStore.java",
    )] + [TEST]
    workspace_temp = ROOT / ".tmp"
    workspace_temp.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="td-window-telemetry-", dir=workspace_temp))
    try:
        output = directory
        java_home = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-21"))
        javac = java_home / "bin" / "javac.exe"
        java = java_home / "bin" / "java.exe"
        if not javac.is_file() or not java.is_file():
            raise RuntimeError(f"JDK 21 required; JAVA_HOME does not contain javac/java: {java_home}")
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        def run(command: list[str]) -> None:
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, errors="replace", creationflags=flags)
            print(result.stdout, end="", flush=True)
            result.check_returncode()
        run([str(javac), "-d", str(output), *(str(source) for source in sources)])
        run([str(java), "-cp", str(output),
             "com.github.xandergos.terraindiffusionmc.infinitetensor.WindowTelemetryTest"])
    finally:
        directory.resolve().relative_to(workspace_temp.resolve())
        shutil.rmtree(directory, ignore_errors=True)


if __name__ == "__main__":
    main()
