"""Measure a copied current Prism TD world without changing its live instance.

``snapshot`` makes one immutable copy. ``run`` copies that template again and
only changes explicitly requested test controls. The default freezes the
integrated server; --live-world leaves simulation running. Both modes keep a
fixed position and can sweep the camera, so neither measures travel or new
chunk generation.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path
import runpy
import re
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/optimization_bench"))
import launch
import run as legacy

FLAGS = getattr(subprocess, "CREATE_NO_WINDOW", 0)
MAX_CLIENT_CACHE_BYTES = 512 * 1024 * 1024


def save(path: Path, value) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=True), encoding="utf-8")


def sha(path: Path) -> str:
    return launch.sha256(path)


def java_active() -> bool:
    import psutil
    return any((p.info["name"] or "").lower() in ("java.exe", "javaw.exe")
               for p in psutil.process_iter(["name"]))


def properties(path: Path) -> dict[str, str]:
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines()
                if "=" in line and not line.startswith("#"))


def selected_packs(options: Path) -> set[str]:
    values = dict(line.split(":", 1) for line in options.read_text(encoding="utf-8").splitlines()
                  if ":" in line)
    return {entry[5:] for entry in json.loads(values.get("resourcePacks", "[]"))
            if entry.startswith("file/")}


def saved_player_camera(level_dat: Path) -> dict[str, float]:
    """Read only the existing player's camera fields; do not rewrite level.dat."""
    nbt_fields = runpy.run_path(str(ROOT / "tools/river_planner/prepare-playtest.py"))["nbt_fields"]
    fields = nbt_fields(gzip.decompress(level_dat.read_bytes()))
    keys = {"x": ("Data", "Player", "Pos", "0"), "y": ("Data", "Player", "Pos", "1"),
            "z": ("Data", "Player", "Pos", "2"), "yaw": ("Data", "Player", "Rotation", "0"),
            "pitch": ("Data", "Player", "Rotation", "1")}
    if not all(key in fields for key in keys.values()):
        raise RuntimeError("saved player camera absent; supply --x --y --z --yaw --pitch")
    return {name: float(fields[key][3]) for name, key in keys.items()}


def copy_selected(source: Path, target: Path, folder: str, wanted: set[str] | None = None) -> list[str]:
    origin = source / folder
    if not origin.exists():
        return []
    destination = target / folder
    destination.mkdir(parents=True, exist_ok=True)
    copied = []
    for item in origin.iterdir():
        if wanted is not None and item.name not in wanted:
            continue
        dst = destination / item.name
        (shutil.copytree if item.is_dir() else shutil.copy2)(item, dst)
        copied.append(f"{folder}/{item.name}")
    return copied


def directory_size(path: Path) -> int:
    return sum(item.stat().st_size for item in path.rglob("*") if item.is_file())


def copy_cache_if_small(source: Path, target: Path, folder: str) -> dict[str, int | bool]:
    origin = source / folder
    if not origin.is_dir():
        return {"present": False, "copied": False, "bytes": 0}
    size = directory_size(origin)
    if size > MAX_CLIENT_CACHE_BYTES:
        return {"present": True, "copied": False, "bytes": size}
    shutil.copytree(origin, target / folder)
    return {"present": True, "copied": True, "bytes": size}


def verify_staged_hashes(target: Path, manifest: dict, allowed_drift: set[str]) -> None:
    drift = []
    for relative, expected in manifest["files_sha256"].items():
        if relative in allowed_drift:
            continue
        candidate = target / relative
        if not candidate.is_file() or sha(candidate) != expected:
            drift.append(relative)
    if drift:
        raise RuntimeError("staged copy drifted from immutable template: " + ", ".join(drift[:8]))


def snapshot(args: argparse.Namespace) -> None:
    import psutil
    source, work = args.source.resolve(), args.work.resolve()
    template = work / "template"
    if template.exists():
        raise RuntimeError(f"immutable template already exists: {template}")
    if java_active():
        raise RuntimeError("close every Java process before snapshotting")
    world = source / "saves" / args.world
    if not (world / "level.dat").is_file():
        raise RuntimeError(f"saved world is absent: {world}")
    print(f"Snapshot starting: {args.world} -> {template}", flush=True)
    work.mkdir(parents=True, exist_ok=True)
    template.mkdir()
    shutil.copy2(source / "options.txt", template / "options.txt")
    shutil.copy2(source.parent / "instance.cfg", template / "source-instance.cfg")
    # All active JARs are copied. Disabled artifacts and accounts are deliberately omitted.
    active_mods = {p.name for p in (source / "mods").glob("*.jar")}
    copy_selected(source, template, "mods", active_mods)
    sidecars = {f"{name}.onnx-receipt.properties" for name in active_mods
                if (source / "mods" / f"{name}.onnx-receipt.properties").is_file()}
    copy_selected(source, template, "mods", sidecars)
    for folder in ("config", "defaultconfigs", "terrain-diffusion-models", "purescale_natives",
                   "globalresources", "datapacks"):
        if (source / folder).is_dir():
            shutil.copytree(source / folder, template / folder)
    packs = selected_packs(source / "options.txt")
    missing = sorted(name for name in packs if not (source / "resourcepacks" / name).exists())
    copy_selected(source, template, "resourcepacks", packs)
    iris = properties(source / "config" / "iris.properties")
    shader = iris.get("shaderPack")
    if not shader or not (source / "shaderpacks" / shader).is_file():
        raise RuntimeError("selected Iris shader pack is missing")
    copy_selected(source, template, "shaderpacks", {shader, f"{shader}.txt"})
    shutil.copytree(world, template / "saves" / "fps-scene", ignore=shutil.ignore_patterns("session.lock"))
    caches = {folder: copy_cache_if_small(source, template, folder) for folder in
              ("xaero", "moonlight-global-datapacks", "dynamic-data-pack-cache", "dynamic-resource-pack-cache")}
    camera = saved_player_camera(world / "level.dat")
    files = {str(p.relative_to(template)).replace("\\", "/"): sha(p)
             for p in template.rglob("*") if p.is_file()}
    save(template / "manifest.json", {
        "complete": True, "source": str(source), "world": args.world,
        "selected_shader": shader, "selected_file_resource_packs": sorted(packs), "saved_player_camera": camera,
        "already_missing_selected_packs": missing, "active_mod_sidecars": sorted(sidecars),
        "client_caches": caches,
        "source_world_level_dat_sha256": sha(world / "level.dat"),
        "files_sha256": files,
        "note": "Live instance was only read; this template contains no accounts or logs."
    })
    print(f"Snapshot complete: {template}")


def set_option(path: Path, key: str, value: str) -> None:
    lines = path.read_text(encoding="utf-8").splitlines()
    found = False
    result = []
    for line in lines:
        if line.partition(":")[0] == key:
            result.append(f"{key}:{value}"); found = True
        else: result.append(line)
    if not found: raise RuntimeError(f"missing option {key}")
    path.write_text("\n".join(result) + "\n", encoding="utf-8")


def enable_block_state_culling(path: Path) -> None:
    lines = path.read_text(encoding="utf-8").splitlines()
    matches = [index for index, line in enumerate(lines)
               if re.fullmatch(r"\s*useBlockStateCulling\s*=\s*false\s*", line)]
    if len(matches) != 1:
        raise RuntimeError("expected exactly one disabled MoreCulling block-state setting")
    index = matches[0]
    lines[index] = re.sub(r"false\s*$", "true", lines[index])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def enable_fast_leaves(path: Path) -> dict[str, str]:
    """Apply the requested Sodium quality change only from the known FANCY baseline."""
    before = json.loads(path.read_text(encoding="utf-8"))
    quality = before.get("quality")
    if not isinstance(quality, dict) or quality.get("leaves_quality") != "FANCY":
        raise RuntimeError("expected Sodium quality.leaves_quality to be exactly FANCY")
    expected = json.loads(json.dumps(before))
    expected["quality"]["leaves_quality"] = "FAST"
    save(path, expected)
    if json.loads(path.read_text(encoding="utf-8")) != expected:
        raise RuntimeError("Sodium fast-leaves write changed fields beyond quality.leaves_quality")
    return {"path": "config/sodium-options.json", "from": "FANCY", "to": "FAST"}


def stage_extra_mods(specifications: list[Path], target: Path) -> list[dict[str, str]]:
    staged = []
    for specification in specifications:
        source = specification.resolve()
        if not source.is_file() or not (source.name.endswith(".jar") or source.name.endswith(".jar.disabled")):
            raise RuntimeError("--extra-mod must name an existing .jar or .jar.disabled file")
        destination_name = source.name[:-len(".disabled")] if source.name.endswith(".jar.disabled") else source.name
        destination = target / "mods" / destination_name
        if destination.exists() or any(item["destination_name"] == destination_name for item in staged):
            raise RuntimeError(f"extra mod filename collides with trial content: {destination_name}")
        try:
            with zipfile.ZipFile(source) as archive:
                if archive.testzip() is not None:
                    raise RuntimeError(f"extra mod contains a corrupt ZIP member: {source}")
        except zipfile.BadZipFile as error:
            raise RuntimeError(f"extra mod is not a valid JAR/ZIP: {source}") from error
        digest = sha(source)
        shutil.copyfile(source, destination)
        if sha(destination) != digest:
            raise RuntimeError(f"extra mod copy hash mismatch: {source.name}")
        staged.append({"source_path": str(source), "source_name": source.name,
                       "destination_name": destination_name, "sha256": digest})
    return staged


def stage_shader_override(specification: Path, target: Path, manifest: dict, backups: Path) -> dict[str, str]:
    """Replace only the copied selected Iris ZIP; Iris configuration stays untouched."""
    source = specification.resolve()
    if not source.is_file() or source.suffix.lower() != ".zip":
        raise RuntimeError("--shader-override must name an existing .zip")
    try:
        with zipfile.ZipFile(source) as archive:
            if archive.testzip() is not None:
                raise RuntimeError("shader override contains a corrupt ZIP member")
            if not any(name.startswith("shaders/") and not name.endswith("/") for name in archive.namelist()):
                raise RuntimeError("shader override ZIP has no top-level shaders/ content")
    except zipfile.BadZipFile as error:
        raise RuntimeError("shader override is not a valid ZIP") from error
    selected = manifest.get("selected_shader")
    if not isinstance(selected, str) or Path(selected).name != selected or not selected.endswith(".zip"):
        raise RuntimeError("immutable template has no safe selected Iris ZIP name")
    iris = properties(target / "config" / "iris.properties")
    if iris.get("shaderPack") != selected:
        raise RuntimeError("cloned Iris selection does not match immutable template")
    relative = f"shaderpacks/{selected}"
    destination = target / relative
    original = manifest["files_sha256"].get(relative)
    if not original or not destination.is_file() or sha(destination) != original:
        raise RuntimeError("cloned selected shader differs from immutable template")
    backup = backups / "shaderpacks" / selected
    backup.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(destination, backup)
    if sha(backup) != original:
        raise RuntimeError("selected shader backup hash mismatch")
    replacement = sha(source)
    shutil.copy2(source, destination)
    if sha(destination) != replacement:
        raise RuntimeError("shader override copy hash mismatch")
    return {"source_path": str(source), "source_name": source.name, "sha256": replacement,
            "original_sha256": original, "destination": relative, "backup": str(backup.relative_to(target)).replace("\\", "/")}


def run_trial(args: argparse.Namespace) -> None:
    import psutil
    work, template = args.work.resolve(), args.work.resolve() / "template"
    target = (args.staging_root.resolve() if args.staging_root else work / "runs") / launch.safe_name(args.name)
    if java_active(): raise RuntimeError("a Java process is active; inspect it before starting")
    if target.exists(): raise RuntimeError(f"trial already exists: {target}")
    manifest = json.loads((template / "manifest.json").read_text(encoding="utf-8"))
    if not manifest.get("complete"): raise RuntimeError("incomplete template")
    cfg = properties(template / "source-instance.cfg")
    supplied = {key: getattr(args, key) for key in ("x", "y", "z", "yaw", "pitch")}
    if any(value is not None for value in supplied.values()):
        if any(value is None for value in supplied.values()):
            raise RuntimeError("supply all five camera arguments or none")
        camera = supplied
    else:
        camera = manifest.get("saved_player_camera")
        if not camera: raise RuntimeError("snapshot has no player camera; supply all five camera arguments")
    for key, value in camera.items(): setattr(args, key, value)
    if not all(math.isfinite(value) for value in [*camera.values(), args.yaw_sweep]):
        raise RuntimeError("camera and yaw sweep values must be finite")
    if cfg.get("JvmArgs") != "-XX:+UseZGC -XX:+ZGenerational":
        raise RuntimeError("unrecognized source JVM arguments; refusing replacement")
    source_options = dict(line.split(":", 1) for line in (template / "options.txt").read_text(encoding="utf-8").splitlines() if ":" in line)
    cap = args.cap if args.cap is not None else int(source_options["maxFps"])
    if not 10 <= cap <= 260: raise RuntimeError("--cap must be between 10 and 260")
    if args.seconds <= 0 or args.warmup <= 0: raise RuntimeError("--seconds and --warmup must be positive")
    shutil.copytree(template, target)
    # Every subsequent test control must start from the immutable copied input.
    verify_staged_hashes(target, manifest, set())
    backups = target / "settings-before"; backups.mkdir()
    shutil.copy2(target / "options.txt", backups / "options.txt")
    declared = {"cap": cap, "spark_off": args.spark_off, "collector": args.collector,
                "live_world": args.live_world, "yaw_sweep_degrees": args.yaw_sweep}
    if args.render_stages: declared["render_stages"] = True
    if args.pipeline_state: declared["pipeline_state"] = True
    if args.prepare_only: declared["external_profiler_preparation"] = True
    if args.cap is not None: set_option(target / "options.txt", "maxFps", str(cap))
    if args.spark_off:
        spark_path = target / "config" / "spark" / "config.json"
        if not spark_path.is_file(): raise RuntimeError("Spark config is absent")
        shutil.copy2(spark_path, backups / "spark-config.json")
        spark = json.loads(spark_path.read_text(encoding="utf-8"))
        if spark.get("backgroundProfiler") is not True: raise RuntimeError("unexpected Spark backgroundProfiler")
        spark["backgroundProfiler"] = False; save(spark_path, spark)
    if args.block_culling:
        culling_path = target / "config" / "moreculling.toml"
        if not culling_path.is_file(): raise RuntimeError("MoreCulling config is absent")
        shutil.copy2(culling_path, backups / "moreculling.toml")
        enable_block_state_culling(culling_path)
        declared["block_culling"] = True
    if args.fast_leaves:
        sodium_path = target / "config" / "sodium-options.json"
        if not sodium_path.is_file(): raise RuntimeError("Sodium config is absent")
        shutil.copy2(sodium_path, backups / "sodium-options.json")
        declared["fast_leaves"] = enable_fast_leaves(sodium_path)
    if args.shader_override is not None:
        declared["shader_override"] = stage_shader_override(args.shader_override, target, manifest, backups)
    jar = legacy.build()
    if (target / "mods" / jar.name).exists(): raise RuntimeError("probe filename collides with an active mod")
    shutil.copy2(jar, target / "mods" / jar.name)
    allowed_drift = set()
    if args.cap is not None: allowed_drift.add("options.txt")
    if args.spark_off: allowed_drift.add("config/spark/config.json")
    if args.block_culling: allowed_drift.add("config/moreculling.toml")
    if args.fast_leaves: allowed_drift.add("config/sodium-options.json")
    if args.shader_override is not None: allowed_drift.add(f"shaderpacks/{manifest['selected_shader']}")
    verify_staged_hashes(target, manifest, allowed_drift)
    extras = stage_extra_mods(args.extra_mod, target)
    if extras: declared["extra_mods"] = extras
    quarantined_voxy = []
    if args.rebuild_voxy_cache:
        cache_root = (target / "saves/fps-scene/voxy").resolve()
        if not cache_root.is_relative_to(target.resolve()):
            raise RuntimeError("Voxy cache must be inside the new trial")
        quarantine = backups / "voxy-cache"
        quarantine.mkdir()
        for entry in cache_root.iterdir():
            if entry.name == "config.json":
                continue
            quarantined_voxy.append(str(entry.relative_to(target)).replace("\\", "/"))
            shutil.move(str(entry), str(quarantine / entry.name))
        declared["rebuild_voxy_cache"] = quarantined_voxy
    runtime = argparse.Namespace(width=2560, height=1440, timeout=900,
        libraries=launch.DEFAULT_LIBRARIES, assets=launch.DEFAULT_ASSETS, java=launch.DEFAULT_JAVA,
        collector=args.collector, heap_min_mib=int(cfg["MinMemAlloc"]), heap_max_mib=int(cfg["MaxMemAlloc"]),
        property=["fpsprobe.world=fps-scene", f"fpsprobe.output={target / 'receipt.json'}",
                  f"fpsprobe.vsync={source_options['enableVsync']}", f"fpsprobe.cap={cap}",
                  f"fpsprobe.warmup={args.warmup}", f"fpsprobe.seconds={args.seconds}",
                  f"fpsprobe.liveWorld={str(args.live_world).lower()}", f"fpsprobe.yawSweep={args.yaw_sweep}",
                  f"fpsprobe.renderStages={str(args.render_stages).lower()}",
                  f"fpsprobe.pipelineState={str(args.pipeline_state).lower()}",
                  *[f"fpsprobe.{key}={getattr(args, key)}" for key in ("x", "y", "z", "yaw", "pitch")],
                  "fpsprobe.entities=true"])
    _, native_jars, _ = launch.production_libraries(runtime.libraries)
    launch.extract_natives(native_jars, target / "natives")
    command = launch.make_command(runtime, target, target / "natives")
    inputs = {key: sha(target / key) if (target / key).is_file() else None for key in manifest["files_sha256"]}
    save(target / "input-receipt.json", {"baseline_manifest_sha256": sha(template / "manifest.json"),
        "files_sha256": inputs, "probe_sha256": sha(jar), "max_heap_mib": runtime.heap_max_mib,
        "min_heap_mib": runtime.heap_min_mib, "vsync": source_options["enableVsync"] == "true",
        "cap": cap, "gecko": "unchanged", "pom_disabled": False, "entities_hidden": False,
        "spark_background_disabled": args.spark_off, "jfr": False, "camera": {k: getattr(args, k) for k in ("x", "y", "z", "yaw", "pitch")},
        "warmup_seconds": args.warmup, "live_world": args.live_world, "yaw_sweep_degrees": args.yaw_sweep,
        "block_culling": args.block_culling, "fast_leaves": args.fast_leaves,
        "shader_override": declared.get("shader_override"), "extra_mods": extras,
        "declared_controls": declared, "started_epoch": time.time()})
    (target / "logs").mkdir(exist_ok=True); legacy.record_driver(target, "before")
    if args.prepare_only:
        save(target / "prepared-launch.json", {"command": command, "cwd": str(target)})
        print(f"Prepared isolated profiler launch: {target}", flush=True)
        return
    if java_active(): raise RuntimeError("a Java process began during preparation; refusing launch")
    with (target / "logs" / "launcher.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=target, stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
    save(target / "process.json", {"pid": process.pid})
    print(f"Trial launched PID {process.pid}: {target}", flush=True)
    gpu_file = (target / "gpu.csv").open("w", encoding="utf-8")
    gpu = subprocess.Popen(["nvidia-smi", "--query-gpu=timestamp,utilization.gpu,memory.used,power.draw,clocks.gr,temperature.gpu", "--format=csv,noheader,nounits", "--loop=1"], stdout=gpu_file, stderr=subprocess.DEVNULL, creationflags=FLAGS)
    started, ready, focused, external = time.monotonic(), False, False, set()
    resources = (target / "resources.csv").open("w", encoding="utf-8"); resources.write("elapsed_s,sample_phase,rss_bytes,available_ram_bytes,system_cpu_percent,process_cpu_seconds\n")
    watched = psutil.Process(process.pid); psutil.cpu_percent()
    try:
        while process.poll() is None and time.monotonic() - started < 900:
            if not focused and time.monotonic() - started > 20: legacy.focus(process.pid); focused = True
            latest = target / "logs" / "latest.log"
            ready = ready or (latest.exists() and "FPS_PROBE_SAMPLE_START" in latest.read_text(encoding="utf-8", errors="replace"))
            if ready and not (target / "receipt.json").exists(): external.update(p.pid for p in psutil.process_iter(["name"]) if p.pid != process.pid and (p.info["name"] or "").lower() in ("java.exe", "javaw.exe"))
            try:
                resources.write(f"{time.monotonic()-started:.3f},{int(ready and not (target/'receipt.json').exists())},{watched.memory_info().rss},{psutil.virtual_memory().available},{psutil.cpu_percent()},{sum(watched.cpu_times()[:2])}\n"); resources.flush()
            except psutil.Error: pass
            time.sleep(1)
    finally:
        resources.close()
        if gpu.poll() is None: gpu.terminate(); gpu.wait(timeout=10)
        gpu_file.close()
    if process.poll() is None:
        save(target / "launch-result.json", {"timeout": True, "pid": process.pid, "note": "task-owned process retained for inspection"})
        raise RuntimeError("launch timed out; inspect retained PID before retry")
    save(target / "launch-result.json", {"exit_code": process.returncode, "external_java_during_sample": sorted(external)})
    if process.returncode or not (target / "receipt.json").is_file() or not json.loads((target / "receipt.json").read_text(encoding="utf-8")).get("success"):
        raise RuntimeError("probe failed; inspect trial launcher/latest logs")
    if external: raise RuntimeError("other Java work overlapped sampling")
    legacy.record_driver(target, "after")
    if json.loads((target / "driver-settings-before.json").read_text(encoding="utf-8")) != json.loads((target / "driver-settings-after.json").read_text(encoding="utf-8")):
        raise RuntimeError("driver settings changed during trial")
    print((target / "receipt.json").read_text(encoding="utf-8"))


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    sub = result.add_subparsers(dest="command", required=True)
    snap = sub.add_parser("snapshot"); snap.add_argument("--source", type=Path, default=launch.DEFAULT_INSTANCE); snap.add_argument("--world", required=True); snap.add_argument("--work", type=Path, required=True); snap.set_defaults(func=snapshot)
    run = sub.add_parser("run"); run.add_argument("name"); run.add_argument("--work", type=Path, required=True); run.add_argument("--cap", type=int); run.add_argument("--spark-off", action="store_true"); run.add_argument("--collector", choices=("zgc", "g1"), default="zgc"); run.add_argument("--seconds", type=int, default=90); run.add_argument("--warmup", type=int, default=60); run.add_argument("--rebuild-voxy-cache", action="store_true"); run.add_argument("--live-world", action="store_true"); run.add_argument("--yaw-sweep", type=float, default=0); run.add_argument("--block-culling", action="store_true"); run.add_argument("--fast-leaves", action="store_true"); run.add_argument("--extra-mod", type=Path, action="append", default=[])
    for key in ("x", "y", "z", "yaw", "pitch"): run.add_argument(f"--{key}", type=float)
    run.add_argument("--render-stages", action="store_true", help="Diagnostic render-stage timings; not an uninstrumented FPS result")
    run.add_argument("--pipeline-state", action="store_true", help="Read effective SR/FG state once per second without stage timing queries")
    run.add_argument("--shader-override", type=Path, help="Validated shader ZIP copied over the selected shader only in this disposable trial")
    run.add_argument("--prepare-only", action="store_true", help="Prepare a fresh clone and launch command for an external diagnostic profiler; do not start Java")
    run.add_argument("--staging-root", type=Path, help="Optional parent of fresh trial copies, for profilers requiring an ASCII physical path")
    run.set_defaults(func=run_trial); return result


if __name__ == "__main__":
    args = parser().parse_args()
    try: args.func(args)
    except RuntimeError as error: print(f"error: {error}", file=sys.stderr); raise SystemExit(2)
