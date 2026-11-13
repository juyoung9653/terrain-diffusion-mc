#!/usr/bin/env python3
"""Make one prepared structure fixture select only T&T's beach village."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
FAMILY = ROOT / "build" / "structure-density"
PACK = "td-controlled-beach"
PACK_ID = f"file/{PACK}"
STRUCTURE = "towns_and_towers:village_beach"
TOWNS = "data/towns_and_towers/worldgen/structure_set/towns.json"
BEACH_TAG = "data/towns_and_towers/tags/worldgen/biome/has_structure/village_beach_lighthouse.json"
CONTROLLED_NATURAL_BIOMES = ("terrain-diffusion-mc:ecology/dry_grassland", "minecraft:warm_ocean")
RIVAL_TAGS = ("data/structory/tags/worldgen/biome/has_structure/chapel.json",
              "data/minecraft/tags/worldgen/biome/has_structure/shipwreck.json")


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def nbt_fields():
    path = ROOT / "tools" / "river_planner" / "prepare-playtest.py"
    spec = importlib.util.spec_from_file_location("controlled_beach_nbt", path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module.nbt_fields


NBT_FIELDS = nbt_fields()
ENABLED = ("Data", "DataPacks", "Enabled")


def encoded_string(value: str) -> bytes:
    data = value.encode("utf-8")
    if len(data) > 65535:
        raise ValueError("datapack id is too long")
    return len(data).to_bytes(2, "big") + data


def named_string(name: str, value: str) -> bytes:
    return bytes((8,)) + encoded_string(name) + encoded_string(value)


def fix_beach_biome(level: Path) -> None:
    """Make the disposable world's biome gate deterministic without changing TD noise."""
    key = ("Data", "WorldGenSettings", "dimensions", "minecraft:overworld",
           "generator", "biome_source")
    raw = gzip.decompress(level.read_bytes())
    before = NBT_FIELDS(raw)
    entry = before.get(key)
    if entry is None or entry[0] != 10:
        raise ValueError("level.dat has no overworld biome-source compound")
    source_type = before.get(key + ("type",))
    if source_type is None or source_type[3] != "terrain-diffusion-mc:terrain_diffusion":
        raise ValueError("fixed-beach fixture must begin with the TD natural biome source")
    payload = named_string("type", "minecraft:fixed") + named_string("biome", "minecraft:beach") + b"\x00"
    raw = raw[:entry[1]] + payload + raw[entry[2]:]
    level.write_bytes(gzip.compress(raw))
    after = NBT_FIELDS(gzip.decompress(level.read_bytes()))
    if (after.get(key + ("type",), (None, None, None, None))[3] != "minecraft:fixed"
            or after.get(key + ("biome",), (None, None, None, None))[3] != "minecraft:beach"):
        raise AssertionError("fixed beach biome source did not round-trip")


def enable_pack(level: Path) -> list[str]:
    raw = gzip.decompress(level.read_bytes())
    before = NBT_FIELDS(raw)
    entry = before.get(ENABLED)
    if entry is None or entry[0] != 9 or raw[entry[1]] != 8:
        raise ValueError("level.dat has no string DataPacks/Enabled list")
    names = [value[3] for key, value in before.items() if key[:-1] == ENABLED]
    if PACK_ID not in names:
        names.append(PACK_ID)
        start, end = entry[1], entry[2]
        encoded = bytes((8,)) + len(names).to_bytes(4, "big") + b"".join(
            encoded_string(name) for name in names)
        raw = raw[:start] + encoded + raw[end:]
        level.write_bytes(gzip.compress(raw))
    after = NBT_FIELDS(gzip.decompress(level.read_bytes()))
    enabled = [value[3] for key, value in after.items() if key[:-1] == ENABLED]
    if PACK_ID not in enabled:
        raise AssertionError("controlled datapack was not enabled in level.dat")
    return enabled


def fixture_path(value: Path) -> Path:
    path = value.resolve()
    if path.parent != FAMILY.resolve() or not path.is_dir() or not (path / "input-manifest.json").is_file():
        raise ValueError("fixture must be a prepared direct build/structure-density child")
    if any((path / "world" / name).exists() for name in ("region", "entities", "poi", "streamsreflowing")):
        raise ValueError("controlled fixture must still have fresh chunks and river cache")
    return path


def original_towns(fixture: Path) -> dict[str, object]:
    jars = list((fixture / "mods").glob("t_and_t-fabric-neoforge-*.jar"))
    if len(jars) != 1:
        raise ValueError("fixture needs exactly one T&T jar")
    with zipfile.ZipFile(jars[0]) as archive:
        document = json.loads(archive.read(TOWNS))
    if not isinstance(document.get("placement"), dict) or not isinstance(document.get("structures"), list):
        raise ValueError("T&T towns structure set has unexpected shape")
    if not any(row.get("structure") == STRUCTURE for row in document["structures"] if isinstance(row, dict)):
        raise ValueError("T&T towns set does not contain village_beach")
    return document


def prepare(fixture_arg: Path, *, fixed_beach_biome: bool = False,
            widen_natural_biomes: bool = False, isolate_rivals: bool = False) -> dict[str, object]:
    if fixed_beach_biome and widen_natural_biomes:
        raise ValueError("choose either fixed or widened natural biome eligibility")
    fixture = fixture_path(fixture_arg)
    config_path = fixture / "config" / "terrain-diffusion-structures.json"
    config = json.loads(config_path.read_text(encoding="utf-8"))
    policy = config.get("structures", {}).get(STRUCTURE)
    if policy != {"surfaceMode": "SURFACE"}:
        raise ValueError("fixture must retain exactly the production beach SURFACE override")
    villages = config.get("villageDistribution")
    if not isinstance(villages, dict) or villages.get("basicSharePercent") != 70:
        raise ValueError("fixture source must retain the production 70% basic-village share")
    towns = original_towns(fixture)
    controlled = {"placement": towns["placement"],
                  "structures": [{"structure": STRUCTURE, "weight": 1}]}
    pack = fixture / "world" / "datapacks" / PACK
    structure_set = pack / TOWNS
    structure_set.parent.mkdir(parents=True, exist_ok=True)
    (pack / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": 48,
        "description": "TEST ONLY: force T&T beach village candidate"}}, indent=2) + "\n", encoding="utf-8")
    structure_set.write_text(json.dumps(controlled, indent=2) + "\n", encoding="utf-8")
    widened_tag = pack / BEACH_TAG
    if widen_natural_biomes:
        widened_tag.parent.mkdir(parents=True, exist_ok=True)
        widened_tag.write_text(json.dumps({"replace": False,
                                            "values": list(CONTROLLED_NATURAL_BIOMES)}, indent=2) + "\n",
                               encoding="utf-8")
    rival_tags: list[Path] = []
    if isolate_rivals:
        for relative in RIVAL_TAGS:
            target = pack / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps({"replace": True, "values": []}, indent=2) + "\n",
                              encoding="utf-8")
            rival_tags.append(target)
    level = fixture / "world" / "level.dat"
    if fixed_beach_biome:
        fix_beach_biome(level)
    enabled = enable_pack(level)
    villages["basicSharePercent"] = 0
    config_path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    manifest_path = fixture / "input-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    hashes = manifest.get("sha256")
    if not isinstance(hashes, dict):
        raise ValueError("prepared fixture has an invalid input manifest")
    manifest_paths = [config_path, fixture / "world" / "level.dat", pack / "pack.mcmeta", structure_set]
    if widen_natural_biomes:
        manifest_paths.append(widened_tag)
    manifest_paths.extend(rival_tags)
    for path in manifest_paths:
        hashes[path.relative_to(fixture).as_posix()] = sha256(path)
    manifest["sha256"] = dict(sorted(hashes.items()))
    manifest["controlledBeach"] = {"structure": STRUCTURE, "basicSharePercent": 0,
                                   "surfaceMode": "SURFACE", "maxCut": 2,
                                   "maxFill": 3, "blend": 12,
                                   "datapack": PACK_ID,
                                   "biomeSource": "minecraft:fixed/minecraft:beach" if fixed_beach_biome
                                   else "terrain-diffusion-mc:terrain_diffusion",
                                   "addedNaturalBiomes": list(CONTROLLED_NATURAL_BIOMES)
                                   if widen_natural_biomes else [],
                                   "disabledRivalBiomeTags": list(RIVAL_TAGS) if isolate_rivals else []}
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    receipt = {"fixture": str(fixture.relative_to(ROOT)), "structure": STRUCTURE,
               "originalPlacement": towns["placement"], "enabledDatapacks": enabled,
               "policy": manifest["controlledBeach"],
               "sha256": {path.relative_to(fixture).as_posix(): sha256(path) for path in manifest_paths}}
    (fixture / "controlled-beach-manifest.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return receipt


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", required=True, type=Path)
    parser.add_argument("--fixed-beach-biome", action="store_true",
                        help="fixture-only deterministic T&T biome gate; TD terrain settings remain active")
    parser.add_argument("--widen-natural-biomes", action="store_true",
                        help="fixture-only: add observed TD dry grassland and warm ocean to T&T beach tag")
    parser.add_argument("--isolate-rivals", action="store_true",
                        help="fixture-only: empty exactly the traced chapel and shipwreck biome tags")
    args = parser.parse_args()
    print(json.dumps(prepare(args.fixture, fixed_beach_biome=args.fixed_beach_biome,
                             widen_natural_biomes=args.widen_natural_biomes,
                             isolate_rivals=args.isolate_rivals), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
