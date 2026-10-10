"""Fail-closed input and tile manifest for diagnostic terrain replay."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

from tools.structure_placement.prepare_fixture import (MANIFEST, NAME, _check_target, _real_components,
                             _sha256, _source_files as _prepared_files, _walk_files)
from probe_suite import is_link

SCHEMA = 1
STRUCTURE_SOURCE = "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure/"
STRUCTURE_CONFIG = "common/src/main/java/com/github/xandergos/terraindiffusionmc/config/StructurePlacementConfig.java"
FIXTURE_CONFIG = "config/terrain-diffusion-structures.json"
EXCLUSIONS = (STRUCTURE_SOURCE + "**", STRUCTURE_CONFIG, FIXTURE_CONFIG)


def _absolute(path: Path) -> Path:
    return Path(os.path.abspath(path))


def _json(path: Path) -> dict:
    if is_link(path) or not path.is_file() or path.stat().st_nlink != 1:
        raise ValueError(f"missing, linked, or hardlinked manifest: {path}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"invalid manifest: {path}")
    return value


def _fixture_files(run: Path) -> dict[str, str]:
    manifest = _json(run / MANIFEST)
    expected = manifest.get("sha256")
    if manifest.get("version") != 1 or not isinstance(expected, dict):
        raise ValueError("invalid prepared fixture manifest")
    actual = _prepared_files(run)
    for name in ("density-center.txt", "density-radius.txt"):
        path = run / name
        if is_link(path) or not path.is_file():
            raise ValueError(f"missing or linked density control: {path}")
        actual[name] = path
    if set(actual) != set(expected):
        raise ValueError("prepared fixture input set differs from its manifest")
    _check_target(run, set(actual) | {MANIFEST})
    hashes = {name: _sha256(path) for name, path in sorted(actual.items())}
    if hashes != expected:
        raise ValueError("prepared fixture inputs differ from their manifest")
    return {name: value for name, value in hashes.items() if name != FIXTURE_CONFIG}


def _source_files(root: Path) -> dict[str, str]:
    paths: dict[str, Path] = {}
    # Discover modules instead of trusting a fixed list: new production inputs invalidate replay.
    for module in root.iterdir():
        if is_link(module):
            raise ValueError(f"linked repository entry: {module}")
        for source_root in ("src/main", "src/client", "src/generated"):
            main = module / source_root
            _real_components(main, root)
            if main.is_dir():
                paths.update({path.relative_to(root).as_posix(): path
                              for path in _walk_files(main)})
        if module.is_dir() and module.name not in ("build", ".gradle", ".git"):
            for path in module.glob("*.gradle*"):
                if path.is_file() or is_link(path):
                    paths[path.relative_to(root).as_posix()] = path
            properties = module / "gradle.properties"
            if properties.is_file() or is_link(properties):
                paths[properties.relative_to(root).as_posix()] = properties
    for pattern in ("*.gradle*", "gradle.properties", "gradlew*", "gradle/wrapper/*",
                    "gradle/**/*", "libs/**/*", "tools/probe_suite.py",
                    "tools/structure_placement/run_suite.py",
                    "tools/structure_placement/prepare_fixture.py",
                    "tools/structure_placement/terrain_snapshot.py",
                    "tools/structure_placement/density.init.gradle",
                    "tools/structure_placement/probe-java/**/*",
                    "tools/structure_placement/probe-resources/**/*"):
        for path in root.glob(pattern):
            if path.is_file() or is_link(path):
                paths[path.relative_to(root).as_posix()] = path
    if not any(name.startswith("common/src/main/") for name in paths):
        raise ValueError("missing common production sources")
    result = {}
    for name, path in sorted(paths.items()):
        if name.startswith(STRUCTURE_SOURCE) or name == STRUCTURE_CONFIG:
            continue
        _real_components(path, root)
        if not path.is_file() or path.stat().st_nlink != 1:
            raise ValueError(f"invalid production input: {path}")
        result[name] = _sha256(path)
    return result


def _tile_hashes(snapshot: Path) -> dict[str, str]:
    allowed = {"request.json", "completion.json"}
    result = {}
    if is_link(snapshot) or not snapshot.is_dir():
        raise ValueError(f"missing or linked snapshot directory: {snapshot}")
    for path in snapshot.iterdir():
        name = path.name
        if is_link(path) or not path.is_file():
            raise ValueError(f"unexpected snapshot entry: {path}")
        if name in allowed:
            continue
        if name == ".tiles.lock" and path.stat().st_nlink == 1 and path.stat().st_size == 0:
            continue
        if not name.endswith(".tile") or name == ".tile":
            raise ValueError(f"unexpected snapshot entry: {name}")
        if path.stat().st_nlink != 1:
            raise ValueError(f"hardlinked snapshot tile: {path}")
        result[name] = _sha256(path)
    if not result:
        raise ValueError("snapshot has no terrain tiles")
    return result


def _fingerprint(files: dict) -> str:
    encoded = json.dumps({"schema": SCHEMA, "files": files, "exclusions": EXCLUSIONS},
                         sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def prepare_snapshot(root: Path, run: Path, snapshot: Path, mode: str) -> str:
    """Validate a fresh prepared run and create or verify its terrain snapshot."""
    if mode not in ("record", "replay"):
        raise ValueError(f"invalid terrain mode: {mode}")
    root, run, snapshot = map(_absolute, (root, run, snapshot))
    family = root / "build/structure-density"
    snapshots = root / "build/terrain-replay"
    if (run.parent != family or not NAME.fullmatch(run.name) or
            snapshot.parent != snapshots or not NAME.fullmatch(snapshot.name)):
        raise ValueError("run and snapshot must be direct safe-name children of their build families")
    if run == snapshot or run.is_relative_to(snapshot) or snapshot.is_relative_to(run):
        raise ValueError("run and snapshot overlap")
    _real_components(run, root)
    _real_components(snapshot, root)
    if not run.is_dir():
        raise ValueError(f"missing prepared fixture: {run}")
    files = {"fixture": _fixture_files(run), "source": _source_files(root)}
    fingerprint = _fingerprint(files)
    request = {"schema": SCHEMA, "fingerprint": fingerprint,
               "files": files, "exclusions": list(EXCLUSIONS)}
    if mode == "record":
        if snapshot.exists():
            raise ValueError(f"snapshot already exists: {snapshot}")
        snapshots.mkdir(parents=True, exist_ok=True)
        snapshot.mkdir()
        (snapshot / "request.json").write_text(json.dumps(request, indent=2) + "\n",
                                                encoding="utf-8")
    else:
        if _json(snapshot / "request.json") != request:
            raise ValueError("snapshot fingerprint or input manifest differs from current inputs")
        completion = _json(snapshot / "completion.json")
        tiles = _tile_hashes(snapshot)
        if (completion.get("schema") != SCHEMA or completion.get("snapshotId") != fingerprint
                or completion.get("tiles") != tiles):
            raise ValueError("snapshot seal or terrain tile hashes differ")
    return fingerprint


def seal_snapshot(snapshot: Path, receipt: Path) -> None:
    """Seal recorded tiles only after a successful record receipt is available."""
    snapshot, receipt = _absolute(snapshot), _absolute(receipt)
    for path in (snapshot, *snapshot.parents, receipt, *receipt.parents):
        if is_link(path):
            raise ValueError(f"linked snapshot or receipt path: {path}")
    if is_link(snapshot) or not snapshot.is_dir():
        raise ValueError(f"invalid snapshot directory: {snapshot}")
    if (snapshot.parent.name != "terrain-replay" or snapshot.parent.parent.name != "build"
            or not NAME.fullmatch(snapshot.name)):
        raise ValueError("snapshot must be a direct safe-name build/terrain-replay child")
    request = _json(snapshot / "request.json")
    if (request.get("schema") != SCHEMA or not isinstance(request.get("files"), dict)
            or request.get("exclusions") != list(EXCLUSIONS)
            or request.get("fingerprint") != _fingerprint(request["files"])
            or (snapshot / "completion.json").exists()):
        raise ValueError("snapshot request is invalid or already sealed")
    report = _json(receipt)
    replay = report.get("terrainReplay")
    if (report.get("success") is not True or report.get("terrainMode") != "record"
            or not isinstance(replay, dict) or replay.get("mode") != "record"
            or replay.get("snapshotId") != request["fingerprint"]
            or type(replay.get("recordedTiles")) is not int or replay["recordedTiles"] <= 0
            or type(replay.get("replayedTiles")) is not int or replay["replayedTiles"] < 0
            or type(replay.get("misses")) is not int or replay["misses"] != 0
            or not isinstance(replay.get("snapshotIoSeconds"), (int, float))
            or replay["snapshotIoSeconds"] < 0 or "bypassed" not in replay
            or replay.get("failure")):
        raise ValueError("record receipt lacks successful terrain replay evidence")
    tiles = _tile_hashes(snapshot)
    if replay["recordedTiles"] < len(tiles):
        raise ValueError("record receipt counts fewer tiles than the snapshot contains")
    completion = {"schema": SCHEMA, "snapshotId": request["fingerprint"], "tiles": tiles}
    path = snapshot / "completion.json"
    with path.open("x", encoding="utf-8") as stream:
        json.dump(completion, stream, indent=2)
        stream.write("\n")
