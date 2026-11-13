"""Bind local probe results to production sources and the built NeoForge JAR.

These local receipts detect stale/mismatched evidence, not malicious forgery or
missing test coverage. Selecting sufficient checks remains a reviewer decision.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROBE_RECEIPT = "validation-evidence.json"
RELEASE_SUFFIX = ".validation.json"


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def source_fingerprint(root: Path) -> str:
    # Hash inputs, not git HEAD: this workspace frequently tests uncommitted code.
    paths = set()
    for module in ("common", "neoforge"):
        for tree in ("main", "client", "generated"):
            paths.update(p for p in (root / module / "src" / tree).rglob("*") if p.is_file())
        paths.update((root / module).glob("*.gradle*"))
        properties = root / module / "gradle.properties"
        if properties.is_file():
            paths.add(properties)
    for pattern in ("*.gradle*", "gradle.properties", "gradlew*", "gradle/**/*", "libs/**/*"):
        paths.update(p for p in root.glob(pattern) if p.is_file())
    files = {p.relative_to(root).as_posix(): sha256(p) for p in sorted(paths)}
    return hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()


def read_validation(path: Path, fingerprint: str) -> dict:
    row = json.loads(path.read_text(encoding="utf-8"))
    if (row.get("schema") != 1 or row.get("kind") != "probe-validation"
            or row.get("success") is not True or row.get("processExit") != 0
            or row.get("probeStatus") != "success"):
        raise ValueError(f"missing successful probe evidence: {path}")
    if row.get("sourceFingerprint") != fingerprint:
        raise ValueError(f"validation source differs from current production inputs: {path}")
    receipt = (path.parent / row["receipt"]).resolve()
    if not receipt.is_relative_to(path.parent.resolve()) or sha256(receipt) != row.get("receiptSha256"):
        raise ValueError(f"validation result was replaced or changed: {path}")
    manifest = path.parent / "input-manifest.json"
    if row.get("inputManifestSha256") != (sha256(manifest) if manifest.is_file() else None):
        raise ValueError(f"validation fixture manifest changed: {path}")
    return {"path": str(path.resolve()), "sha256": sha256(path), "result": row}


def check_validations(paths: list[Path], fingerprint: str) -> list[dict]:
    return [read_validation(path, fingerprint) for path in paths]


def assert_release_jar(mod: Path) -> None:
    with zipfile.ZipFile(mod) as archive:
        diagnostic = [name for name in archive.namelist()
                      if "/diagnostic/" in name or "GrassWriteTrace" in name
                      or name.startswith(("td-ice-trace", "td-grass-write-trace",
                                          "td-terrain-replay", "td-shore-audit"))]
    if diagnostic:
        raise ValueError(f"release contains diagnostic output: {diagnostic[0]}")


def write_release(root: Path, mod: Path, fingerprint: str, validations: list[dict],
                  build_command: list[str]) -> Path:
    if source_fingerprint(root) != fingerprint:
        raise ValueError("production sources changed during build")
    # Recheck the original evidence too, not only its pre-build in-memory copy.
    for entry in validations:
        path = Path(entry["path"])
        if sha256(path) != entry["sha256"]:
            raise ValueError("probe evidence changed during build")
        read_validation(path, fingerprint)
    assert_release_jar(mod)
    target = mod.with_name(mod.name + RELEASE_SUFFIX)
    value = {"schema": 1, "kind": "validated-neoforge-build", "sourceFingerprint": fingerprint,
             "modSha256": sha256(mod), "validations": validations, "buildCommand": build_command}
    temporary = target.with_name(target.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    temporary.replace(target)
    return target


def verify_release(mod: Path, root: Path = ROOT) -> dict:
    path = mod.with_name(mod.name + RELEASE_SUFFIX)
    row = json.loads(path.read_text(encoding="utf-8"))
    if row.get("schema") != 1 or row.get("kind") != "validated-neoforge-build" or not row.get("validations"):
        raise ValueError("installation requires a build with --validation evidence")
    if row.get("modSha256") != sha256(mod):
        raise ValueError("installation JAR differs from validated build")
    fingerprint = source_fingerprint(root)
    if row.get("sourceFingerprint") != fingerprint:
        raise ValueError("production sources changed since validated build")
    for entry in row["validations"]:
        evidence = Path(entry["path"])
        if sha256(evidence) != entry["sha256"]:
            raise ValueError("installation probe evidence changed")
        read_validation(evidence, fingerprint)
    assert_release_jar(mod)
    return row
