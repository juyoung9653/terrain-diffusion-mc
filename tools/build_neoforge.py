"""Build the local NeoForge CUDA disk-library artifact with a fixed toolchain.

This is intentionally narrower than the Gradle task aliases: it is the release
path used for the local Prism instance, so it never falls back to a user-profile
Gradle cache or an inherited Java selection.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from probe_suite import environment, exclusive_lock
from build_evidence import check_validations, sha256, source_fingerprint, write_release

NEOFORGE_VERSION = "21.1.249"
DEFAULT_JAVA_HOME = Path("C:/Program Files/Java/jdk-21")


def build_plan(root: Path, *, java_home: Path | None = None,
               gradle_user_home: Path | None = None) -> tuple[list[str], dict[str, str]]:
    root = root.resolve()
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    cache = (gradle_user_home or root / ".gradle").resolve()
    java = (java_home or DEFAULT_JAVA_HOME).resolve()
    if not wrapper.is_file():
        raise ValueError(f"missing Gradle wrapper: {wrapper}")
    if not cache.is_dir():
        raise ValueError(f"missing populated Gradle user home: {cache}")
    executable = ".exe" if os.name == "nt" else ""
    if not all((java / "bin" / (tool + executable)).is_file() for tool in ("java", "javac")):
        raise ValueError(f"missing JDK java/javac executables under: {java}")
    command = [str(wrapper), ":neoforge:build", "--offline", "--no-daemon",
               "--console=plain", "--gradle-user-home", str(cache),
               "-PuseCuda=true", "-PneoforgeDiskLibraries=true",
               f"-Pneoforge_version={NEOFORGE_VERSION}"]
    # gradlew.bat is parsed by cmd.exe even with shell=False.
    if os.name == "nt" and any(re.search(r'[&|<>^%!"\r\n]', argument) for argument in command):
        raise ValueError("Windows Gradle arguments contain cmd.exe metacharacters")
    return command, environment(str(java), str(cache))


def run(root: Path, *, java_home: Path | None = None,
        gradle_user_home: Path | None = None, check_only: bool = False,
        validations: list[Path] | None = None) -> int:
    command, env = build_plan(root, java_home=java_home, gradle_user_home=gradle_user_home)
    if check_only:
        check_validations(validations or [], source_fingerprint(root))
        print(json.dumps({"command": command, "JAVA_HOME": env["JAVA_HOME"],
                          "GRADLE_USER_HOME": env["GRADLE_USER_HOME"]}))
        return 0
    with exclusive_lock(root.resolve()):
        fingerprint = source_fingerprint(root)
        evidence = check_validations(validations or [], fingerprint)
        completed = subprocess.run(command, cwd=root, env=env, shell=False,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if completed.returncode == 0:
            from memory_probe.disk_libraries import properties
            receipt = properties(root / "neoforge/build/disk-libraries/onnx-runtime-receipt.properties")
            if Path(receipt["modFile"]).name != receipt["modFile"]:
                raise ValueError("unsafe mod filename in build receipt")
            mod = root / "neoforge/build/libs" / receipt["modFile"]
            if sha256(mod) != receipt["modSha256"]:
                raise ValueError("built JAR does not match ONNX receipt")
            write_release(root, mod, fingerprint, evidence, command)
    return completed.returncode


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--gradle-user-home", type=Path)
    parser.add_argument("--check-only", action="store_true")
    parser.add_argument("--validation", type=Path, action="append", default=[],
                        help="successful probe validation-evidence.json; repeat for each required run")
    args = parser.parse_args()
    return run(ROOT, java_home=args.java_home, gradle_user_home=args.gradle_user_home,
               check_only=args.check_only, validations=args.validation)


if __name__ == "__main__":
    raise SystemExit(main())
