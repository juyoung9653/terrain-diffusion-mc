"""Bind local probe results to production sources and the built NeoForge JAR.

These local receipts detect stale/mismatched evidence, not malicious forgery.
Streams 2.17.3 has mandatory river coverage. Ecology-enabled releases also require
an independent biome realism review of the exact candidate artifact.
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


def input_fingerprint(root: Path, inputs: tuple[Path, ...]) -> str:
    """Snapshot selected diagnostic inputs, including missing paths and new files."""
    root = root.resolve()
    files = {}
    for raw in inputs:
        path = (root / raw).resolve()
        if not path.is_relative_to(root):
            raise ValueError(f"diagnostic input outside workspace: {raw}")
        key = path.relative_to(root).as_posix()
        if path.is_dir():
            files[key + "/"] = "directory"
            for child in sorted(path.rglob("*")):
                if child.is_file() and "__pycache__" not in child.parts and child.suffix != ".pyc":
                    if not child.resolve().is_relative_to(root):
                        raise ValueError(f"diagnostic input outside workspace: {child}")
                    files[child.relative_to(root).as_posix()] = sha256(child)
        else:
            files[key] = sha256(path) if path.is_file() else None
    return hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()


def read_validation(path: Path, fingerprint: str, root: Path = ROOT) -> dict:
    row = json.loads(path.read_text(encoding="utf-8"))
    if (row.get("schema") != 1 or row.get("kind") != "probe-validation"
            or row.get("success") is not True or row.get("processExit") != 0
            or row.get("probeStatus") != "success"):
        raise ValueError(f"missing successful probe evidence: {path}")
    if row.get("sourceFingerprint") != fingerprint:
        raise ValueError(f"validation source differs from current production inputs: {path}")
    if "diagnosticInputs" in row or "diagnosticInputsSha256" in row:
        inputs = row.get("diagnosticInputs")
        if (not isinstance(inputs, list) or not inputs
                or any(not isinstance(item, str) or Path(item).is_absolute() for item in inputs)
                or input_fingerprint(root, tuple(Path(item) for item in inputs))
                != row.get("diagnosticInputsSha256")):
            raise ValueError(f"validation diagnostic inputs changed: {path}")
    receipt = (path.parent / row["receipt"]).resolve()
    if not receipt.is_relative_to(path.parent.resolve()) or sha256(receipt) != row.get("receiptSha256"):
        raise ValueError(f"validation result was replaced or changed: {path}")
    manifest = path.parent / "input-manifest.json"
    if row.get("inputManifestSha256") != (sha256(manifest) if manifest.is_file() else None):
        raise ValueError(f"validation fixture manifest changed: {path}")
    return {"path": str(path.resolve()), "sha256": sha256(path), "result": row}


def check_validations(paths: list[Path], fingerprint: str, root: Path = ROOT) -> list[dict]:
    entries = [read_validation(path, fingerprint, root) for path in paths]
    from streams_probe.release_checks import require_streams_coverage
    require_streams_coverage(entries, root=root)
    return entries


def assert_release_jar(mod: Path) -> None:
    with zipfile.ZipFile(mod) as archive:
        diagnostic = [name for name in archive.namelist()
                      if "/diagnostic/" in name or "GrassWriteTrace" in name
                      or name.startswith(("td-ice-trace", "td-grass-write-trace",
                                          "td-terrain-replay", "td-shore-audit"))]
    if diagnostic:
        raise ValueError(f"release contains diagnostic output: {diagnostic[0]}")


def _canonical_jar_name(name: str) -> tuple[str, ...]:
    if not isinstance(name, str) or not name or "\\" in name or name.startswith("/"):
        raise ValueError("biome review baseline JAR has an unsafe entry name")
    parts = tuple(name.split("/"))
    if any(part in ("", ".", "..") for part in parts):
        raise ValueError("biome review baseline JAR has an unsafe entry name")
    return parts


def _translation_jar_entry(name: str) -> bool:
    parts = _canonical_jar_name(name)
    return len(parts) == 4 and parts[0] == "assets" and parts[2] == "lang" and parts[3].endswith(".json")


def _jar_entries(path: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("biome review baseline JAR has duplicate entries")
        values = {}
        for name in names:
            _canonical_jar_name(name.rstrip("/"))
            if not name.endswith("/"):
                values[name] = archive.read(name)
        return values


def _translation_payload(payload: bytes) -> None:
    try:
        value = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise ValueError("biome review exemption requires valid translation JSON") from failure
    if not isinstance(value, dict) or any(not isinstance(key, str) or not isinstance(text, str)
                                         for key, text in value.items()):
        raise ValueError("biome review exemption requires flat translation JSON")


def _review_baseline(review_path: Path, jar: Path, root: Path = ROOT) -> dict:
    """Bind a translation-only release to an existing fully validated direct review."""
    from biome_review.contract import validate_reviewed_baseline
    review_path, jar = review_path.resolve(), jar.resolve()
    artifact = sha256(jar)
    reviewed = validate_reviewed_baseline(review_path, root, artifact)
    return {"reviewPath": str(review_path), "reviewSha256": sha256(review_path),
            "jarPath": str(jar), "jarSha256": artifact,
            "sourceFingerprint": reviewed["sourceFingerprint"], "artifactSha256": artifact}


def _translation_only_jar_change(baseline: dict, candidate: Path) -> dict:
    before, after = _jar_entries(Path(baseline["jarPath"])), _jar_entries(candidate)
    changed = sorted(name for name in set(before) | set(after) if before.get(name) != after.get(name))
    if not changed or any(not _translation_jar_entry(name) for name in changed):
        raise ValueError("biome review exemption permits only changed translation JSON JAR entries")
    for name in changed:
        _translation_payload(before.get(name, b"{}"))
        _translation_payload(after.get(name, b"{}"))
    return dict(baseline, candidateJarSha256=sha256(candidate), changedJarEntries=changed)


def check_biome_review(root: Path, path: Path | None, *, releasing: bool,
                       artifact: str | None = None, baseline_review: Path | None = None,
                       baseline_jar: Path | None = None, candidate_jar: Path | None = None) -> dict | None:
    from biome_review.contract import required, validate_review
    if path is not None and (baseline_review is not None or baseline_jar is not None):
        raise ValueError("supply --biome-review or both baseline review/JAR arguments, not both")
    if (baseline_review is None) != (baseline_jar is None):
        raise ValueError("biome review exemption requires both --biome-review-baseline and --biome-review-baseline-jar")
    if path is not None:
        return validate_review(path, root, artifact)
    if releasing and required(root):
        if baseline_review is None:
            raise ValueError("release requires --biome-review from an independent realism review")
        baseline = _review_baseline(baseline_review, baseline_jar, root)
        if artifact is not None and candidate_jar is None:
            raise ValueError("biome review exemption candidate JAR is missing")
        if candidate_jar is not None:
            proof = _translation_only_jar_change(baseline, candidate_jar)
            if proof["candidateJarSha256"] != artifact:
                raise ValueError("biome review exemption candidate JAR differs from the release artifact")
            return {"exemption": proof}
        return {"exemption": baseline}
    if baseline_review is not None:
        raise ValueError("biome review baseline is only valid for a releasing build")
    return None


def write_release(root: Path, mod: Path, fingerprint: str, validations: list[dict],
                  build_command: list[str], biome_review: Path | None = None,
                  biome_review_baseline: Path | None = None,
                  biome_review_baseline_jar: Path | None = None) -> Path:
    if source_fingerprint(root) != fingerprint:
        raise ValueError("production sources changed during build")
    # Recheck the original evidence too, not only its pre-build in-memory copy.
    for entry in validations:
        path = Path(entry["path"])
        if sha256(path) != entry["sha256"]:
            raise ValueError("probe evidence changed during build")
        read_validation(path, fingerprint, root)
    from streams_probe.release_checks import require_streams_coverage
    require_streams_coverage(validations, root=root)
    assert_release_jar(mod)
    independent = check_biome_review(root, biome_review, releasing=bool(validations), artifact=sha256(mod),
                                     baseline_review=biome_review_baseline,
                                     baseline_jar=biome_review_baseline_jar, candidate_jar=mod)
    target = mod.with_name(mod.name + RELEASE_SUFFIX)
    value = {"schema": 1, "kind": "validated-neoforge-build", "sourceFingerprint": fingerprint,
             "modSha256": sha256(mod), "validations": validations, "buildCommand": build_command,
             "biomeReview": independent}
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
        read_validation(evidence, fingerprint, root)
    from streams_probe.release_checks import require_streams_coverage
    require_streams_coverage(row["validations"], root=root)
    independent = row.get("biomeReview")
    if isinstance(independent, dict) and "exemption" in independent:
        exemption = independent["exemption"]
        review_path, jar_path = Path(exemption.get("reviewPath", "")), Path(exemption.get("jarPath", ""))
        if not review_path.is_file() or sha256(review_path) != exemption.get("reviewSha256"):
            raise ValueError("installation biome review baseline changed or is missing")
        if not jar_path.is_file() or sha256(jar_path) != exemption.get("jarSha256"):
            raise ValueError("installation biome review baseline JAR changed or is missing")
        baseline = _review_baseline(review_path, jar_path, root)
        if _translation_only_jar_change(baseline, mod) != exemption:
            raise ValueError("installation biome review exemption differs")
    else:
        review_path = Path(independent["path"]) if independent else None
        if independent and sha256(review_path) != independent.get("sha256"):
            raise ValueError("installation independent biome review changed")
        check_biome_review(root, review_path, releasing=True, artifact=sha256(mod))
    assert_release_jar(mod)
    return row
