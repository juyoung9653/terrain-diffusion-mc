"""Safely apply the validated FP16 decoder tested by a chunk-profile fixture.

The default is a read-only preflight.  ``--apply`` changes exactly the Terrain
Diffusion JAR, ``decoder_model_fp16.onnx``, and the decoder precision setting.
It never touches worlds, caches, saves, or any other mod/configuration file.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psutil


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.memory_probe import disk_libraries
FP16_SHA256 = "af32fdd6955f15611c5fe4696f5a6c96dd008657b361f391db1c948739d125ee"
FP16_BYTES = 56_077_790
CONFIG_RELATIVE = Path("config/terrain-diffusion-mc.properties")
MODEL_RELATIVE = Path("terrain-diffusion-models/decoder_model_fp16.onnx")


class InstallError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def regular_file(path: Path, label: str) -> None:
    if not path.is_file() or path.is_symlink():
        raise InstallError(f"{label} must be a regular file: {path}")


def read_json(path: Path, label: str) -> dict[str, Any]:
    regular_file(path, label)
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise InstallError(f"invalid {label}: {path}") from error
    if not isinstance(value, dict):
        raise InstallError(f"{label} must be a JSON object: {path}")
    return value


def artifact(value: Any, label: str) -> tuple[Path, str, int]:
    if not isinstance(value, dict):
        raise InstallError(f"{label} is absent or malformed")
    path, digest, size = value.get("path"), value.get("sha256"), value.get("bytes")
    if not isinstance(path, str) or not isinstance(digest, str) or not isinstance(size, int):
        raise InstallError(f"{label} lacks path, sha256, or bytes")
    candidate = Path(path).resolve()
    regular_file(candidate, label)
    if candidate.stat().st_size != size or sha256(candidate) != digest:
        raise InstallError(f"{label} changed since fixture preparation: {candidate}")
    return candidate, digest, size


def no_game_processes() -> None:
    found: list[str] = []
    for process in psutil.process_iter(["pid", "name"]):
        try:
            name = (process.info.get("name") or "").lower()
        except (psutil.AccessDenied, psutil.NoSuchProcess):
            continue
        if name in {"java.exe", "javaw.exe", "java", "javaw", "minecraft.exe"}:
            found.append(f"{process.info.get('pid')}:{name}")
    if found:
        raise InstallError("refusing while Java/game processes are running: " + ", ".join(found))


def verify_hash(path: Path, entry: Any, label: str) -> tuple[str, int]:
    regular_file(path, label)
    if not isinstance(entry, dict) or not isinstance(entry.get("sha256"), str) or not isinstance(entry.get("bytes"), int):
        raise InstallError(f"fixture lacks hash metadata for {label}")
    digest, size = sha256(path), path.stat().st_size
    if digest != entry["sha256"] or size != entry["bytes"]:
        raise InstallError(f"{label} does not match fixture hash")
    return digest, size


def successful_fixture(fixture: Path) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    process = read_json(fixture / "process.json", "fixture process record")
    receipt = read_json(fixture / "receipt.json", "fixture receipt")
    launch = read_json(fixture / "launch-result.json", "fixture launch result")
    started = process.get("start_epoch")
    if not isinstance(started, (int, float)):
        raise InstallError("fixture process record lacks start_epoch")
    for path, label in ((fixture / "receipt.json", "receipt"), (fixture / "launch-result.json", "launch result")):
        if path.stat().st_mtime < started:
            raise InstallError(f"fixture {label} predates its process record")
    if (launch.get("exit_code") != 0 or launch.get("timed_out") is not False
            or launch.get("source_world_metadata_unchanged") is not True
            or receipt.get("status") != "success" or launch.get("receipt") != receipt):
        raise InstallError("fixture does not have a fresh successful process and receipt")
    return process, receipt, launch


def rewrite_precision(original: bytes) -> bytes:
    try:
        text = original.decode("utf-8")
    except UnicodeDecodeError as error:
        raise InstallError("Terrain Diffusion config is not UTF-8") from error
    setting = re.compile(r"(?m)^(\s*inference\.decoder_precision\s*=\s*)[^\r\n]*$")
    updated, count = setting.subn(lambda match: match[1] + "fp16", text)
    if count > 1:
        raise InstallError("expected at most one inference.decoder_precision setting")
    if count == 0:
        newline = "\r\n" if "\r\n" in text else "\n"
        if updated and not updated.endswith(("\n", "\r")):
            updated += newline
        updated += "inference.decoder_precision=fp16" + newline
    validation = re.findall(r"(?m)^\s*validate_model\s*=\s*([^\r\n#]+)", text)
    if len(validation) != 1 or validation[0].strip().lower() != "true":
        raise InstallError("validate_model=true must be present exactly once")
    return updated.encode("utf-8")


def safe_target_parent(target: Path, instance: Path) -> None:
    """Require target parent resolution to stay within the selected instance."""
    if not target.parent.is_dir():
        raise InstallError(f"install target parent is absent: {target.parent}")
    try:
        resolved_parent = target.parent.resolve(strict=True)
    except OSError as error:
        raise InstallError(f"cannot resolve install target parent: {target.parent}") from error
    if not resolved_parent.is_relative_to(instance):
        raise InstallError(f"install target parent escapes instance: {target.parent}")


def atomic_bytes(data: bytes, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}.", suffix=".tmp", dir=destination.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def atomic_copy(source: Path, destination: Path) -> None:
    regular_file(source, "install source")
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}.", suffix=".tmp", dir=destination.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(fd, "wb") as stream, source.open("rb") as original:
            shutil.copyfileobj(original, stream, length=1024 * 1024)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def preflight(fixture: Path, instance: Path, backup: Path, runtime_receipt: Path) -> dict[str, Any]:
    fixture, instance, backup = fixture.resolve(), instance.resolve(), backup.resolve()
    runtime_receipt = runtime_receipt.resolve()
    if not fixture.is_dir() or fixture.is_symlink():
        raise InstallError(f"fixture must be a real directory: {fixture}")
    if not instance.is_dir() or instance.is_symlink():
        raise InstallError(f"instance must be a real directory: {instance}")
    if backup.exists():
        raise InstallError(f"backup directory must be fresh: {backup}")
    build_root = (ROOT / "build").resolve()
    if not backup.is_relative_to(build_root) or backup.is_relative_to(instance):
        raise InstallError("backup directory must be under workspace build/ and outside the instance")
    regular_file(runtime_receipt, "disk-library runtime receipt")
    no_game_processes()
    manifest = read_json(fixture / "input-manifest.json", "fixture input manifest")
    if manifest.get("complete") is not True or Path(manifest.get("source", "")).resolve() != instance:
        raise InstallError("fixture source does not identify the requested instance")
    successful_fixture(fixture)
    files, source_files = manifest.get("files"), manifest.get("source_files")
    if not isinstance(files, dict) or not isinstance(source_files, dict):
        raise InstallError("fixture manifest lacks file inventories")
    source_td = manifest.get("source_terrain_diffusion_artifact")
    replacement_td = manifest.get("terrain_diffusion_artifact")
    if not isinstance(source_td, dict) or not isinstance(replacement_td, dict):
        raise InstallError("fixture manifest lacks Terrain Diffusion artifact identities")
    filename = source_td.get("source_filename")
    if not isinstance(filename, str) or Path(filename).name != filename or not filename.lower().endswith(".jar"):
        raise InstallError("fixture original Terrain Diffusion filename is unsafe")
    jar_target = instance / "mods" / filename
    safe_target_parent(jar_target, instance)
    verify_hash(jar_target, source_td, "current Terrain Diffusion JAR")
    jar_source, jar_hash, jar_bytes = artifact(replacement_td, "fixture replacement Terrain Diffusion JAR")
    if replacement_td.get("kind") != "replacement" or replacement_td.get("source_filename") != filename:
        raise InstallError("fixture replacement does not target the original Terrain Diffusion filename")
    try:
        runtime = disk_libraries.properties(runtime_receipt)
    except (OSError, UnicodeError, ValueError) as error:
        raise InstallError(f"invalid disk-library runtime receipt: {runtime_receipt}") from error
    runtime_library = runtime_receipt.parent / runtime["libraryFile"]
    if runtime["modFile"] != filename or jar_source.name != filename:
        raise InstallError("disk-library receipt mod filename does not match the fixture target")
    try:
        disk_libraries.verify(runtime_receipt, runtime_library, jar_source)
    except (OSError, ValueError) as error:
        raise InstallError("disk-library receipt does not verify the new runtime companion and TD JAR") from error
    runtime_target = instance / "mods" / runtime["libraryFile"]
    verify_hash(runtime_target, {"sha256": runtime["librarySha256"], "bytes": runtime_library.stat().st_size},
                "current ONNX Runtime companion")
    config_target = instance / CONFIG_RELATIVE
    safe_target_parent(config_target, instance)
    config_hash, config_bytes = verify_hash(config_target, files.get(CONFIG_RELATIVE.as_posix()), "current Terrain Diffusion config")
    expected_override = manifest.get("input_overrides", {}).get(CONFIG_RELATIVE.as_posix())
    if not isinstance(expected_override, str):
        raise InstallError("fixture has no FP16 config override hash")
    fixture_config = fixture / CONFIG_RELATIVE
    if sha256(fixture_config) != expected_override:
        raise InstallError("fixture FP16 configuration no longer matches its manifest")
    precision = manifest.get("decoder_precision")
    candidate = precision.get("decoder_candidate") if isinstance(precision, dict) else None
    if not isinstance(candidate, dict) or precision.get("mode") != "fp16" or candidate.get("selected_config") != "inference.decoder_precision=fp16":
        raise InstallError("fixture was not prepared with the reviewed FP16 decoder")
    if candidate.get("staged_path") != MODEL_RELATIVE.as_posix():
        raise InstallError("fixture FP16 model path is not the supported separate decoder filename")
    source_candidate = candidate.get("source")
    if not isinstance(source_candidate, dict) or source_candidate.get("sha256") != FP16_SHA256 or source_candidate.get("bytes") != FP16_BYTES:
        raise InstallError("fixture decoder does not match the pinned FP16 artifact")
    model_source = fixture / MODEL_RELATIVE
    model_hash, model_bytes = verify_hash(model_source, files.get(MODEL_RELATIVE.as_posix()), "fixture FP16 decoder")
    if model_hash != FP16_SHA256 or model_bytes != FP16_BYTES:
        raise InstallError("fixture FP16 decoder is not the pinned candidate")
    updated_config = rewrite_precision(config_target.read_bytes())
    safe_target_parent(instance / MODEL_RELATIVE, instance)
    safe_target_parent(instance / "mods" / (filename + ".onnx-receipt.properties"), instance)
    return {
        "fixture": fixture, "instance": instance, "backup": backup,
        "jar": {"source": jar_source, "target": jar_target, "sha256": jar_hash, "bytes": jar_bytes},
        "runtime_receipt": {"source": runtime_receipt, "target": instance / "mods" / (filename + ".onnx-receipt.properties"),
                            "sha256": sha256(runtime_receipt), "bytes": runtime_receipt.stat().st_size,
                            "companion": runtime_target, "companion_sha256": runtime["librarySha256"]},
        "model": {"source": model_source, "target": instance / MODEL_RELATIVE, "sha256": model_hash, "bytes": model_bytes},
        "config": {"target": config_target, "before_sha256": config_hash, "before_bytes": config_bytes,
                   "after": updated_config, "after_sha256": hashlib.sha256(updated_config).hexdigest()},
    }


def backup_file(source: Path, destination: Path) -> dict[str, Any]:
    if not source.exists():
        return {"present": False, "backup": None}
    regular_file(source, "current install target")
    atomic_copy(source, destination)
    return {"present": True, "backup": str(destination), "sha256": sha256(destination), "bytes": destination.stat().st_size}


def apply(prepared: dict[str, Any]) -> Path:
    # Recheck immediately before writes, so a dry run cannot authorize stale files.
    prepared = preflight(prepared["fixture"], prepared["instance"], prepared["backup"], prepared["runtime_receipt"]["source"])
    backup = prepared["backup"]
    backup.mkdir(parents=True, exist_ok=False)
    targets = [prepared["jar"]["target"], prepared["model"]["target"], prepared["runtime_receipt"]["target"], prepared["config"]["target"]]
    saved = {target: backup_file(target, backup / target.relative_to(prepared["instance"])) for target in targets}
    changed: list[Path] = []
    expected_after = {prepared["jar"]["target"]: prepared["jar"]["sha256"],
                      prepared["model"]["target"]: prepared["model"]["sha256"],
                      prepared["runtime_receipt"]["target"]: prepared["runtime_receipt"]["sha256"],
                      prepared["config"]["target"]: prepared["config"]["after_sha256"]}
    try:
        for item in (prepared["jar"], prepared["model"]):
            atomic_copy(item["source"], item["target"])
            changed.append(item["target"])
            if sha256(item["target"]) != item["sha256"]:
                raise InstallError(f"post-copy hash mismatch: {item['target']}")
        # Disk mode keeps the already-verified companion in place.  Publish its
        # matching receipt before enabling FP16 in configuration.
        atomic_copy(prepared["runtime_receipt"]["source"], prepared["runtime_receipt"]["target"])
        changed.append(prepared["runtime_receipt"]["target"])
        if sha256(prepared["runtime_receipt"]["target"]) != prepared["runtime_receipt"]["sha256"]:
            raise InstallError("post-copy runtime receipt hash mismatch")
        # Configuration is last: a failed earlier write cannot select a missing model.
        atomic_bytes(prepared["config"]["after"], prepared["config"]["target"])
        changed.append(prepared["config"]["target"])
        if sha256(prepared["config"]["target"]) != prepared["config"]["after_sha256"]:
            raise InstallError("post-write config hash mismatch")
        receipt = {
            "schema": 1, "status": "applied", "created_utc": datetime.now(timezone.utc).isoformat(),
            "fixture": str(prepared["fixture"]), "instance": str(prepared["instance"]), "backup": str(backup),
            "before": {str(target.relative_to(prepared["instance"])): saved[target] for target in targets},
            "after": [{"path": str(target.relative_to(prepared["instance"])), "sha256": expected_after[target],
                        "bytes": target.stat().st_size} for target in targets],
        }
        receipt_path = backup / "install-receipt.json"
        atomic_bytes((json.dumps(receipt, indent=2, sort_keys=True) + "\n").encode("utf-8"), receipt_path)
        return receipt_path
    except BaseException:
        failures = []
        for target in reversed(changed):
            try:
                if not target.is_file() or sha256(target) != expected_after[target]:
                    failures.append(f"{target}: target changed before rollback")
                    continue
                state = saved[target]
                if state["present"]:
                    atomic_copy(Path(state["backup"]), target)
                else:
                    target.unlink(missing_ok=True)
            except Exception as error:
                failures.append(f"{target}: {error}")
        if failures:
            raise InstallError("installation failed and rollback was incomplete: " + "; ".join(failures))
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, required=True, help="successful chunk-profile FP16 fixture")
    parser.add_argument("--instance", type=Path, required=True, help="the exact instance recorded by the fixture")
    parser.add_argument("--backup-directory", type=Path, required=True, help="fresh workspace directory for originals and receipt")
    parser.add_argument("--runtime-receipt", type=Path, required=True,
                        help="verified -PneoforgeDiskLibraries runtime receipt beside its companion")
    parser.add_argument("--apply", action="store_true", help="perform the verified installation; default is dry-run")
    args = parser.parse_args()
    prepared = preflight(args.fixture, args.instance, args.backup_directory, args.runtime_receipt)
    if not args.apply:
        print(json.dumps({"status": "dry-run-success", "fixture": str(prepared["fixture"]),
                          "instance": str(prepared["instance"]), "backup": str(prepared["backup"]),
                          "targets": [str(prepared[key]["target"]) for key in ("jar", "model", "runtime_receipt", "config")],
                          "unchanged_runtime_companion": str(prepared["runtime_receipt"]["companion"])}, indent=2))
        return 0
    print(apply(prepared))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except InstallError as error:
        print(f"refused: {error}", file=sys.stderr)
        raise SystemExit(2)
