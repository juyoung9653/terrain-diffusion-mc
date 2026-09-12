#!/usr/bin/env python3
"""Create fresh, unlaunched ecology showcase worlds from a dedicated TD template.

The template is read only.  Each staged world has only level.dat, data/, and
datapacks/; generated chunks, players, entities, POI, and Streams state are
intentionally excluded.
"""
import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import struct

ECOLOGY_BIOMES = (
    "temperate_forest", "temperate_woodland", "temperate_grassland",
    "boreal_forest", "boreal_woodland", "cold_grassland",
    "dry_woodland", "dry_grassland", "tropical_forest", "tropical_woodland", "desert",
)
OVERWORLD_BIOME_SOURCE = (
    "Data", "WorldGenSettings", "dimensions", "minecraft:overworld", "generator", "biome_source"
)


def river_nbt_helpers():
    path = Path(__file__).parents[1] / "river_planner" / "prepare-playtest.py"
    spec = importlib.util.spec_from_file_location("td_river_nbt_helpers", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module.nbt_fields


nbt_fields = river_nbt_helpers()


def nbt_string(value):
    encoded = value.encode("utf-8")
    if len(encoded) > 65535:
        raise ValueError("NBT string exceeds unsigned-short limit")
    return struct.pack(">H", len(encoded)) + encoded


def named_string(name, value):
    return bytes((8,)) + nbt_string(name) + nbt_string(value)


def scalar_payload(kind, value):
    if kind == 1:
        return struct.pack(">b", value)
    if kind == 3:
        return struct.pack(">i", value)
    if kind == 4:
        return struct.pack(">q", value)
    if kind == 8:
        return nbt_string(value)
    raise ValueError(f"Unsupported scalar NBT type {kind}")


def patch_level(path, profile, spawn):
    if profile not in ECOLOGY_BIOMES:
        raise ValueError(f"Unknown ecology showcase profile: {profile}")
    raw = gzip.decompress(path.read_bytes())
    before = nbt_fields(raw)
    if ("Data", "Player") in before:
        raise ValueError("Template must be a dedicated probe world without Data/Player")
    source = before.get(OVERWORLD_BIOME_SOURCE)
    if source is None or source[0] != 10:
        raise ValueError("Missing overworld generator biome_source compound")
    required = {
        OVERWORLD_BIOME_SOURCE + ("type",): "terrain-diffusion-mc:terrain_diffusion",
        OVERWORLD_BIOME_SOURCE + ("terralith",): 1,
        OVERWORLD_BIOME_SOURCE + ("natural_biomes",): 1,
    }
    for key, expected in required.items():
        if key not in before or before[key][3] != expected:
            raise ValueError(f"Template lacks required TD biome source flag: {'/'.join(key)}")

    changes = {
        ("Data", "LevelName"): profile,
        ("Data", "GameType"): 1,
        ("Data", "allowCommands"): 1,
        ("Data", "SpawnX"): spawn[0],
        ("Data", "SpawnY"): spawn[1],
        ("Data", "SpawnZ"): spawn[2],
    }
    for name, value in (("spawnRadius", "0"), ("spawnChunkRadius", "0"), ("keepInventory", "true"),
                        ("doDaylightCycle", "false"), ("doWeatherCycle", "false")):
        key = ("Data", "GameRules", name)
        if key in before:
            changes[key] = value
    for name, value in (("DayTime", 6000), ("raining", 0), ("thundering", 0)):
        key = ("Data", name)
        if key in before:
            changes[key] = value

    patches = []
    for key, value in changes.items():
        kind, start, end, _ = before[key]
        patches.append((start, end, scalar_payload(kind, value)))
    showcase_key = OVERWORLD_BIOME_SOURCE + ("ecology_showcase",)
    if showcase_key in before:
        kind, start, end, _ = before[showcase_key]
        if kind != 8:
            raise ValueError("Existing ecology_showcase is not an NBT string")
        patches.append((start, end, nbt_string(profile)))
    else:
        # Compound payload ends with TAG_End. Insert before it without re-encoding other fields.
        patches.append((source[2] - 1, source[2] - 1, named_string("ecology_showcase", profile)))
    for start, end, encoded in sorted(patches, reverse=True):
        raw = raw[:start] + encoded + raw[end:]
    after = nbt_fields(raw)
    assert after[showcase_key][3] == profile
    assert all(after[key][3] == value for key, value in changes.items())
    assert all(after[key][3] == old[3] for key, old in before.items()
               if key not in changes and key not in (OVERWORLD_BIOME_SOURCE, showcase_key))
    path.write_bytes(gzip.compress(raw))
    return after[("Data", "WorldGenSettings", "seed")][3]


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


ALLOWED_DATA_FILES = {"terrain_diffusion_world_settings.dat"}


def ignore_non_showcase_data(_directory, names):
    # A fresh showcase needs only the persisted TD scale, not probe tickets or state.
    return {name for name in names if name not in ALLOWED_DATA_FILES}


def copy_template(template, target):
    shutil.copy2(template / "level.dat", target / "level.dat")
    data = template / "data"
    if data.exists():
        shutil.copytree(data, target / "data", ignore=ignore_non_showcase_data)
    datapacks = template / "datapacks"
    if datapacks.exists():
        shutil.copytree(datapacks, target / "datapacks")


def read_saved_scale(template):
    settings = template / "data" / "terrain_diffusion_world_settings.dat"
    if not settings.is_file():
        raise ValueError("Template lacks saved TD world scale data")
    fields = nbt_fields(gzip.decompress(settings.read_bytes()))
    # The codec omits the default scale field, whose persisted semantic value is still 2.
    scale = fields.get(("data", "scale"), (None, None, None, 2))[3]
    if scale != 2:
        raise ValueError(f"Showcases require template saved TD scale 2, got {scale}")
    return scale, settings.relative_to(template).as_posix()


def stage(template, output, spawn):
    template = template.resolve(strict=True)
    if not (template / "level.dat").is_file():
        raise ValueError("--template-world must contain level.dat")
    if output.exists():
        raise FileExistsError(f"--output must be new: {output}")
    scale, scale_path = read_saved_scale(template)
    output.mkdir(parents=True)
    manifests = []
    for profile in ECOLOGY_BIOMES:
        world = output / profile
        world.mkdir()
        copy_template(template, world)
        seed = patch_level(world / "level.dat", profile, spawn)
        manifest = {
            "profile": profile, "seed": seed, "scale": scale, "savedScalePath": scale_path,
            "world": profile, "levelDat": "level.dat", "dataPath": "data",
            "datapacksPath": "datapacks", "spawn": list(spawn),
            "sourceTemplate": str(template), "levelDatSha256": sha256(world / "level.dat"),
            "stagedOnly": True,
        }
        (world / "showcase-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        manifests.append(manifest)
    (output / "showcases-manifest.json").write_text(json.dumps({"profiles": manifests}, indent=2) + "\n", encoding="utf-8")
    return manifests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--template-world", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--spawn", nargs=3, type=int, default=(9472, 110, 896), metavar=("X", "Y", "Z"))
    args = parser.parse_args()
    manifests = stage(args.template_world, args.output, tuple(args.spawn))
    print(json.dumps({"output": str(args.output), "worlds": [item["world"] for item in manifests]}, indent=2))


if __name__ == "__main__":
    main()
