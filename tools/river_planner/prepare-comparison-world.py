"""Create a new, chunk-empty comparison world from a cached V1 probe fixture.

Never upgrades a player's existing world. Terrain inputs and seed stay fixed;
natural mode writes a V2 cache identity, Streams mode omits preview state.
Use matching instance configs (Streams waterways on only for the reference).
"""
import argparse
import gzip
from pathlib import Path
import runpy
import shutil
import struct


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mode", choices=("natural", "streams"), required=True)
    args = parser.parse_args()
    helper = runpy.run_path(str(Path(__file__).with_name("prepare-playtest.py")))
    source = args.source.resolve(strict=True)
    target = args.output.resolve()
    if target.exists():
        raise FileExistsError(target)
    fields = helper["nbt_fields"](gzip.decompress((source / "level.dat").read_bytes()))
    if ("Data", "Player") in fields:
        raise ValueError("Use a dedicated probe fixture without player data")
    if fields[("Data", "WorldGenSettings", "seed")][3] != 6187795302574383407:
        raise ValueError("Comparison spawn requires the audited fixture seed")
    cache = gzip.decompress((source / "td-river-preview-cache-v1.bin.gz").read_bytes())
    if struct.unpack(">iii", cache[:12]) != (0x54445250, 1, 1):
        raise ValueError("Expected exact V1 terrain cache")
    (target / "data").mkdir(parents=True)
    shutil.copy2(source / "level.dat", target / "level.dat")
    shutil.copytree(source / "datapacks", target / "datapacks")
    shutil.copy2(source / "data/terrain_diffusion_world_settings.dat", target / "data/terrain_diffusion_world_settings.dat")
    helper["prepare_world"](target / "level.dat")
    helper["patch_nbt"](target / "level.dat", {
        ("Data", "LevelName"): "TD Natural Rivers" if args.mode == "natural" else "TD Streams Reference",
    })
    if args.mode == "natural":
        state = target / "data/terrain_diffusion_river_preview.dat"
        shutil.copy2(source / "data/terrain_diffusion_river_preview.dat", state)
        helper["patch_nbt"](state, {("data", "version"): 2})
        modified = cache[:8] + struct.pack(">i", 2) + cache[12:]
        (target / "td-river-preview-cache-v2.bin.gz").write_bytes(gzip.compress(modified))
        assert modified[12:] == cache[12:], "Terrain input bytes must be preserved"
    print(f"Created {args.mode} world without existing chunks: {target}")


if __name__ == "__main__":
    main()
