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
    parser.add_argument("--instance-name", default="TD Streams Integrated 1.21.1")
    parser.add_argument("--world-name", default="TD Streams Rivers")
    parser.add_argument("--client-mod", type=Path, action="append", default=[],
                        help="Additional compatible client jar to copy into the new instance")
    parser.add_argument("--spawn", type=int, nargs=3, required=True, metavar=("X", "Y", "Z"))
    args = parser.parse_args()
    if not args.instance_name.strip() or any(c in args.instance_name for c in "\r\n"):
        parser.error("instance-name must be a nonempty single line")
    if not args.world_name.strip() or not re.fullmatch(r"[A-Za-z0-9 _-]+", args.world_name):
        parser.error("world-name must contain only letters, digits, spaces, underscore or hyphen")
    client_mods = [p.resolve(strict=True) for p in args.client_mod]
    if len({p.name for p in client_mods}) != len(client_mods):
        parser.error("client-mod filenames must be unique")
    for mod in client_mods:
        if not mod.is_file() or mod.suffix != ".jar" or not zipfile.is_zipfile(mod):
            parser.error(f"invalid client jar: {mod.name}")
    source, probe, jar = (p.resolve(strict=True) for p in (args.source_instance, args.probe, args.jar))
    target = args.output.resolve()
    if target.exists():
        raise FileExistsError(target)
    for mod in client_mods:
        existing = probe / "mods" / mod.name
        if (existing.exists() and digest(existing) != digest(mod)) or mod.name == jar.name:
            raise ValueError(f"conflicting client jar: {mod.name}")
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
        f"[General]\nConfigVersion=1.2\nInstanceType=OneSix\nname={args.instance_name}\niconKey=default\n"
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
    for mod in client_mods:
        installed = game / "mods" / mod.name
        shutil.copy2(mod, installed)
    world = game / "saves" / args.world_name
    shutil.copytree(probe / "world", world, ignore=shutil.ignore_patterns("session.lock", "level.dat_old"))
    nbt["patch_nbt"](world / "level.dat", {
        ("Data", "LevelName"): args.world_name, ("Data", "GameType"): 1,
        ("Data", "allowCommands"): 1, ("Data", "SpawnX"): args.spawn[0],
        ("Data", "SpawnY"): args.spawn[1], ("Data", "SpawnZ"): args.spawn[2],
        ("Data", "GameRules", "spawnRadius"): "0", ("Data", "GameRules", "spawnChunkRadius"): "0",
    })
    (game / "options.txt").write_text("lang:ko_kr\nrenderDistance:8\nsimulationDistance:5\n", encoding="utf-8")
    receipt = {"jarSha256": digest(jar), "seed": manifest["seed"], "spawn": args.spawn,
               "world": args.world_name, "nativeStreams": True, "standalonePreview": False,
               "clientMods": [{"file": p.name, "sha256": digest(p)} for p in client_mods]}
    (target / "playtest-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt))


if __name__ == "__main__":
    main()
