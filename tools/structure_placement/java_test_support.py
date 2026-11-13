"""Small shared process helpers for dependency-free Java structure checks."""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
from typing import Iterable


DEFAULT_JAVA_HOME = Path("C:/Program Files/Java/jdk-21")


def java_bin() -> Path:
    """Return the configured JDK bin directory, defaulting to the local Java 21."""
    return Path(os.environ.get("JAVA_HOME", DEFAULT_JAVA_HOME)) / "bin"


def ensure_output(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    return path


def run(command: Iterable[str | Path]) -> None:
    """Run a Java tool without a Windows console and forward all of its output."""
    result = subprocess.run(
        [str(part) for part in command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, errors="replace",
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0, check=False,
    )
    if result.stdout:
        print(result.stdout, end="", flush=True)
    result.check_returncode()


def compile_and_run(output: Path, sources: Iterable[Path], test_class: str,
                    *, encoding: bool = True) -> None:
    """Compile source files into *output*, then execute the supplied test class."""
    output = ensure_output(output)
    javac = [java_bin() / "javac"]
    if encoding:
        javac.extend(("-encoding", "UTF-8"))
    javac.extend(("-d", output, *sources))
    run(javac)
    run((java_bin() / "java", "-cp", output, test_class))
