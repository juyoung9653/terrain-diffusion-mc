"""Isolate original Minecraft 1.21.1 villages from replacement datapacks.

Run with an unmodified official 1.21.1 client jar:
    python tools/structure_placement/generate_basic_villages.py CLIENT.jar
Use --check to verify checked-in assets without writing them. No third-party
packages are needed. The source jar is SHA-1 pinned: extracting from a modded
resource manager would silently copy Epic's replacements instead of vanilla.

Only structure starts, template locations, pool fallbacks and NBT jigsaw pool
links are relocated. Alias-only biome tags additionally admit matching TD woodlands;
loot tables, processors, features and entity IDs keep their vanilla contracts. A separate structure set exposes the aliases
to biome filtering; shared village placement replaces its scheduling when on.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile


ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "common/src/main/resources"
CLIENT_SHA1 = "30c73b1c5da787909b2f73340419fdf13b9def88"
KINDS = ("plains", "desert", "savanna", "snowy", "taiga")
SOURCE = "minecraft:village/"
TARGET = "terrain-diffusion-mc:basic_village/"
FIXED_TAG_BYTES = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}
TAG_PATH = "data/minecraft/tags/worldgen/structure/village.json"
SET_PATH = "data/terrain-diffusion-mc/worldgen/structure_set/basic_villages.json"


def alias(identifier: str) -> str:
    return TARGET + identifier[len(SOURCE):] if identifier.startswith(SOURCE) else identifier


def rewrite_json(value):
    if isinstance(value, str):
        return alias(value)
    if isinstance(value, list):
        return [rewrite_json(item) for item in value]
    if isinstance(value, dict):
        return {key: rewrite_json(item) for key, item in value.items()}
    return value


def json_bytes(value) -> bytes:
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def resource_path(kind: str, identifier: str, extension="json") -> str:
    namespace, name = identifier.split(":", 1)
    if ".." in name.split("/") or name.startswith("/"):
        raise ValueError(f"Invalid resource location: {identifier}")
    return f"data/{namespace}/{kind}/{name}.{extension}"


def transform_nbt(raw: bytes, rewrite=alias) -> tuple[bytes, set[str]]:
    """Preserve every NBT byte except named TAG_String 'pool' payloads.

    Parsing tag boundaries avoids corrupting UTF lengths or changing loot,
    entity and jigsaw name/target contracts. Return original pool references
    to drive graph traversal. Unchanged strings remain raw modified UTF-8.
    """
    source = io.BytesIO(raw)
    output = io.BytesIO()
    pools = set()

    def take(count):
        if count < 0:
            raise ValueError("Negative NBT length")
        data = source.read(count)
        if len(data) != count:
            raise ValueError("Truncated NBT payload")
        return data

    def copy(count):
        data = take(count)
        output.write(data)
        return data

    def string():
        return take(struct.unpack(">H", take(2))[0])

    def write_string(data):
        output.write(struct.pack(">H", len(data)))
        output.write(data)

    def payload(kind, name=b""):
        if kind in FIXED_TAG_BYTES:
            copy(FIXED_TAG_BYTES[kind])
        elif kind == 8:
            value = string()
            if name == b"pool":
                identifier = value.decode("ascii")
                pools.add(identifier)
                value = rewrite(identifier).encode("ascii")
            write_string(value)
        elif kind == 9:
            child = copy(1)[0]
            count = struct.unpack(">i", copy(4))[0]
            if count < 0 or (child == 0 and count):
                raise ValueError("Invalid NBT list")
            # Numeric lists contain no named pool strings. Preserve their bytes
            # in one bounded copy instead of visiting each coordinate separately.
            if child in FIXED_TAG_BYTES:
                copy(count * FIXED_TAG_BYTES[child])
            else:
                for _ in range(count):
                    payload(child)
        elif kind == 10:
            while (child := copy(1)[0]):
                child_name = string()
                write_string(child_name)
                payload(child, child_name)
        elif kind in (7, 11, 12):
            count = struct.unpack(">i", copy(4))[0]
            copy(count * {7: 1, 11: 4, 12: 8}[kind])
        else:
            raise ValueError(f"Unknown NBT tag: {kind}")

    kind = copy(1)[0]
    if kind != 10:
        raise ValueError("Structure NBT must have a compound root")
    write_string(string())
    payload(kind)
    if source.read(1):
        raise ValueError("Trailing bytes after NBT root")
    return output.getvalue(), pools


def compress_nbt(raw: bytes) -> bytes:
    # GzipFile fixes filename/mtime and the OS byte across Python platforms.
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode="wb", filename="", mtime=0) as stream:
        stream.write(raw)
    return output.getvalue()


def template_locations(value):
    if isinstance(value, dict):
        if "location" in value:
            yield value["location"]
        for item in value.values():
            yield from template_locations(item)
    elif isinstance(value, list):
        for item in value:
            yield from template_locations(item)


def build_assets(archive: zipfile.ZipFile) -> dict[str, bytes]:
    """Traverse all five starts through fallback, template and jigsaw links."""
    version = json.loads(archive.read("version.json"))
    if version.get("id") != "1.21.1":
        raise ValueError("Expected Minecraft 1.21.1 resources")
    assets = {}
    pending = []
    visited = set()
    for kind in KINDS:
        entry = f"data/minecraft/worldgen/structure/village_{kind}.json"
        structure = json.loads(archive.read(entry))
        extra = {
            "plains": ["temperate_forest", "temperate_woodland"],
            "savanna": ["tropical_woodland"],
            "snowy": ["snowy_boreal_forest", "snowy_boreal_woodland"],
        }.get(kind, [])
        tag = f"terrain-diffusion-mc:has_structure/basic_village_{kind}"
        assets[resource_path("tags/worldgen/biome", tag)] = json_bytes({"replace": False, "values": [
            structure["biomes"], *["terrain-diffusion-mc:ecology/" + biome for biome in extra]
        ]})
        structure["biomes"] = "#" + tag
        pending.append(structure["start_pool"])
        assets[f"data/terrain-diffusion-mc/worldgen/structure/basic_village_{kind}.json"] = json_bytes(rewrite_json(structure))
    while pending:
        pool_id = pending.pop()
        if pool_id == "minecraft:empty" or pool_id in visited:
            continue
        if not pool_id.startswith(SOURCE):
            raise ValueError(f"Unexpected external village pool: {pool_id}")
        visited.add(pool_id)
        pool = json.loads(archive.read(resource_path("worldgen/template_pool", pool_id)))
        assets[resource_path("worldgen/template_pool", alias(pool_id))] = json_bytes(rewrite_json(pool))
        pending.append(pool["fallback"])
        for template in template_locations(pool):
            if not template.startswith(SOURCE):
                raise ValueError(f"Unexpected external village template: {template}")
            destination = resource_path("structure", alias(template), "nbt")
            if destination in assets:
                continue
            raw = gzip.decompress(archive.read(resource_path("structure", template, "nbt")))
            rewritten, references = transform_nbt(raw)
            pending.extend(sorted(references))
            assets[destination] = compress_nbt(rewritten)
    assets[TAG_PATH] = json_bytes({"replace": False, "values": [
        f"terrain-diffusion-mc:basic_village_{kind}" for kind in KINDS
    ]})
    structure_set = json.loads(archive.read("data/minecraft/worldgen/structure_set/villages.json"))
    for entry in structure_set["structures"]:
        entry["structure"] = entry["structure"].replace("minecraft:village_", "terrain-diffusion-mc:basic_village_", 1)
    assets[SET_PATH] = json_bytes(structure_set)
    return dict(sorted(assets.items()))


def generate(jar: Path, output: Path, check=False) -> dict[str, bytes]:
    with jar.open("rb") as stream:
        source_hash = hashlib.file_digest(stream, "sha1").hexdigest()
    if source_hash != CLIENT_SHA1:
        raise ValueError("Source must be the unmodified official Minecraft 1.21.1 client jar (SHA-1 " + CLIENT_SHA1 + ")")
    with zipfile.ZipFile(jar) as archive:
        assets = build_assets(archive)
    mismatches = []
    for relative, data in assets.items():
        destination = output / relative
        if check:
            if not destination.is_file() or destination.read_bytes() != data:
                mismatches.append(relative)
        elif not destination.is_file() or destination.read_bytes() != data:
            # Preserve timestamps of identical assets so downstream resource
            # builds do not interpret an unchanged generation as modified input.
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
    if mismatches:
        raise ValueError("Generated assets differ: " + ", ".join(mismatches))
    return assets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("minecraft_jar", type=Path)
    parser.add_argument("--output", type=Path, default=RESOURCES)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    assets = generate(args.minecraft_jar, args.output, args.check)
    counts = {suffix: sum(name.endswith(suffix) for name in assets) for suffix in (".json", ".nbt")}
    print(f"{'Verified' if args.check else 'Generated'} {len(assets)} resources: {counts}, {sum(map(len, assets.values())):,} bytes")


if __name__ == "__main__":
    main()
