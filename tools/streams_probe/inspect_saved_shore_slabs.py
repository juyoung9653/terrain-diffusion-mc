"""Read a saved shoreline without launching Minecraft or writing to the world.

The report identifies whether a displayed full block is a Terrain Slabs candidate
under the native generator's side-wall rule.  It records exact block names from
one in-memory snapshot of each required MCA file; it does not infer feature or
structure provenance from block-tag membership.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "ecology"))
from compare_saved_vegetation import decode_chunk  # noqa: E402


class SnapshotReader:
    def __init__(self, world: Path, min_x: int, max_x: int, min_z: int, max_z: int):
        self.world = world.resolve()
        if not (self.world / "level.dat").is_file():
            raise ValueError("Expected saved world with level.dat")
        needed = {(x // 16 // 32, z // 16 // 32)
                  for x in range(min_x, max_x + 1) for z in range(min_z, max_z + 1)}
        self.regions: dict[tuple[int, int], bytes] = {}
        self.snapshot_hashes: dict[str, str] = {}
        self.end_hashes: dict[str, str | None] = {}
        for key in sorted(needed):
            path = self.world / "region" / f"r.{key[0]}.{key[1]}.mca"
            data = path.read_bytes() if path.is_file() else b""
            self.regions[key] = data
            self.snapshot_hashes[path.name] = hashlib.sha256(data).hexdigest()
        self.chunks: dict[tuple[int, int], object | None] = {}

    def chunk(self, cx: int, cz: int):
        key = cx, cz
        if key in self.chunks:
            return self.chunks[key]
        data = self.regions[cx // 32, cz // 32]
        if len(data) < 8192:
            self.chunks[key] = None
            return None
        index = ((cz % 32) * 32 + cx % 32) * 4
        offset, sectors = int.from_bytes(data[index:index + 3], "big") * 4096, data[index + 3]
        if not offset:
            self.chunks[key] = None
            return None
        if not sectors or offset < 8192 or offset + sectors * 4096 > len(data):
            raise ValueError(f"Invalid chunk allocation {cx},{cz}")
        length = int.from_bytes(data[offset:offset + 4], "big")
        if length < 1 or length + 4 > sectors * 4096:
            raise ValueError(f"Invalid chunk length {cx},{cz}")
        payload = data[offset + 5:offset + 4 + length]
        compression = data[offset + 4]
        raw = {1: gzip.decompress, 2: zlib.decompress, 3: lambda value: value}.get(compression)
        if raw is None:
            raise ValueError(f"Unsupported compression {compression}")
        self.chunks[key] = decode_chunk(raw(payload))
        return self.chunks[key]

    def block(self, x: int, y: int, z: int) -> str:
        chunk = self.chunk(x // 16, z // 16)
        return "minecraft:air" if chunk is None else chunk.block(x % 16, y, z % 16)

    def finish(self) -> bool:
        for name, digest in self.snapshot_hashes.items():
            path = self.world / "region" / name
            self.end_hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
        return all(self.end_hashes[name] == digest for name, digest in self.snapshot_hashes.items())


OPEN_OR_DECORATION = frozenset((
    "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:water",
    "minecraft:short_grass", "minecraft:tall_grass", "minecraft:kelp", "minecraft:kelp_plant",
    "hybrid_aquatic:bull_kelp", "supplementaries:barnacles",
))
# These are only an offline lower-bound for the installed ModSlabsMap.  The report
# deliberately labels them as shape candidates, not as proof that the native
# generator would place a slab after every mod's feature ordering has run.
COMMON_MAPPED_SUPPORT = frozenset((
    "minecraft:stone", "minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt",
    "minecraft:podzol", "minecraft:sand", "minecraft:red_sand", "minecraft:gravel",
    "minecraft:deepslate", "minecraft:andesite", "minecraft:diorite", "minecraft:granite",
    "minecraft:cobblestone",
))


def unplaced_shape_candidates(reader: SnapshotReader, center_x: int, center_z: int, radius: int) -> list[list[object]]:
    """Find simple visible wall/support shapes that lack a generated slab.

    The real feature additionally consults exact collision shapes, its complete
    material map, and generation order. This test is intentionally not a verdict
    that a particular cell must receive a slab.
    """
    result = []
    for z in range(center_z - radius, center_z + radius + 1):
        for x in range(center_x - radius, center_x + radius + 1):
            for y in range(45, 75):
                at, below, above = reader.block(x, y, z), reader.block(x, y - 1, z), reader.block(x, y + 1, z)
                if at not in OPEN_OR_DECORATION or below not in COMMON_MAPPED_SUPPORT or above not in OPEN_OR_DECORATION:
                    continue
                for name, dx, dz in (("east", 1, 0), ("west", -1, 0), ("south", 0, 1), ("north", 0, -1)):
                    wall = reader.block(x + dx, y, z + dz)
                    wall_above = reader.block(x + dx, y + 1, z + dz)
                    opposite_support = reader.block(x - dx, y - 1, z - dz)
                    if wall in COMMON_MAPPED_SUPPORT and wall_above in OPEN_OR_DECORATION \
                            and opposite_support in COMMON_MAPPED_SUPPORT:
                        result.append([x, y, z, name, at, below, wall, wall_above, opposite_support])
                        break
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True)
    parser.add_argument("--target", nargs=3, type=int, metavar=("X", "Y", "Z"), required=True)
    parser.add_argument("--player", nargs=2, type=int, metavar=("X", "Z"), required=True)
    parser.add_argument("--radius", type=int, default=16)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    tx, ty, tz = args.target
    px, pz = args.player
    if not 1 <= args.radius <= 32:
        raise ValueError("radius must be 1..32")
    output = args.output.resolve()
    if output.is_relative_to(args.world.resolve()):
        raise ValueError("Output must be outside the saved world")
    reader = SnapshotReader(args.world, min(tx, px) - args.radius, max(tx, px) + args.radius,
                            min(tz, pz) - args.radius, max(tz, pz) + args.radius)
    candidate = (tx, ty + 1, tz)
    x, y, z = candidate
    directions = (("east", 1, 0), ("west", -1, 0), ("south", 0, 1), ("north", 0, -1))
    sides = {
        name: {"wall_at_candidate_y": reader.block(x + dx, y, z + dz),
               "wall_above": reader.block(x + dx, y + 1, z + dz),
               "opposite_support_below": reader.block(x - dx, y - 1, z - dz)}
        for name, dx, dz in directions
    }
    slabs = []
    for scan_z in range(pz - args.radius, pz + args.radius + 1):
        for scan_x in range(px - args.radius, px + args.radius + 1):
            chunk = reader.chunk(scan_x // 16, scan_z // 16)
            if chunk is None:
                continue
            for scan_y in range(chunk.min_y, chunk.max_y + 1):
                block = chunk.block(scan_x % 16, scan_y, scan_z % 16)
                if block.startswith("terrain_slabs:"):
                    slabs.append([scan_x, scan_y, scan_z, block])
    config = args.world.parents[1] / "config" / "terrain_slabs.json"
    report = {
        "format": 1,
        "method": "MCA files are read once into memory; block names are decoded from that snapshot. "
                  "No Minecraft process is launched and no save file is written.",
        "world": str(args.world.resolve()),
        "target_full_block": {"position": [tx, ty, tz], "block": reader.block(tx, ty, tz)},
        "native_bottom_slab_candidate_above_target": {
            "position": list(candidate), "block": reader.block(*candidate),
            "above": reader.block(x, y + 1, z), "below": reader.block(x, y - 1, z),
            "side_samples": sides,
            "interpretation": "The native SlabFeature needs a non-slab full collision wall at candidate Y, "
                              "open space above that wall, and full opposite support. Air/short grass walls "
                              "cannot satisfy its wall condition, so this case rejects before the TD contour gate."
        },
        "terrain_slabs_within_player_radius": {
            "center": [px, pz], "radius": args.radius, "count": len(slabs),
            "first_40_positions": slabs[:40]
        },
        "unplaced_simple_wall_shapes_within_target_radius": {
            "center": [tx, tz], "radius": args.radius,
            "count": 0, "positions": [],
            "method_limit": "Offline lower-bound only: common mapped vanilla supports and known open/decorative "
                            "blocks are used. Exact collision shapes, the complete ModSlabsMap, and feature order "
                            "are not reconstructed; these rows identify places needing native admission audit."
        },
        "terrain_slabs_config": json.loads(config.read_text(encoding="utf-8")) if config.is_file() else None,
        "source_region_snapshot_sha256": reader.snapshot_hashes,
    }
    report["unplaced_simple_wall_shapes_within_target_radius"]["positions"] = \
        unplaced_shape_candidates(reader, tx, tz, args.radius)
    report["unplaced_simple_wall_shapes_within_target_radius"]["count"] = \
        len(report["unplaced_simple_wall_shapes_within_target_radius"]["positions"])
    report["source_regions_unchanged_after_snapshot"] = reader.finish()
    report["source_region_end_sha256"] = reader.end_hashes
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"target": report["target_full_block"],
                      "slab_count": len(slabs),
                      "source_unchanged": report["source_regions_unchanged_after_snapshot"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
