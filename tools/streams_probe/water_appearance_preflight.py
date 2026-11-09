"""Cheap appearance-contract checks before any Gradle/Minecraft launch."""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def streams_jar(run: Path) -> Path:
    """Select by the fixture's active mod metadata, never the player's instance."""
    candidates = []
    for jar in sorted((run / "mods").glob("*.jar")):
        with zipfile.ZipFile(jar) as archive:
            for name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
                if name not in archive.namelist():
                    continue
                metadata = tomllib.loads(archive.read(name).decode("utf-8"))
                for mod in metadata.get("mods", []):
                    if mod.get("modId") == "streamsreflowing":
                        candidates.append((jar, mod.get("version")))
                break
    if len(candidates) != 1:
        raise ValueError(f"appearance preflight requires exactly one active Streams JAR: {run}")
    jar, version = candidates[0]
    if version != "2.16.2":
        raise ValueError(f"appearance preflight supports Streams 2.16.2, found {version}: {jar}")
    return jar


def run_check(label: str, args: list[str], env: dict[str, str]) -> None:
    print(f"[appearance preflight] {label}", flush=True)
    result = subprocess.run([sys.executable, "-B", *args], cwd=ROOT, env=env,
                            capture_output=True, text=True,
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    if result.returncode:
        detail = (result.stdout + result.stderr).strip()
        raise RuntimeError(f"{label} failed; Gradle/Minecraft not started\n{detail}")
    print((result.stdout + result.stderr).strip(), flush=True)


def check_receipt_contract(env: dict[str, str]) -> None:
    run_check("receipt-format regression tests",
              ["-m", "unittest", "tools.streams_probe.test_water_appearance_receipt"], env)


def check_streams_contract(run: Path, env: dict[str, str]) -> None:
    jar = streams_jar(run)
    home = Path(env.get("JAVA_HOME", ""))
    suffix = ".exe" if os.name == "nt" else ""
    if not env.get("JAVA_HOME") or not all((home / "bin" / (name + suffix)).is_file()
                                             for name in ("java", "javac", "javap")):
        raise ValueError("appearance preflight requires JAVA_HOME with java, javac and javap")
    for script, label in (("test_2162_bed_hook.py", "native depth-helper compatibility"),
                          ("test_2162_adapter.py", "native logical-accessor compatibility")):
        run_check(f"{label}: {jar}",
                  [str(ROOT / "tools/streams_probe" / script), "--jar", str(jar),
                   "--java-home", str(home)], env)
