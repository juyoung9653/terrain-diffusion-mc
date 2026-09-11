"""Stage a separate Prism instance from a validated native Streams + TD probe."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import runpy
import shutil
import zipfile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-instance", type=Path, required=True)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--spawn", type=int, nargs=3, required=True, metavar=("X", "Y", "Z"))
    args = parser.parse_args()
    source, probe, jar = (p.resolve(strict=True) for p in (args.source_instance, args.probe, args.jar))
    target = args.output.resolve()
    if target.exists():
        raise FileExistsError(target)
    manifest = json.loads((probe / "input-manifest.json").read_text())
    if manifest["waterwaysDisabled"]:
        raise ValueError("Native Streams must be enabled in the validated probe")
    receipts = [json.loads(line) for line in (probe / "world/td-streams-probe.jsonl").read_text().splitlines()]
    for event in ("complete", "player_prefetch_audit", "streams_topology_audit", "streams_tick_audit"):
        expected = "success" if event == "complete" else "passed"
        if not any(r.get("event") == event and r.get("status") == expected
                   and (event != "player_prefetch_audit" or r.get("mode") == "enabled") for r in receipts):
            raise ValueError(f"Missing successful {event} receipt")
    config = (probe / "config/streamsreflowing-common.toml").read_text(encoding="utf-8")
    for key in ("generateStreams", "generateLakes", "vanillaWaterFlow", "flowVanillaRivers"):
        if not re.search(rf"(?m)^\s*{key}\s*=\s*true\s*$", config):
            raise ValueError(f"Native Streams requires {key}=true")
    td_config = (probe / "config/terrain-diffusion-mc.properties").read_text(encoding="utf-8")
    if re.search(r"(?m)^\s*streams\.lazy_prefetch\s*=\s*false\s*$", td_config):
        raise ValueError("Native playtest requires streams.lazy_prefetch=true")
    nbt = runpy.run_path(str(Path(__file__).resolve().parents[1] / "river_planner/prepare-playtest.py"))
    fields = nbt["nbt_fields"](gzip.decompress((probe / "world/level.dat").read_bytes()))
    if ("Data", "Player") in fields:
        raise ValueError("Use a dedicated probe without player data")
    preview = probe / "world/data/terrain_diffusion_river_preview.dat"
    if preview.exists():
        state = nbt["nbt_fields"](gzip.decompress(preview.read_bytes()))
        if state.get(("data", "enabled"), (None, None, None, 0))[3]:
            raise ValueError("Standalone river preview must be disabled")
    if any((probe / "world").glob("td-river-preview-cache-*.bin.gz")):
        raise ValueError("Standalone preview cache is not a native Streams world")
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if any("/diagnostic/" in name for name in names) or not any(name.endswith("StreamsPlayerPrefetchMixin.class") for name in names):
            raise ValueError("Expected production JAR with Streams integration, without diagnostics")
    target.mkdir(parents=True)
    shutil.copy2(source / "mmc-pack.json", target / "mmc-pack.json")
    (target / "instance.cfg").write_text(
        "[General]\nConfigVersion=1.2\nInstanceType=OneSix\nname=TD Streams Integrated 1.21.1\niconKey=default\n"
        "OverrideJavaLocation=true\nJavaPath=C:/Program Files/Java/jdk-21/bin/javaw.exe\n"
        "OverrideJavaArgs=true\nJvmArgs=-XX:+UseZGC -XX:+ZGenerational\n"
        "OverrideMemory=true\nMinMemAlloc=2048\nMaxMemAlloc=12288\n", encoding="utf-8")
    game = target / "minecraft"
    for folder in ("mods", "config", "terrain-diffusion-models"):
        shutil.copytree(probe / folder, game / folder)
    if not re.search(r"(?m)^\s*streams\.lazy_prefetch\s*=", td_config):
        (game / "config/terrain-diffusion-mc.properties").write_text(
            td_config.rstrip() + "\n\nstreams.lazy_prefetch=true\n", encoding="utf-8")
    shutil.copy2(jar, game / "mods" / jar.name)
    world = game / "saves/TD Streams Rivers"
    shutil.copytree(probe / "world", world, ignore=shutil.ignore_patterns("session.lock", "level.dat_old"))
    nbt["patch_nbt"](world / "level.dat", {
        ("Data", "LevelName"): "TD Streams Rivers", ("Data", "GameType"): 1,
        ("Data", "allowCommands"): 1, ("Data", "SpawnX"): args.spawn[0],
        ("Data", "SpawnY"): args.spawn[1], ("Data", "SpawnZ"): args.spawn[2],
        ("Data", "GameRules", "spawnRadius"): "0", ("Data", "GameRules", "spawnChunkRadius"): "0",
    })
    (game / "options.txt").write_text("lang:ko_kr\nrenderDistance:8\nsimulationDistance:5\n", encoding="utf-8")
    receipt = {"jarSha256": digest(jar), "seed": manifest["seed"], "spawn": args.spawn,
               "world": "TD Streams Rivers", "nativeStreams": True, "standalonePreview": False}
    (target / "playtest-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt))


if __name__ == "__main__":
    main()
