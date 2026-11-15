"""Fail before Minecraft starts when a prepared Streams fixture lacks its audited adapter checks."""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tomllib
import zipfile
from typing import Callable


def _streams_metadata(jar: Path) -> str | None:
    try:
        with zipfile.ZipFile(jar) as archive:
            data = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8-sig"))
    except KeyError:
        # Libraries and Fabric/client-only JARs can share this fixture directory.
        return None
    except (UnicodeDecodeError, zipfile.BadZipFile, tomllib.TOMLDecodeError) as error:
        raise ValueError(f"cannot read Streams metadata from {jar.name}: {error}") from error
    versions = [str(mod.get("version")) for mod in data.get("mods", [])
                if mod.get("modId") == "streamsreflowing"]
    if len(versions) > 1:
        raise ValueError(f"Streams metadata declares streamsreflowing more than once: {jar.name}")
    return versions[0] if versions else None


def find_streams_jar(mods_dir: Path) -> tuple[Path, str] | None:
    """Return the only Streams JAR identified by its metadata, never its filename."""
    if not mods_dir.exists():
        return None
    if not mods_dir.is_dir():
        raise ValueError(f"fixture mods path is not a directory: {mods_dir}")
    matches: list[tuple[Path, str]] = []
    for jar in sorted(mods_dir.glob("*.jar")):
        version = _streams_metadata(jar)
        if version is not None:
            matches.append((jar, version))
    if len(matches) > 1:
        names = ", ".join(jar.name for jar, _ in matches)
        raise ValueError(f"fixture contains multiple Streams JARs: {names}")
    return matches[0] if matches else None


def _sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _commands(root: Path, jar: Path, version: str, java_home: Path) -> list[tuple[str, list[str]]]:
    python = sys.executable
    base = ["--jar", str(jar), "--java-home", str(java_home)]
    scripts = [
        "audit_2171.py",
        "test_2171_adapter.py",
        "test_2171_inference.py",
        "test_2171_network.py",
        "test_2171_seagrass.py",
        "test_2171_gate.py",
    ]
    if version == "2.17.3":
        scripts.extend(("test_2173_bank_material.py", "test_2173_bed_hook.py",
                        "test_2173_raised_cap_policy.py", "test_river_regression_contract.py"))
    commands: list[tuple[str, list[str]]] = []
    for name in scripts:
        command = [python, "-B", str(root / "tools/streams_probe" / name)]
        if name not in {"test_2171_gate.py", "test_2173_raised_cap_policy.py",
                        "test_river_regression_contract.py"}:
            command.extend(base)
        else:
            command.extend(("--java-home", str(java_home)))
        commands.append((name, command))
    return commands


def validate_streams_compat(root: Path, run: Path, java_home: Path,
                            *, execute: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run) -> dict:
    """Run the selected static audit suite against the exact copied fixture JAR.

    2.17.1 and 2.17.3 are audited adapters.  Older explicitly supported versions
    remain runnable but return ``legacy_not_covered``; future versions fail before
    a world can be generated until their bytecode contract is added deliberately.
    """
    found = find_streams_jar(run / "mods")
    if found is None:
        raise ValueError("Streams probe fixture has no Streams Reflowing JAR")
    jar, version = found
    result = {"jar": str(jar), "jarSha256": _sha256(jar), "version": version,
              "checks": []}
    if version in {"2.13.1", "2.13.5", "2.13.8", "2.15.2", "2.16.2"}:
        return {**result, "status": "legacy_not_covered"}
    if version not in {"2.17.1", "2.17.3"}:
        raise ValueError(f"Streams {version} has no audited compatibility contract")
    if not all((java_home / "bin" / f"{tool}.exe").is_file()
               for tool in ("java", "javac", "javap")):
        raise ValueError(f"Streams compatibility preflight requires JDK java/javac/javap: {java_home}")
    environment = os.environ | {"JAVA_HOME": str(java_home)}
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    for name, command in _commands(root, jar, version, java_home):
        completed = execute(command, cwd=root, env=environment, text=True,
                            capture_output=True, creationflags=flags)
        check = {"script": name, "returncode": completed.returncode}
        result["checks"].append(check)
        if completed.returncode:
            detail = (completed.stderr or completed.stdout or "").strip()[-1000:]
            raise ValueError(f"Streams {version} compatibility check failed: {name}: {detail}")
    return {**result, "status": "passed"}
