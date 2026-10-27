"""Fail-closed installer for the reviewed Terrain Diffusion memory package.

The installer is intentionally separate from staging.  It first validates every
input against the running instance, then makes a timestamped backup outside the
mods directory, and only then copies companions before replacing their parents.
Run without ``--apply`` for a side-effect-free preflight.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path

import disk_libraries
from externalize_nested import BURNT, TARGETS, VOXY_PROFILES, METADATA, sha256, select, verify_voxy_parent


class InstallError(RuntimeError):
    pass


PLAN_NAME = "externalization-plan.json"
RECEIPT_NAME = "onnx-runtime-receipt.properties"
TD_RECEIPT_SUFFIX = ".onnx-receipt.properties"
MODERNFIX = Path("config") / "modernfix-mixins.properties"


def load_plan(stage: Path) -> dict:
    path = stage / PLAN_NAME
    try:
        plan = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise InstallError(f"cannot read externalization plan: {path}") from exc
    if plan.get("formatVersion") != 1 or plan.get("mode") != "workspace-staged-externalization":
        raise InstallError("unsupported externalization plan")
    voxy_names = set(VOXY_PROFILES.values())
    sources = set(plan.get("sources", ()))
    selected = sources & voxy_names
    if len(selected) != 1 or sources != selected | {BURNT} or not isinstance(plan.get("inventory"), list):
        raise InstallError("externalization plan has an unexpected source schema")
    plan["voxyFile"] = next(iter(selected))
    return plan


def inventory_by_name(plan: dict) -> dict[str, dict]:
    result: dict[str, dict] = {}
    for item in plan["inventory"]:
        if not isinstance(item, dict) or not isinstance(item.get("file"), str) \
                or not isinstance(item.get("sha256"), str) or item["file"] in result:
            raise InstallError("externalization plan inventory is malformed")
        result[item["file"]] = item
    expected = {plan["voxyFile"], BURNT} | {target.companion for target in TARGETS}
    if set(result) != expected:
        raise InstallError("externalization plan inventory is incomplete or contains unrelated files")
    return result


def nested_onnx_source(mod: Path, expected: str) -> None:
    """Prove that the mod being replaced is the original disk-library source."""
    try:
        with zipfile.ZipFile(mod) as archive:
            matches = [entry for entry in archive.infolist()
                       if entry.filename.startswith("META-INF/jarjar/")
                       and "onnxruntime" in entry.filename.lower()]
            if len(matches) != 1:
                raise InstallError("current Terrain Diffusion jar does not have exactly one embedded ONNX Runtime")
            digest = hashlib.sha256(archive.read(matches[0])).hexdigest()
    except zipfile.BadZipFile as exc:
        raise InstallError(f"current Terrain Diffusion jar is unreadable: {mod}") from exc
    if digest != expected:
        raise InstallError("current Terrain Diffusion embedded ONNX hash does not match the verified receipt")


def assert_absent(mods: Path, names: set[str]) -> None:
    conflicts = sorted(name for name in names if (mods / name).exists())
    if conflicts:
        raise InstallError("existing companion conflict: " + ", ".join(conflicts))


def verify_rewritten_parent(source_path: Path, staged_path: Path, owner: str) -> None:
    """Recheck that staging changed only its selected JarJar members/metadata."""
    try:
        with zipfile.ZipFile(source_path) as source, zipfile.ZipFile(staged_path) as staged:
            metadata_info, metadata, targets = select(source, owner)
            selected = {target.nested_path for target in targets}
            expected_names = set(source.namelist()) - selected
            if set(staged.namelist()) != expected_names:
                raise InstallError(f"staged {owner} parent has unexpected members")
            for name in expected_names - {METADATA}:
                if source.read(name) != staged.read(name):
                    raise InstallError(f"staged {owner} parent changed unrelated member: {name}")
            staged_metadata = json.loads(staged.read(METADATA).decode("utf-8"))
            expected_metadata = dict(metadata)
            expected_metadata["jars"] = [entry for entry in metadata["jars"]
                                         if entry.get("path") not in selected]
            if staged_metadata != expected_metadata:
                raise InstallError(f"staged {owner} JarJar metadata changed unrelated entries")
    except (zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise InstallError(f"staged {owner} parent cannot be validated") from exc


def preflight(instance_minecraft: Path, release: Path, stage: Path) -> dict:
    mods = instance_minecraft / "mods"
    if not mods.is_dir():
        raise InstallError(f"mods directory does not exist: {mods}")
    plan = load_plan(stage)
    inventory = inventory_by_name(plan)
    receipt_path = release / RECEIPT_NAME
    receipt = disk_libraries.properties(receipt_path)
    library, slim_td = release / receipt["libraryFile"], release / receipt["modFile"]
    disk_libraries.verify(receipt_path, library, slim_td)

    # The current TD must contain exactly the original ONNX payload named by the
    # receipt.  This prevents replacement of an arbitrary same-named mod jar.
    current_td = mods / receipt["modFile"]
    if not current_td.is_file():
        raise InstallError(f"current Terrain Diffusion jar is missing: {current_td.name}")
    nested_onnx_source(current_td, receipt["sourceSha256"])

    for name, expected in plan["sources"].items():
        current = mods / name
        if not current.is_file() or sha256(current) != expected:
            raise InstallError(f"current externalization source hash differs: {name}")
    for name, item in inventory.items():
        candidate = stage / name
        if not candidate.is_file() or sha256(candidate) != item["sha256"]:
            raise InstallError(f"staged externalization artifact hash differs: {name}")
    voxy_file = plan["voxyFile"]
    voxy_version = next(version for version, name in VOXY_PROFILES.items() if name == voxy_file)
    try:
        verify_voxy_parent(mods / voxy_file, voxy_version)
    except ValueError as exc:
        raise InstallError(str(exc)) from exc
    verify_rewritten_parent(mods / voxy_file, stage / voxy_file, "voxy")
    verify_rewritten_parent(mods / BURNT, stage / BURNT, "burnt")
    companions = ({receipt["libraryFile"], receipt["modFile"] + TD_RECEIPT_SUFFIX}
                  | {target.companion for target in TARGETS})
    assert_absent(mods, companions)
    return {"mods": mods, "receipt": receipt, "receipt_path": receipt_path,
            "library": library, "slim_td": slim_td, "stage": stage,
            "plan": plan, "inventory": inventory, "current_td": current_td,
            "voxy_file": voxy_file}


def atomic_copy(source: Path, target: Path) -> None:
    with tempfile.NamedTemporaryFile(prefix=target.name + ".", suffix=".tmp", dir=target.parent,
                                     delete=False) as handle:
        temporary = Path(handle.name)
        with source.open("rb") as input_file:
            shutil.copyfileobj(input_file, handle)
        handle.flush()
        os.fsync(handle.fileno())
    try:
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def write_instance_cfg(source: Path, target: Path, collector: str | None,
                       heap_max_mib: int | None) -> None:
    lines = source.read_text(encoding="utf-8").splitlines(keepends=True)
    seen = seen_max = False
    output: list[str] = []
    for line in lines:
        if line.startswith("MinMemAlloc="):
            output.append("MinMemAlloc=4096\n")
            seen = True
        elif heap_max_mib is not None and line.startswith("MaxMemAlloc="):
            output.append(f"MaxMemAlloc={heap_max_mib}\n")
            seen_max = True
        elif collector == "g1" and line.startswith("JvmArgs="):
            # Preserve every unrelated JVM option.  Prism's OverrideJavaArgs is
            # a boolean gate; the arguments themselves belong in JvmArgs.
            args = line.partition("=")[2].rstrip("\r\n")
            for flag in ("-XX:+UseZGC", "-XX:+ZGenerational"):
                args = re.sub(r"(?<!\S)" + re.escape(flag) + r"(?=\s|$)", "", args)
            args = " ".join(args.split())
            if "-XX:+UseG1GC" not in args.split():
                args = (args + " -XX:+UseG1GC").strip()
            output.append("JvmArgs=" + args + "\n")
        elif collector == "g1" and line.startswith("OverrideJavaArgs="):
            output.append("OverrideJavaArgs=true\n")
        else:
            output.append(line)
    if not seen:
        output.append("MinMemAlloc=4096\n")
    if heap_max_mib is not None and not seen_max:
        output.append(f"MaxMemAlloc={heap_max_mib}\n")
    if collector == "g1" and not any(line.startswith("JvmArgs=") for line in lines):
        output.append("JvmArgs=-XX:+UseG1GC\n")
    if collector == "g1" and not any(line.startswith("OverrideJavaArgs=") for line in lines):
        output.append("OverrideJavaArgs=true\n")
    temporary = target.with_suffix(target.suffix + ".new")
    temporary.write_text("".join(output), encoding="utf-8")
    os.replace(temporary, target)


def enable_dynamic_resources(source: Path, target: Path) -> None:
    text = source.read_text(encoding="utf-8")
    key = "mixin.perf.dynamic_resources"
    lines = text.splitlines(keepends=True)
    changed = False
    for index, line in enumerate(lines):
        if line.lstrip().startswith(key + "="):
            lines[index] = key + "=true\n"
            changed = True
    if not changed:
        lines.append(key + "=true\n")
    temporary = target.with_suffix(target.suffix + ".new")
    temporary.write_text("".join(lines), encoding="utf-8")
    os.replace(temporary, target)


def backup_root(instance_minecraft: Path) -> Path:
    root = instance_minecraft.parent / ".terrain-diffusion-backups"
    root.mkdir(exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    return Path(tempfile.mkdtemp(prefix=stamp + "-", dir=root))


def apply(prepared: dict, instance_minecraft: Path, *, collector: str | None = None,
          dynamic_resources: bool = False, heap_max_mib: int | None = None,
          fail_after: int | None = None) -> Path:
    """Install atomically per file and rollback our own changes after an error.

    ``fail_after`` is only a test hook; production callers must leave it unset.
    """
    if heap_max_mib is not None and heap_max_mib < 4096:
        raise InstallError("--heap-max-mib must be at least 4096")
    # Recheck directly before the first write.  The caller may have inspected a
    # preflight result minutes earlier while another process changed a mod.
    prepared = preflight(instance_minecraft, prepared["library"].parent, prepared["stage"])
    mods: Path = prepared["mods"]
    receipt: dict[str, str] = prepared["receipt"]
    backup = backup_root(instance_minecraft)
    changed: list[tuple[Path, Path | None, str | None]] = []
    cfg = instance_minecraft.parent / "instance.cfg"
    modernfix = instance_minecraft / MODERNFIX
    if dynamic_resources and not modernfix.is_file():
        raise InstallError("--dynamic-resources requires config/modernfix-mixins.properties")
    originals = [prepared["current_td"], mods / prepared["voxy_file"], mods / BURNT, cfg]
    if dynamic_resources:
        originals.append(modernfix)
    try:
        for original in originals:
            if not original.is_file():
                raise InstallError(f"required original disappeared after preflight: {original}")
            relative = original.relative_to(instance_minecraft.parent)
            destination = backup / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            atomic_copy(original, destination)

        actions: list[tuple[Path, Path, bool, str]] = []
        actions.append((prepared["library"], mods / receipt["libraryFile"], False, receipt["librarySha256"]))
        for target in TARGETS:
            actions.append((prepared["stage"] / target.companion, mods / target.companion, False,
                            prepared["inventory"][target.companion]["sha256"]))
        actions.extend(((prepared["slim_td"], prepared["current_td"], True, receipt["modSha256"]),
                        (prepared["stage"] / prepared["voxy_file"], mods / prepared["voxy_file"], True,
                         prepared["inventory"][prepared["voxy_file"]]["sha256"]),
                        (prepared["stage"] / BURNT, mods / BURNT, True, prepared["inventory"][BURNT]["sha256"])))
        for index, (source, target, replaced, expected) in enumerate(actions, 1):
            if sha256(source) != expected:
                raise InstallError(f"install source changed after preflight: {source.name}")
            atomic_copy(source, target)
            if sha256(target) != expected:
                raise InstallError(f"atomic copy verification failed: {target.name}")
            changed.append((target, backup / target.relative_to(instance_minecraft.parent) if replaced else None,
                            expected))
            if fail_after == index:
                raise OSError("simulated install failure")
        receipt_target = mods / (receipt["modFile"] + TD_RECEIPT_SUFFIX)
        atomic_copy(prepared["receipt_path"], receipt_target)
        changed.append((receipt_target, None, sha256(prepared["receipt_path"])))
        write_instance_cfg(cfg, cfg, collector, heap_max_mib)
        changed.append((cfg, backup / cfg.relative_to(instance_minecraft.parent), None))
        if dynamic_resources:
            enable_dynamic_resources(modernfix, modernfix)
            changed.append((modernfix, backup / modernfix.relative_to(instance_minecraft.parent), None))
        receipt_out = backup / "install-receipt.json"
        receipt_out.write_text(json.dumps({"formatVersion": 1, "backup": str(backup),
            "installed": [{"path": str(path), "sha256": digest} for path, _, digest in changed]}, indent=2) + "\n",
            encoding="utf-8")
        return receipt_out
    except Exception:
        rollback_errors = []
        for target, saved, expected in reversed(changed):
            try:
                if expected is not None and target.exists() and sha256(target) != expected:
                    continue  # Never remove/replace a file changed by something else.
                if saved is None:
                    target.unlink(missing_ok=True)
                elif saved.is_file():
                    atomic_copy(saved, target)
            except Exception as exc:  # best-effort rollback, reported with original failure
                rollback_errors.append(f"{target}: {exc}")
        if rollback_errors:
            raise InstallError("installation failed and rollback was incomplete: " + "; ".join(rollback_errors))
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", type=Path, required=True, help="instance minecraft directory")
    parser.add_argument("--release", type=Path, required=True, help="verified optimized-release directory")
    parser.add_argument("--externalization-stage", type=Path, required=True)
    parser.add_argument("--apply", action="store_true", help="perform the already-verified installation")
    parser.add_argument("--collector", choices=["g1"])
    parser.add_argument("--dynamic-resources", action="store_true")
    parser.add_argument("--heap-max-mib", type=int,
                        help="set Prism MaxMemAlloc; omitted preserves its current value")
    args = parser.parse_args()
    prepared = preflight(args.minecraft, args.release, args.externalization_stage)
    if not args.apply:
        print("preflight passed; no files were changed")
        return
    print(apply(prepared, args.minecraft, collector=args.collector,
                dynamic_resources=args.dynamic_resources, heap_max_mib=args.heap_max_mib))


if __name__ == "__main__":
    main()
