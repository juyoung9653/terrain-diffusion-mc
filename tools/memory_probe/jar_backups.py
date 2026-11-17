"""Keep the one recoverable Terrain Diffusion installation preceding the current one."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import tempfile
import uuid
from dataclasses import dataclass
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _linked(path: Path) -> bool:
    return path.is_symlink() or getattr(os.path, "isjunction", lambda _: False)(path)


def validate_destination_path(mods_dir: Path) -> None:
    """Reject links/junctions in the destination path before any installer writes."""
    absolute = mods_dir.absolute()
    for ancestor in (absolute, *absolute.parents):
        if _linked(ancestor):
            raise ValueError(f"linked install destination component: {ancestor}")


def _regular(path: Path, *, required: bool = True) -> bool:
    if not path.exists():
        if required:
            raise ValueError(f"missing backup input: {path}")
        return False
    if _linked(path) or not path.is_file():
        raise ValueError(f"backup input must be a regular file: {path}")
    return True


def _name(value: str) -> str:
    if not value or Path(value).name != value:
        raise ValueError(f"unsafe backup filename: {value!r}")
    return value


def backup_root(mods_dir: Path) -> Path:
    validate_destination_path(mods_dir)
    if mods_dir.name != "mods" or _linked(mods_dir) or not mods_dir.is_dir():
        raise ValueError(f"backup requires a real mods directory: {mods_dir}")
    minecraft = mods_dir.parent
    instance = minecraft.parent if minecraft.name.lower() in {"minecraft", ".minecraft"} else minecraft
    root = instance / ".terrain-diffusion-backups" / "td-jar"
    for parent in (instance / ".terrain-diffusion-backups", root):
        if _linked(parent) or (parent.exists() and not parent.is_dir()):
            raise ValueError(f"unsafe backup directory: {parent}")
    return root


def _copy(source: Path, destination: Path) -> dict[str, object]:
    _regular(source)
    _name(destination.name)
    with source.open("rb") as input_file, tempfile.NamedTemporaryFile(
            prefix=destination.name + ".", suffix=".tmp", dir=destination.parent,
            delete=False) as temporary:
        temporary_path = Path(temporary.name)
        shutil.copyfileobj(input_file, temporary)
    try:
        if sha256(source) != sha256(temporary_path):
            raise ValueError(f"backup copy hash mismatch: {source}")
        temporary_path.replace(destination)
    finally:
        temporary_path.unlink(missing_ok=True)
    return {"name": destination.name, "sha256": sha256(destination),
            "bytes": destination.stat().st_size}


def _safe_remove_directory(path: Path) -> None:
    if _linked(path) or (path.exists() and not path.is_dir()):
        raise ValueError(f"unsafe managed backup directory: {path}")
    if not path.exists():
        return
    for child in path.iterdir():
        if _linked(child) or not child.is_file():
            raise ValueError(f"unsafe managed backup member: {child}")
        child.unlink()
    path.rmdir()


def _validate_managed_directory(path: Path, *, published: bool = False) -> None:
    if _linked(path) or (path.exists() and not path.is_dir()):
        raise ValueError(f"unsafe managed backup directory: {path}")
    if not path.exists():
        return
    for child in path.iterdir():
        if _linked(child) or not child.is_file():
            raise ValueError(f"unsafe managed backup member: {child}")
    if not published:
        return
    metadata_path = path / "backup.json"
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        raise ValueError(f"invalid managed backup metadata: {metadata_path}") from failure
    if metadata.get("schema") != 1 or metadata.get("kind") != "terrain-diffusion-previous-install":
        raise ValueError(f"invalid managed backup metadata: {metadata_path}")
    previous = metadata.get("previous")
    replacement = metadata.get("supersededBy")
    if not isinstance(previous, dict) or not isinstance(replacement, dict):
        raise ValueError(f"invalid managed backup metadata: {metadata_path}")
    expected = {"backup.json"}
    for key in ("mod", "sidecar", "library"):
        entry = previous.get(key)
        if key == "mod" and not isinstance(entry, dict):
            raise ValueError(f"invalid managed backup metadata: {metadata_path}")
        if entry is None:
            continue
        if not isinstance(entry, dict) or set(entry) != {"name", "sha256", "bytes"}:
            raise ValueError(f"invalid managed backup metadata: {metadata_path}")
        name = _name(str(entry["name"])); file = path / name
        if not isinstance(entry["bytes"], int) or entry["bytes"] < 0 or not isinstance(entry["sha256"], str):
            raise ValueError(f"invalid managed backup metadata: {metadata_path}")
        if not file.is_file() or file.stat().st_size != entry["bytes"] or sha256(file) != entry["sha256"]:
            raise ValueError(f"managed backup content mismatch: {file}")
        expected.add(name)
    for key in ("mod", "library"):
        entry = replacement.get(key)
        if not isinstance(entry, dict) or set(entry) != {"name", "sha256", "bytes"}:
            raise ValueError(f"invalid managed backup metadata: {metadata_path}")
        if (_name(str(entry["name"])) != entry["name"] or not isinstance(entry["sha256"], str)
                or not isinstance(entry["bytes"], int) or entry["bytes"] < 0):
            raise ValueError(f"invalid managed backup metadata: {metadata_path}")
    if {child.name for child in path.iterdir()} != expected:
        raise ValueError(f"unexpected managed backup member: {path}")


def _reject_pending(root: Path) -> None:
    if not root.exists():
        return
    pending = [child for child in root.iterdir()
               if child.name.startswith((".staging-", ".retired-"))]
    if pending:
        raise ValueError("unfinished Terrain Diffusion backup recovery state: " +
                         ", ".join(str(path) for path in pending))


def validate_retention_state(mods_dir: Path) -> None:
    """Validate destination and any existing managed state before replacement begins."""
    validate_destination_path(mods_dir)
    if not mods_dir.exists():
        return
    root = backup_root(mods_dir)
    _reject_pending(root)
    _validate_managed_directory(root / "previous", published=True)


def _old_library(mods_dir: Path, old_mod: Path, sidecar: Path, new_library: Path) -> Path | None:
    if not sidecar.exists():
        return None
    _regular(sidecar)
    values: dict[str, str] = {}
    for line in sidecar.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator:
            values[key] = value
    name = values.get("libraryFile")
    expected = values.get("librarySha256")
    if not name or not expected or values.get("modSha256") != sha256(old_mod):
        raise ValueError(f"old ONNX receipt does not identify its installed mod/library: {sidecar}")
    candidate = mods_dir / _name(name)
    _regular(candidate)
    if sha256(candidate) != expected:
        raise ValueError(f"old ONNX receipt library hash mismatch: {candidate}")
    if sha256(candidate) == sha256(new_library):
        return None
    return candidate


@dataclass(frozen=True)
class StagedBackup:
    root: Path
    stage: Path
    old_mod_sha256: str


def stage_previous(mods_dir: Path, mod_name: str, incoming_mod: Path,
                   incoming_library: Path) -> StagedBackup | None:
    """Copy the replaced distinct installation to an uncommitted managed staging dir."""
    _name(mod_name)
    validate_retention_state(mods_dir)
    old_mod = mods_dir / mod_name
    if not old_mod.exists():
        return None
    _regular(old_mod)
    old_hash = sha256(old_mod)
    old_sidecar = mods_dir / (mod_name + ".onnx-receipt.properties")
    old_library = _old_library(mods_dir, old_mod, old_sidecar, incoming_library)
    if old_hash == sha256(incoming_mod):
        if old_library is not None:
            raise ValueError("same Terrain Diffusion JAR with a different ONNX library is unsupported")
        return None
    root = backup_root(mods_dir)
    root.mkdir(parents=True, exist_ok=True)
    if _linked(root) or not root.is_dir():
        raise ValueError(f"unsafe backup directory: {root}")
    _reject_pending(root)
    _validate_managed_directory(root / "previous", published=True)
    stage = root / (".staging-" + uuid.uuid4().hex)
    stage.mkdir()
    try:
        files = {"mod": _copy(old_mod, stage / mod_name)}
        if old_sidecar.exists():
            files["sidecar"] = _copy(old_sidecar, stage / old_sidecar.name)
        if old_library is not None:
            files["library"] = _copy(old_library, stage / old_library.name)
        metadata = {"schema": 1, "kind": "terrain-diffusion-previous-install",
                    "previous": files}
        (stage / "backup.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n",
                                           encoding="utf-8")
        return StagedBackup(root, stage, str(files["mod"]["sha256"]))
    except Exception:
        _safe_remove_directory(stage)
        raise


def commit_previous(staged: StagedBackup, installed_mod: Path,
                    installed_library: Path) -> None:
    """Publish a fully staged backup after every replacement artifact verifies."""
    _regular(installed_mod)
    _regular(installed_library)
    metadata_path = staged.stage / "backup.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    metadata["supersededBy"] = {
        "mod": {"name": installed_mod.name, "sha256": sha256(installed_mod),
                "bytes": installed_mod.stat().st_size},
        "library": {"name": installed_library.name, "sha256": sha256(installed_library),
                    "bytes": installed_library.stat().st_size},
    }
    metadata_path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    previous = staged.root / "previous"
    retired = staged.root / (".retired-" + uuid.uuid4().hex)
    if previous.exists():
        _validate_managed_directory(previous, published=True)
        previous.replace(retired)
    try:
        staged.stage.replace(previous)
    except Exception:
        if retired.exists():
            retired.replace(previous)
        raise
    _validate_managed_directory(retired, published=True)
    _safe_remove_directory(retired)
