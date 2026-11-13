#!/usr/bin/env python3
"""Observe an explicitly prepared copied save using the installed mod environment."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT))
from tools.streams_probe.tree_ground_runtime import validate_tree_ground_mixin_jar, validate_tree_ground_runtime

WORLD_RE = re.compile(r"TD-River-[A-Za-z0-9._-]{1,71}\Z")
TEMP_PROBE = "td-instance-river-diagnostic.jar"
RECEIPT = "td-instance-client-probe-receipt.json"


def load_launch():
    source = ROOT / "tools" / "optimization_bench" / "launch.py"
    spec = importlib.util.spec_from_file_location("td_instance_client_launch", source)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load launcher: {source}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tree_hashes(root: Path) -> dict[str, str]:
    if not root.is_dir():
        return {}
    return {str(path.relative_to(root)).replace("\\", "/"): sha256(path)
            for path in sorted(root.rglob("*")) if path.is_file()}


def instance_snapshot(game: Path) -> dict:
    mods = game / "mods"
    if not mods.is_dir():
        raise ValueError(f"missing mods directory: {mods}")
    return {
        "mods": {path.name: sha256(path) for path in sorted(mods.glob("*.jar"))},
        "config": tree_hashes(game / "config"),
        "models": tree_hashes(game / "terrain-diffusion-models"),
        "dataPacks": {name: tree_hashes(game / name) for name in
                      ("datapacks", "defaultconfigs", "globalresources", "moonlight-global-datapacks")},
    }


def observer_hashes(probe: Path) -> dict[str, str]:
    """Hash observer classes/resources; relocated production payload is optional."""
    with zipfile.ZipFile(probe) as archive:
        result = {}
        for info in archive.infolist():
            name = info.filename
            if (info.is_dir() or name.startswith("com/terrain/instanceclientprobe/production/")
                    or name == "td-instance-tree-ground-fix.mixins.json"):
                continue
            if name.endswith(".class") or name.endswith(".json") or name.endswith(".toml"):
                result[name] = hashlib.sha256(archive.read(info)).hexdigest()
    if not result:
        raise ValueError("probe contains no observer classes/resources")
    return result


def jar_has_class(jar: Path, class_name: str) -> bool:
    with zipfile.ZipFile(jar) as archive:
        return class_name.replace(".", "/") + ".class" in archive.namelist()


def installed_td_jar(game: Path) -> Path:
    found = sorted((game / "mods").glob("NeoForge-terrain-diffusion-mc-*.jar"))
    if len(found) != 1:
        raise ValueError(f"expected exactly one installed Terrain Diffusion jar, found {found}")
    return found[0]


def level_seed(level_dat: Path) -> int:
    """Use the repository's complete NBT reader for modern Data.WorldGenSettings.seed."""
    source = ROOT / "tools" / "river_planner" / "prepare-playtest.py"
    spec = importlib.util.spec_from_file_location("td_level_nbt", source)
    if spec is None or spec.loader is None: raise RuntimeError(f"cannot load NBT reader: {source}")
    reader = importlib.util.module_from_spec(spec); spec.loader.exec_module(reader)
    import gzip
    value = reader.nbt_fields(gzip.decompress(level_dat.read_bytes())).get(("Data", "WorldGenSettings", "seed"), (None, None, None, None))[3]
    if type(value) is not int: raise ValueError("level.dat has no integer Data.WorldGenSettings.seed")
    return value


def world_snapshot(world: Path) -> dict:
    """Hash all persisted routing/terrain inputs and existing chunk state before launch."""
    return {"levelDat": sha256(world / "level.dat"), "data": tree_hashes(world / "data"),
            "datapacks": tree_hashes(world / "datapacks"), "serverconfig": tree_hashes(world / "serverconfig"),
            "streams": tree_hashes(world / "streamsreflowing"),
            "regions": {name: tree_hashes(world / name) for name in ("region", "entities", "poi", "dimensions", "DIM-1", "DIM1")}}


def validate_world(game: Path, world_name: str) -> Path:
    if not WORLD_RE.fullmatch(world_name):
        raise ValueError("--world must be a safe, explicitly prepared TD-River-* copy name")
    world = (game / "saves" / world_name).resolve()
    saves = (game / "saves").resolve()
    if world.parent != saves or not (world / "level.dat").is_file():
        raise ValueError("--world is not an existing copied save")
    return world


def read_fresh_receipt(path: Path, started_ns: int) -> dict:
    if not path.is_file() or path.stat().st_mtime_ns < started_ns:
        raise RuntimeError("fresh instance probe receipt was not produced")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError("instance probe receipt is invalid") from exc
    if not isinstance(value, dict) or value.get("event") != "td_instance_client_river_material_audit" or value.get("status") != "success_observation":
        raise RuntimeError("instance probe did not report a successful observation")
    return value


def observation_valid(record: dict) -> bool:
    if not isinstance(record, dict): return False
    receipt = record.get("receipt")
    if record.get("exitCode") != 0 or not isinstance(receipt, dict): return False
    if receipt.get("event") != "td_instance_client_river_material_audit" or receipt.get("status") != "success_observation": return False
    return receipt.get("settled") is True and type(receipt.get("actualTicks")) is int and receipt["actualTicks"] >= 100


def load_baseline(path: Path) -> dict:
    source = path.resolve() / "run.json"
    try: value = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc: raise ValueError("--baseline has no valid run.json") from exc
    if not observation_valid(value): raise ValueError("--baseline is not a successful settled observation")
    if any(key not in value for key in ("seed", "snapshot", "worldSnapshot", "observer", "runtime")):
        raise ValueError("--baseline lacks reproducibility hashes")
    return value


def regression_verdict(baseline: dict, fixed: dict) -> tuple[str, str]:
    """Return a deliberately separate observation or verified-regression status."""
    if not observation_valid(baseline) or not observation_valid(fixed):
        return "success_observation", "unreproducible_or_unsettled"
    if (baseline["snapshot"] != fixed["snapshot"] or baseline["worldSnapshot"] != fixed["worldSnapshot"]
            or baseline["observer"] != fixed["observer"]
            or not baseline.get("runtime", {}).get("runtimeJarSha256")
            or baseline["runtime"]["runtimeJarSha256"] != fixed.get("runtime", {}).get("runtimeJarSha256")):
        return "success_observation", "inputs_or_observer_mismatch"
    if baseline["seed"] != fixed["seed"]:
        return "success_observation", "world_seed_mismatch"
    before, after = baseline["receipt"].get("observation", {}), fixed["receipt"].get("observation", {})
    if (any(type(value.get("rectangle")) is not str for value in (before, after))
            or any(type(value.get(key)) is not int for value in (before, after)
                   for key in ("waterBottoms", "submergedGrassBeds"))):
        return "success_observation", "missing_physical_observation"
    if before["rectangle"] != after["rectangle"] or before["waterBottoms"] <= 0 or before["submergedGrassBeds"] <= 0:
        return "success_observation", "baseline_did_not_exercise_defect"
    if (after["waterBottoms"] != before["waterBottoms"] or after["waterBottoms"] <= 0
            or after["submergedGrassBeds"] != 0 or type(fixed["receipt"].get("treeGroundSkippedWrites")) is not int
            or fixed["receipt"]["treeGroundSkippedWrites"] <= 0):
        return "success_observation", "fixed_observation_did_not_verify"
    return "verified_regression", "baseline_defect_reproduced_and_fixed"


def run(args: argparse.Namespace) -> dict:
    baseline = load_baseline(args.baseline) if args.baseline else None
    launch = load_launch()
    game = Path(launch.DEFAULT_INSTANCE).resolve()
    world = validate_world(game, args.world)
    probe = Path(args.probe).resolve()
    if not probe.is_file(): raise ValueError(f"missing --probe: {probe}")
    output = Path(args.output).resolve()
    if output.exists(): raise ValueError(f"--output already exists: {output}")
    if ROOT.resolve() not in output.parents: raise ValueError("--output must be a fresh workspace folder")
    runtime_jar = Path(launch.DEFAULT_LIBRARIES) / f"net/neoforged/neoforge/{launch.NEO}/neoforge-{launch.NEO}-client.jar"
    td_jar = installed_td_jar(game)
    # This intentionally happens before any instance write or process launch.
    java_home = Path(launch.DEFAULT_JAVA).parent.parent
    installed_mixin = "com.github.xandergos.terraindiffusionmc.mixin.SubmergedTreeGroundMixin"
    probe_mixin = "com.terrain.instanceclientprobe.production.mixin.SubmergedTreeGroundMixin"
    installed_has_fix = jar_has_class(td_jar, installed_mixin)
    if args.tree_ground_fix:
        if installed_has_fix:
            raise ValueError("--tree-ground-fix would duplicate the installed production mixin")
        runtime = validate_tree_ground_mixin_jar(runtime_jar, java_home, probe, probe_mixin)
    elif installed_has_fix:
        runtime = validate_tree_ground_mixin_jar(runtime_jar, java_home, td_jar, installed_mixin)
    else:
        runtime = validate_tree_ground_runtime(runtime_jar, java_home)
    snapshot, observer, seed, saved_world = instance_snapshot(game), observer_hashes(probe), level_seed(world / "level.dat"), world_snapshot(world)
    output.mkdir(parents=True)
    destination, receipt_path = game / "mods" / TEMP_PROBE, world / RECEIPT
    if destination.exists(): raise RuntimeError(f"temporary probe path is occupied: {destination}")
    started_ns = time.time_ns()
    record = {"world": args.world, "worldPath": str(world), "seed": seed, "snapshot": snapshot,
              "observer": observer, "worldSnapshot": saved_world, "probeSha256": sha256(probe), "runtime": runtime, "startedNs": started_ns}
    originals = {path: path.read_bytes() for path in (game / "options.txt", game / "config" / "iris.properties") if path.is_file()}
    try:
        shutil.copy2(probe, destination)
        if sha256(destination) != record["probeSha256"]: raise RuntimeError("temporary probe hash mismatch")
        natives = output / "natives"; _, native_jars, _ = launch.production_libraries(launch.DEFAULT_LIBRARIES)
        launch.extract_natives(native_jars, natives)
        runtime_args = argparse.Namespace(libraries=launch.DEFAULT_LIBRARIES, assets=launch.DEFAULT_ASSETS,
            java=launch.DEFAULT_JAVA, heap_min_mib=2048, heap_max_mib=10000, collector="g1", width=1280, height=720,
            timeout=1800, property=["td.instanceProbe.enable=true", f"td.instanceProbe.world={args.world}"])
        command = launch.make_command(runtime_args, game, natives)
        if args.tree_ground_fix:
            command += ["--mixin", "td-instance-tree-ground-fix.mixins.json"]
        with (output / "client-output.txt").open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, cwd=game, stdout=log, stderr=subprocess.STDOUT,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            record["pid"] = process.pid; record["exitCode"] = process.wait()
        if record["exitCode"] != 0:
            raise RuntimeError(f"instance client exited {record['exitCode']}")
        record["receipt"] = read_fresh_receipt(receipt_path, started_ns)
        if (record["receipt"].get("world") != args.world
                or Path(record["receipt"].get("worldRoot", "")).resolve() != world):
            raise RuntimeError("receipt belongs to a different copied world")
        shutil.copy2(receipt_path, output / RECEIPT)
    finally:
        if destination.exists() and sha256(destination) == record["probeSha256"]:
            destination.unlink()
        for path, content in originals.items():
            if not path.is_file() or path.read_bytes() != content: path.write_bytes(content)
    record["finishedNs"] = time.time_ns()
    (output / "run.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    if baseline:
        baseline_path = Path(args.baseline).resolve() / "run.json"
        status, reason = regression_verdict(baseline, record)
        result = {"status": status, "reason": reason, "baseline": str(baseline_path), "fixed": str(output / "run.json")}
    else:
        result = {"status": "success_observation", "reason": "observation_only", "fixed": str(output / "run.json")}
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", required=True)
    parser.add_argument("--probe", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--baseline", type=Path, help="prior observation output; comparison remains fail-closed")
    parser.add_argument("--tree-ground-fix", action="store_true", help="use the separately built relocated fix mixin")
    try:
        print(json.dumps(run(parser.parse_args(argv)), indent=2)); return 0
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as exc:
        print(f"instance client probe: {exc}", file=sys.stderr); return 2


if __name__ == "__main__": raise SystemExit(main())
