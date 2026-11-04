#!/usr/bin/env python3
"""Private full-modpack underground screenshot fixture and client capture.

The source Prism instance is read only.  ``prepare`` delegates the proven
chunk-profile copier into this tool's private build directory; it removes all
source chunks/player data before a candidate world is generated.  ``capture``
uses the existing FPS probe client, which moves a cloned player to a fixed camera
and writes an actual Minecraft PNG plus receipt.  It never uses the player save.
"""
from __future__ import annotations
import argparse, gzip, importlib.util, json, re, shutil, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "build/geology/visual-probe"
SOURCE_RECORD = ROOT / "build/underground-v3-player-source.json"

def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path); mod = importlib.util.module_from_spec(spec)
    assert spec and spec.loader; spec.loader.exec_module(mod); return mod

def safe_name(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", value):
        raise RuntimeError("name must be one safe workspace directory name")
    return value

def patch_capture_spawn(level: Path, x: float, y: float, z: float) -> None:
    benchmark = load("td_visual_capture_nbt", ROOT / "tools/geology/benchmark.py")
    raw = gzip.decompress(level.read_bytes()); fields = benchmark.nbt_fields(raw)
    changes = {("Data", "initialized"): 1, ("Data", "SpawnX"): int(x), ("Data", "SpawnY"): int(y), ("Data", "SpawnZ"): int(z)}
    patches=[]
    for key, value in changes.items():
        kind,start,end,_=fields[key]; patches.append((start,end,benchmark.scalar_payload(kind,value)))
    for start,end,value in sorted(patches, reverse=True): raw=raw[:start]+value+raw[end:]
    verified=benchmark.nbt_fields(raw)
    if any(verified[key][3] != value for key,value in changes.items()): raise RuntimeError("capture spawn patch did not round-trip")
    level.write_bytes(gzip.compress(raw))

def source_record() -> tuple[Path, dict]:
    row = json.loads(SOURCE_RECORD.read_text(encoding="utf-8")); world = Path(row["world"])
    if row.get("savedScale") != 2 or row.get("fields", {}).get("Data/WorldGenSettings/seed") != 6761977799736804665:
        raise RuntimeError("visual source record is not the requested seed/scale-2 world")
    if not (world / "level.dat").is_file(): raise RuntimeError("recorded player world is unavailable")
    return world, row

def prepare(a: argparse.Namespace) -> None:
    world, _ = source_record(); instance = world.parents[1]
    cp = load("td_visual_chunk_profile", ROOT / "tools/chunk_profile/run.py")
    if instance.resolve() != cp.launch.DEFAULT_INSTANCE.resolve():
        raise RuntimeError("recorded instance differs from the validated direct launcher instance")
    cp.WORK = WORK
    name = safe_name(a.name)
    ns = argparse.Namespace(name=name, world=world.name, no_shaders=a.no_shaders, td_jar=str(a.candidate_jar),
        decoder_fp16=None, decoder_precision=None, request_telemetry=False, without_streams=False,
        streams_ground_marine=None, streams_batch_sampling=None, streams_ground_marine_audit=False,
        streams_settle_seconds=0, phase_timeout=900, regions=None, side=8, chunk_x=-67, chunk_z=62,
        settle=45, timeout=1500)
    cp.prepare(ns)
    fixture = WORK / name
    benchmark = load("td_visual_benchmark", ROOT / "tools/geology/benchmark.py")
    level = fixture / "saves/chunk-profile/level.dat"
    benchmark.patch_level(level, scale=2, underground=True, rivers=True, river_version=2)
    fields = benchmark.nbt_fields(__import__("gzip").decompress(level.read_bytes()))
    source = benchmark.BIOME_SOURCE
    if fields[source + ("underground_river_version",)][3] != 2:
        raise RuntimeError("saved level.dat does not retain underground river profile v2")
    manifest = json.loads((fixture / "input-manifest.json").read_text(encoding="utf-8"))
    if manifest["seed"] != 6761977799736804665 or manifest["scale"] != 2:
        raise RuntimeError("prepared clone does not retain requested seed/scale")
    model = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/geology/UndergroundRiverModel.java"
    (fixture / "visual-source.json").write_text(json.dumps({"sourceRecord": str(SOURCE_RECORD), "candidateJar": str(a.candidate_jar.resolve()), "candidateSha256": cp.launch.sha256(a.candidate_jar), "riverModelSource": str(model), "riverModelSourceSha256": cp.launch.sha256(model), "expectedRiverProfile": 2, "seed": manifest["seed"], "scale": manifest["scale"], "freshWorld": "saves/chunk-profile"}, indent=2)+"\n", encoding="utf-8")
    print(fixture)

def capture(a: argparse.Namespace) -> None:
    fixture = (WORK / safe_name(a.fixture)).resolve(); source = fixture / "saves/chunk-profile"
    if not (source / "level.dat").is_file(): raise RuntimeError("prepared fresh world is missing")
    target = WORK / "captures" / safe_name(a.name)
    if target.exists(): raise RuntimeError("capture name already exists")
    copy_bytes = sum(p.stat().st_size for p in fixture.rglob("*") if p.is_file())
    reserve_bytes = 2 * 1024**3  # New chunks, native extraction and runtime caches.
    free_bytes = shutil.disk_usage(WORK).free
    if free_bytes < copy_bytes + reserve_bytes:
        raise RuntimeError(f"Not enough space for private capture: need {copy_bytes + reserve_bytes} bytes, have {free_bytes}")
    fps = load("td_visual_fps", ROOT / "tools/fps_probe/run.py")
    probe = fps.build()  # Existing tested client screenshot mod; compilation is deferred to explicit capture.
    # Runtime-warmed templates can already contain extracted DLLs. The launcher
    # extracts its validated native-library set into a fresh directory below.
    shutil.copytree(fixture, target, ignore=shutil.ignore_patterns("natives"))
    patch_capture_spawn(target / "saves/chunk-profile/level.dat", a.x, a.y, a.z)
    old_world = (target / "saves/chunk-profile").resolve(strict=True)
    new_world = (target / "saves/fps-scene").resolve()
    if not old_world.is_relative_to(WORK.resolve()) or not new_world.is_relative_to(WORK.resolve()):
        raise RuntimeError("capture world move escaped private workspace")
    shutil.move(str(old_world), str(new_world))
    options = target / "options.txt"; text = options.read_text(encoding="utf-8")
    edits={"renderDistance":str(a.render_distance),"simulationDistance":"5","enableVsync":"false","maxFps":"60"}
    if a.hide_hud: edits["hideGui"]="true"
    seen=set(); lines=[]
    for line in text.splitlines():
        key,sep,_=line.partition(":"); lines.append(f"{key}:{edits[key]}" if sep and key in edits else line); seen.add(key)
    lines.extend(f"{key}:{value}" for key,value in edits.items() if key not in seen); options.write_text("\n".join(lines)+"\n",encoding="utf-8")
    shutil.copy2(probe, target / "mods" / probe.name)
    launch = fps.launch
    runtime = argparse.Namespace(width=1920, height=1080, timeout=a.timeout, libraries=launch.DEFAULT_LIBRARIES,
        assets=launch.DEFAULT_ASSETS, java=launch.DEFAULT_JAVA, collector="zgc", heap_min_mib=12000, heap_max_mib=12000,
        property=["fpsprobe.world=fps-scene", f"fpsprobe.output={target/'receipt.json'}", "fpsprobe.vsync=false", "fpsprobe.cap=60", f"fpsprobe.warmup={a.warmup}", f"fpsprobe.seconds={a.seconds}", f"fpsprobe.x={a.x}", f"fpsprobe.y={a.y}", f"fpsprobe.z={a.z}", f"fpsprobe.yaw={a.yaw}", f"fpsprobe.pitch={a.pitch}", "fpsprobe.entities=true", "fpsprobe.spectator=true", f"fpsprobe.nightVision={str(a.night_vision).lower()}"])
    _, natives, _ = launch.production_libraries(runtime.libraries); launch.extract_natives(natives, target / "natives")
    (target / "capture-request.json").write_text(json.dumps({"camera": vars(a), "nightVisionRequested": a.night_vision, "spectatorRequested": True}, indent=2)+"\n", encoding="utf-8")
    result = subprocess.run(launch.make_command(runtime, target, target / "natives"), cwd=target, timeout=a.timeout)
    receipt = target / "receipt.json"; png = target / "screenshots/fps-probe.png"
    if result.returncode or not receipt.is_file() or not png.is_file() or json.loads(receipt.read_text(encoding="utf-8")).get("success") is not True: raise RuntimeError("client did not publish a successful screenshot receipt and PNG")
    print(target / "receipt.json")

def main() -> int:
    p=argparse.ArgumentParser(description=__doc__); sub=p.add_subparsers(dest="cmd", required=True)
    x=sub.add_parser("prepare"); x.add_argument("name"); x.add_argument("--candidate-jar", type=Path, required=True); x.add_argument("--no-shaders", action="store_true")
    x=sub.add_parser("capture"); x.add_argument("fixture"); x.add_argument("name"); x.add_argument("--x",type=float,required=True); x.add_argument("--y",type=float,required=True); x.add_argument("--z",type=float,required=True); x.add_argument("--yaw",type=float,required=True); x.add_argument("--pitch",type=float,required=True); x.add_argument("--render-distance",type=int,choices=range(2,33),default=6); x.add_argument("--warmup",type=int,default=45); x.add_argument("--seconds",type=int,default=10); x.add_argument("--timeout",type=int,default=900); x.add_argument("--hide-hud",action="store_true"); x.add_argument("--night-vision",action="store_true")
    a=p.parse_args(); (prepare if a.cmd=="prepare" else capture)(a); return 0
if __name__ == "__main__": raise SystemExit(main())
