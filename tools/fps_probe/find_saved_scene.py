"""Find dense jungle viewpoints in already-saved chunks without Minecraft.

This only reads Anvil region files from a saved world.  It neither opens the
game nor requests chunks, and records source-region hashes before and after the
scan so its result is invalidated if a source save changes concurrently.
"""
from __future__ import annotations

import argparse
from collections import Counter
import gzip
import hashlib
import json
from pathlib import Path
import re
import runpy
import struct
import zlib


ROOT = Path(__file__).resolve().parents[2]
_map_saved = runpy.run_path(str(ROOT / "tools/ecology/map_saved.py"))
nbt_fields = _map_saved["nbt_fields"]
packed_values = _map_saved["packed_values"]

AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


def leaf(name: str) -> bool:
    return name.endswith("_leaves") or name in {"minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves"}


def foliage(name: str) -> bool:
    return leaf(name) or name in AIR or name in {"minecraft:vine", "minecraft:glow_lichen", "minecraft:moss_carpet"}


def terrain_skip(name: str) -> bool:
    """Exclude canopy and tree stems when estimating walkable terrain."""
    return foliage(name) or any(token in name for token in ("_log", "_wood", "_stem", "_hyphae", "bamboo", "_sapling", "_fungus"))


def full_fields(raw: bytes) -> dict | None:
    fields = nbt_fields(raw)
    if fields.get(("Status",), (0, 0, 0, ""))[3] != "minecraft:full":
        return None
    return fields


def biome_sections(raw: bytes, fields: dict) -> tuple[dict[int, tuple[dict[int, str], list[int]]], list[tuple[tuple[str, str], int]]]:
    biomes, section_prefixes = {}, []
    for key, value in fields.items():
        if len(key) != 3 or key[0] != "sections" or key[2] != "Y":
            continue
        prefix, sy = key[:2], int(value[3])
        section_prefixes.append((prefix, sy))
        palette = {int(k[4]): v[3] for k, v in fields.items()
                   if len(k) == 5 and k[:4] == prefix + ("biomes", "palette")}
        if palette and set(palette) == set(range(len(palette))):
            values = packed_values(raw, fields.get(prefix + ("biomes", "data")), len(palette), 64, 1)
            biomes[sy] = (palette, values)
    return biomes, section_prefixes


def block_sections(raw: bytes, fields: dict, section_prefixes: list[tuple[tuple[str, str], int]]) -> dict[int, list[str]]:
    blocks = {}
    for prefix, sy in section_prefixes:
        names = {int(k[4]): v[3] for k, v in fields.items()
                 if len(k) == 6 and k[:4] == prefix + ("block_states", "palette") and k[5] == "Name"}
        if names and set(names) == set(range(len(names))):
            values = packed_values(raw, fields.get(prefix + ("block_states", "data")), len(names), 4096, 4)
            blocks[sy] = [names[index] for index in values]
    return blocks


def biome_at(biomes: dict[int, tuple[dict[int, str], list[int]]], y: int, x: int, z: int) -> str | None:
    entry = biomes.get(y // 16)
    if entry is None:
        return None
    palette, values = entry
    index = ((y & 15) // 4) * 16 + ((z & 15) // 4) * 4 + ((x & 15) // 4)
    return palette[values[index]]


def columns(blocks: dict[int, list[str]], biomes: dict[int, tuple[dict[int, str], list[int]]]) -> list[dict]:
    result = []
    for z in range(16):
        for x in range(16):
            upper_leaf, ground_y, biome = None, None, None
            for sy in sorted(blocks, reverse=True):
                states = blocks[sy]
                for ly in range(15, -1, -1):
                    name = states[ly * 256 + z * 16 + x]
                    y = sy * 16 + ly
                    if upper_leaf is None and leaf(name):
                        upper_leaf = y
                    if ground_y is None and not terrain_skip(name):
                        ground_y, biome = y, biome_at(biomes, y, x, z)
                    if ground_y is not None and upper_leaf is not None:
                        break
                if ground_y is not None and upper_leaf is not None:
                    break
            if ground_y is not None:
                def state_at(y: int) -> str:
                    values = blocks.get(y // 16)
                    return values[(y & 15) * 256 + z * 16 + x] if values else "minecraft:air"
                result.append({"x": x, "z": z, "ground_y": ground_y, "leaf_y": upper_leaf, "biome": biome,
                               "camera_blocks_air": state_at(ground_y + 1) in AIR and state_at(ground_y + 2) in AIR})
    return result


def read_chunk(region: bytes, cx: int, cz: int) -> bytes | None:
    index = ((cz & 31) * 32 + (cx & 31)) * 4
    offset, sectors = int.from_bytes(region[index:index + 3], "big") * 4096, region[index + 3]
    if not offset:
        return None
    if offset < 8192 or sectors == 0 or offset + sectors * 4096 > len(region):
        raise ValueError(f"invalid chunk allocation {cx},{cz}")
    length = int.from_bytes(region[offset:offset + 4], "big")
    if length < 1 or length + 4 > sectors * 4096:
        raise ValueError(f"invalid chunk length {cx},{cz}")
    codec, payload = region[offset + 4], region[offset + 5:offset + 4 + length]
    if codec == 1:
        return gzip.decompress(payload)
    if codec == 2:
        return zlib.decompress(payload)
    if codec == 3:
        return payload
    raise ValueError(f"unsupported Anvil compression {codec} at {cx},{cz}")


def scan(world: Path, output: Path, limit: int, biome_pattern: str) -> dict:
    world = world.resolve(strict=True)
    if not (world / "level.dat").is_file() or not (world / "region").is_dir():
        raise ValueError("--world must be an existing saved Minecraft world")
    if output.resolve().is_relative_to(world):
        raise ValueError("output must be outside the saved world")
    matcher = re.compile(biome_pattern, re.IGNORECASE)
    region_paths = sorted((world / "region").glob("r.*.*.mca"))
    before = {path.name: hashlib.file_digest(path.open("rb"), "sha256").hexdigest() for path in region_paths}
    full_coordinates: set[tuple[int, int]] = set()
    def checkpoint(phase: str, regions_done: int, **extra: object) -> None:
        partial = {"kind": "saved_leaf_heavy_forest_scene_candidates", "complete": False, "phase": phase,
                   "world": world.name, "biome_pattern": biome_pattern, "regions_done": regions_done,
                   "source_region_count": len(region_paths), "full_chunks_scanned": len(full_coordinates), **extra}
        temporary = output.with_suffix(output.suffix + ".tmp")
        temporary.write_text(json.dumps(partial, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(output)
    # Pass 1: establish only already-full chunks.  This makes the following
    # coverage test independent of any biome or rendering work.
    for region_index, path in enumerate(region_paths, 1):
        data = path.read_bytes()
        if len(data) >= 8192:
            _, rx, rz = path.stem.split(".")
            for local_z in range(32):
                for local_x in range(32):
                    cx, cz = int(rx) * 32 + local_x, int(rz) * 32 + local_z
                    raw = read_chunk(data, cx, cz)
                    if raw is not None and full_fields(raw) is not None:
                        full_coordinates.add((cx, cz))
        checkpoint("full_chunk_coverage", region_index)
    # Pass 2: inspect compact biome palettes only.  Block states remain opaque
    # until a candidate has all 625 saved chunks around render distance 12.
    eligible: list[tuple[int, int]] = []
    for region_index, path in enumerate(region_paths, 1):
        data = path.read_bytes()
        if len(data) >= 8192:
            _, rx, rz = path.stem.split(".")
            for local_z in range(32):
                for local_x in range(32):
                    cx, cz = int(rx) * 32 + local_x, int(rz) * 32 + local_z
                    if (cx, cz) not in full_coordinates:
                        continue
                    if sum((cx + dx, cz + dz) in full_coordinates for dx in range(-12, 13) for dz in range(-12, 13)) != 625:
                        continue
                    raw = read_chunk(data, cx, cz)
                    fields = full_fields(raw) if raw is not None else None
                    if fields is None:
                        continue
                    biomes, _ = biome_sections(raw, fields)
                    names = [name for palette, _ in biomes.values() for name in palette.values()]
                    if any(matcher.search(name) and "cave" not in name.lower() and "underground" not in name.lower() for name in names):
                        eligible.append((cx, cz))
        checkpoint("surface_forest_palette", region_index, eligible_full_coverage_chunks=len(eligible))
    candidates, biome_counts = [], Counter()
    # Pass 3: exact leaf counts only for the fully-covered surface-forest set.
    for candidate_index, (cx, cz) in enumerate(eligible, 1):
        path = world / "region" / f"r.{cx >> 5}.{cz >> 5}.mca"
        raw = read_chunk(path.read_bytes(), cx, cz)
        fields = full_fields(raw) if raw is not None else None
        if fields is None:
            continue
        biomes, prefixes = biome_sections(raw, fields)
        blocks = block_sections(raw, fields, prefixes)
        points = columns(blocks, biomes)
        forest = [point for point in points if point["biome"] and matcher.search(point["biome"]) and "cave" not in point["biome"].lower() and "underground" not in point["biome"].lower()]
        biome_counts.update(point["biome"] for point in forest)
        if not forest:
            continue
        leaf_blocks = sum(sum(leaf(name) for name in states) for states in blocks.values())
        leaf_columns = sum(point["leaf_y"] is not None for point in forest)
        point = max(forest, key=lambda p: (p["camera_blocks_air"], (p["leaf_y"] or -512) - p["ground_y"], -abs(p["x"] - 8) - abs(p["z"] - 8)))
        candidates.append({"chunk": [cx, cz], "coordinate": [cx * 16 + point["x"], point["ground_y"], cz * 16 + point["z"]],
                           "verified_surface_biomes": dict(Counter(p["biome"] for p in forest)), "forest_columns": len(forest),
                           "forest_leaf_columns": leaf_columns, "leaf_blocks_in_chunk": leaf_blocks, "ground_y": point["ground_y"],
                           "leaf_height_y": point["leaf_y"], "canopy_clearance": None if point["leaf_y"] is None else point["leaf_y"] - point["ground_y"],
                           "camera_blocks_air": point["camera_blocks_air"], "saved_chunks_within_render_distance_12": 625,
                           "render_distance_12_fully_saved": True})
        checkpoint("exact_leaf_counts", candidate_index, eligible_full_coverage_chunks=len(eligible), candidates_completed=len(candidates))
    candidates.sort(key=lambda c: (c["render_distance_12_fully_saved"], c["saved_chunks_within_render_distance_12"], c["forest_leaf_columns"], c["leaf_blocks_in_chunk"], c["canopy_clearance"] or -999), reverse=True)
    after = {path.name: hashlib.file_digest(path.open("rb"), "sha256").hexdigest() for path in region_paths}
    if before != after:
        raise RuntimeError("source region changed during read-only scan")
    report = {
        "kind": "saved_leaf_heavy_forest_scene_candidates",
        "world": world.name,
        "source_region_count": len(region_paths),
        "complete": True,
        "full_chunks_scanned": len(full_coordinates),
        "eligible_full_coverage_chunks": len(eligible),
        "biome_pattern": biome_pattern,
        "surface_forest_candidate_chunks": len(candidates),
        "candidates": candidates[:limit],
        "view_recommendation": "Use coordinate X,Z; set player feet to ground_y+1 (eye is feet+1.62) only when camera_blocks_air is true. Face a neighboring high-leaf candidate after one visual check. Prefer render_distance_12_fully_saved; otherwise the coverage count records the existing saved surroundings. Leaf density is exact saved block-state count, terrain Y skips leaves/vines/tree stems.",
        "limitations": "Only existing full saved chunks were decoded. This is an NBT biome/block-state scan, not a rendered visibility or entity-density measurement; no chunks were generated.",
        "biomes_observed": dict(biome_counts.most_common()),
        "source_regions_sha256": before,
        "source_regions_unchanged": True,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/fps-probe/saved-scenes.json")
    parser.add_argument("--limit", type=int, default=12)
    parser.add_argument("--biome-pattern", default=r"forest|jungle|woodland|blooming|bamboo",
                        help="case-insensitive regex matched against saved surface biome IDs")
    args = parser.parse_args()
    if args.limit < 1 or args.limit > 50:
        raise ValueError("--limit must be 1..50")
    report = scan(args.world, args.output, args.limit, args.biome_pattern)
    print(json.dumps({key: report[key] for key in ("full_chunks_scanned", "surface_forest_candidate_chunks", "source_regions_unchanged")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
