"""Verify Supplementaries' galleon placement contract from a supplied artifact."""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RESERVATIONS = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure/StructureReservations.java"
CLASS = "net.mehvahdjukaar.supplementaries.common.worldgen.RandomSpreadStructurePlacementWithExclusion"


def java_tool(name: str) -> Path:
    java_home = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr"))
    tool = java_home / "bin" / f"{name}.exe"
    if not tool.is_file():
        raise FileNotFoundError(f"JDK 21 {name} not found: set JAVA_HOME (looked for {tool})")
    return tool


def artifact_version(jar: Path) -> str:
    with zipfile.ZipFile(jar) as archive:
        try:
            manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
        except KeyError:
            return "manifest-missing"
    fields = dict(
        line.split(": ", 1) for line in manifest.replace("\r\n", "\n").split("\n") if ": " in line
    )
    return fields.get("Implementation-Version", fields.get("Specification-Version", "manifest-version-missing"))


def javap_for(jar: Path) -> str:
    result = subprocess.run(
        [str(java_tool("javap")), "-p", "-classpath", str(jar), CLASS],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    if result.returncode:
        raise RuntimeError(f"javap failed for {jar}:\n{result.stderr.strip()}")
    return result.stdout


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--jar", type=Path, help="Supplementaries JAR to inspect with JDK javap")
    source.add_argument("--javap", type=Path, help="previous captured javap output (evidence-only replay)")
    return parser.parse_args()


def main() -> None:
    args = arguments()
    if args.jar:
        jar = args.jar.resolve()
        if not jar.is_file():
            raise FileNotFoundError(f"Supplementaries JAR not found: {jar}")
        with jar.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        print(f"GalleonPlacementAudit: jar={jar.name} sha256={digest} version={artifact_version(jar)}")
        audit = javap_for(jar)
    else:
        evidence = args.javap.resolve()
        if not evidence.is_file():
            raise FileNotFoundError(f"javap evidence not found: {evidence}")
        print(f"GalleonPlacementAudit: javap-evidence={evidence}")
        audit = evidence.read_text(encoding="utf-8")
    assert "extends net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement" in audit
    assert "public boolean applyInteractionsWithOtherStructures" in audit
    assert "getPotentialStructureChunk" not in audit, "grid override needs a new placement audit"
    source = RESERVATIONS.read_text(encoding="utf-8")
    assert f'"{CLASS}"' in source
    assert "placement.getClass()==RandomSpreadStructurePlacement.class" in source
    assert "VERIFIED_SPREAD_SUBCLASSES.contains(placement.getClass().getName())" in source
    print("GalleonPlacementAudit: PASS inherited random-spread grid; native exclusions retained at generation")

if __name__ == "__main__":
    try:
        main()
    except (AssertionError, FileNotFoundError, RuntimeError, zipfile.BadZipFile) as error:
        print(f"GalleonPlacementAudit: FAIL {error}", file=sys.stderr)
        raise SystemExit(1)
