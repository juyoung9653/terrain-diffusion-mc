"""Stage a separate Prism instance from a completed Terralith river probe.

Only creates a new output directory. Never edits the source instance or world.
Uses the persisted river cache, matching probe mods/config/models, and a normal
production mod JAR. The copied world starts in creative near audited water.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import struct
import zipfile


def nbt_fields(raw):
    """Locate named payloads without re-encoding unrelated NBT data."""
    fields = {}
    pos = 0

    def take(count):
        nonlocal pos
        if count < 0 or pos + count > len(raw):
            raise ValueError("Truncated NBT")
        value = raw[pos:pos + count]
        pos += count
        return value

    def string():
        return take(struct.unpack(">H", take(2))[0]).decode("utf-8")

    def payload(kind, path):
        start = pos
        value = None
        if kind in (1, 2, 3, 4, 5, 6):
            fmt = {1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[kind]
            value = struct.unpack(">" + fmt, take(struct.calcsize(fmt)))[0]
        elif kind == 8:
            value = string()
        elif kind in (7, 11, 12):
            count = struct.unpack(">i", take(4))[0]
            take(count * {7: 1, 11: 4, 12: 8}[kind])
        elif kind == 9:
            child = take(1)[0]
            count = struct.unpack(">i", take(4))[0]
            if count < 0:
                raise ValueError("Negative NBT list length")
            for index in range(count):
                payload(child, path + (str(index),))
        elif kind == 10:
            while True:
                child = take(1)[0]
                if child == 0:
                    break
                name = string()
                payload(child, path + (name,))
        else:
            raise ValueError(f"Unsupported NBT type {kind}")
        fields[path] = (kind, start, pos, value)

    kind = take(1)[0]
    string()
    payload(kind, ())
    if pos != len(raw):
        raise ValueError("Trailing NBT data")
    return fields


def prepare_world(path):
    raw = gzip.decompress(path.read_bytes())
    fields = nbt_fields(raw)
    assert fields[("Data", "WorldGenSettings", "generate_features")][3] == 0
    assert ("Data", "Player") not in fields, "Use a dedicated probe world without a player"
    changes = {
        ("Data", "LevelName"): "TD River Preview",
        ("Data", "GameType"): 1,
        ("Data", "allowCommands"): 1,
        ("Data", "SpawnX"): -540,
        ("Data", "SpawnY"): 64,
        ("Data", "SpawnZ"): -96,
        ("Data", "GameRules", "spawnRadius"): "0",
        ("Data", "GameRules", "spawnChunkRadius"): "0",
    }
    patches = []
    for key, value in changes.items():
        kind, start, end, _ = fields[key]
        if kind == 8:
            encoded = value.encode("utf-8")
            encoded = struct.pack(">H", len(encoded)) + encoded
        else:
            encoded = struct.pack(">" + {1: "b", 3: "i"}[kind], value)
        patches.append((start, end, encoded))
    for start, end, encoded in sorted(patches, reverse=True):
        raw = raw[:start] + encoded + raw[end:]
    result = nbt_fields(raw)
    assert all(result[key][3] == value for key, value in changes.items())
    # All unmodified scalar values, including seed, generator and datapacks,
    # must survive the edit exactly.
    assert all(result[key][3] == value[3] for key, value in fields.items() if key not in changes)
    path.write_bytes(gzip.compress(raw))


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-instance", type=Path, required=True)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    probe = args.probe.resolve(strict=True)
    source = args.source_instance.resolve(strict=True)
    jar = args.jar.resolve(strict=True)
    target = args.output.resolve()
    if target.exists():
        raise FileExistsError(target)
    manifest = json.loads((probe / "input-manifest.json").read_text())
    assert manifest["terralith"] and manifest["waterwaysDisabled"]
    assert manifest["seed"] == 6187795302574383407, "Spawn uses this audited seed"
    assert (probe / "world/td-river-preview-cache-v1.bin.gz").is_file()
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        assert any(name.endswith("RiverPreviewService.class") for name in names)
        assert not any("/diagnostic/" in name for name in names)
    target.mkdir(parents=True)
    shutil.copy2(source / "mmc-pack.json", target / "mmc-pack.json")
    (target / "instance.cfg").write_text(
        "[General]\nConfigVersion=1.2\nInstanceType=OneSix\n"
        "name=TD River Preview 1.21.1\niconKey=default\n"
        "OverrideJavaLocation=true\nJavaPath=C:/Program Files/Java/jdk-21/bin/javaw.exe\n"
        "OverrideJavaArgs=true\nJvmArgs=-XX:+UseZGC -XX:+ZGenerational\n"
        "OverrideMemory=true\nMinMemAlloc=2048\nMaxMemAlloc=12288\n", encoding="utf-8")
    game = target / "minecraft"
    for folder in ("mods", "config", "terrain-diffusion-models"):
        shutil.copytree(probe / folder, game / folder)
    shutil.copy2(jar, game / "mods" / jar.name)
    world = game / "saves/TD River Preview"
    shutil.copytree(probe / "world", world,
                    ignore=shutil.ignore_patterns("session.lock", "*.csv", "*.jsonl", "level.dat_old"))
    prepare_world(world / "level.dat")
    (game / "options.txt").write_text("lang:ko_kr\nrenderDistance:8\nsimulationDistance:5\n", encoding="utf-8")
    cache = "td-river-preview-cache-v1.bin.gz"
    assert sha256(world / cache) == sha256(probe / "world" / cache)
    receipt = {"jar": jar.name, "jarSha256": sha256(jar), "cacheSha256": sha256(world / cache),
               "instance": target.name, "world": "TD River Preview", "spawn": [-540, 64, -96],
               "creative": True, "commands": True, "structures": False,
               "newWorldPreviewFlag": False, "sourceSeed": manifest["seed"]}
    (target / "playtest-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
