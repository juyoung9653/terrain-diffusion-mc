"""Read true natural ground across a saved-world seam without launching Minecraft.

Leaves, trunks, roots, ground plants and water decoration are reported as cover
rather than treated as terrain.  A slab or an unknown solid deliberately stops a
column: the report never guesses what lies below a structure-like surface.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "ecology"))
from compare_saved_vegetation import AIR, WorldReader, is_ground_plant  # noqa: E402

WATER = {"minecraft:water", "minecraft:kelp", "minecraft:kelp_plant",
         "minecraft:seagrass", "minecraft:tall_seagrass"}
GROUND = {"minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt",
          "minecraft:podzol", "minecraft:rooted_dirt", "minecraft:mud",
          "minecraft:clay", "minecraft:sand", "minecraft:red_sand",
          "minecraft:gravel", "minecraft:stone", "minecraft:deepslate"}


def cover(block: str) -> bool:
    return (is_ground_plant(block) or block.endswith(("_leaves", "_log", "_wood"))
            or block in {"minecraft:vine", "minecraft:lily_pad", "minecraft:mangrove_roots",
                         "minecraft:muddy_mangrove_roots"})


def column(world: WorldReader, x: int, z: int, min_y: int, max_y: int) -> dict:
    chunk = world.chunk(x // 16, z // 16)
    if chunk is None:
        return {"x": x, "z": z, "classification": "missing_chunk"}
    covers, water_top = Counter(), None
    for y in range(min(max_y, chunk.max_y), max(min_y, chunk.min_y) - 1, -1):
        block = chunk.block(x & 15, y, z & 15)
        if block in AIR:
            continue
        if cover(block):
            covers[block] += 1
            continue
        if block in WATER:
            water_top = y if water_top is None else water_top
            continue
        if block.endswith("_slab"):
            return {"x": x, "z": z, "classification": "obscured_by_slab",
                    "block": block, "y": y, "waterSurfaceY": water_top,
                    "cover": dict(covers), "biome": chunk.biome(x & 15, y, z & 15)}
        if block in GROUND or block.startswith("terralith:"):
            return {"x": x, "z": z, "classification": "natural_ground",
                    "groundBlock": block, "groundY": y, "waterSurfaceY": water_top,
                    "waterDepth": 0 if water_top is None else water_top - y,
                    "cover": dict(covers), "biome": chunk.biome(x & 15, y, z & 15)}
        return {"x": x, "z": z, "classification": "obscured_by_solid",
                "block": block, "y": y, "waterSurfaceY": water_top,
                "cover": dict(covers), "biome": chunk.biome(x & 15, y, z & 15)}
    return {"x": x, "z": z, "classification": "no_ground_in_range", "cover": dict(covers)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", required=True, type=Path)
    parser.add_argument("--left-x", required=True, type=int)
    parser.add_argument("--right-x", required=True, type=int)
    parser.add_argument("--min-z", required=True, type=int)
    parser.add_argument("--max-z", required=True, type=int)
    parser.add_argument("--min-y", type=int, default=40)
    parser.add_argument("--max-y", type=int, default=180)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.max_z < args.min_z or args.left_x == args.right_x or args.max_y < args.min_y:
        parser.error("expected distinct X columns and nonempty inclusive ranges")
    world = WorldReader(args.world)
    rows = []
    for z in range(args.min_z, args.max_z + 1):
        left = column(world, args.left_x, z, args.min_y, args.max_y)
        right = column(world, args.right_x, z, args.min_y, args.max_y)
        delta = None
        if left.get("classification") == right.get("classification") == "natural_ground":
            delta = right["groundY"] - left["groundY"]
        rows.append({"z": z, "left": left, "right": right, "rightMinusLeftGroundY": delta})
    if not world.unchanged():
        raise RuntimeError("saved source region changed during read-only inspection")
    paired = [row["rightMinusLeftGroundY"] for row in rows if row["rightMinusLeftGroundY"] is not None]
    report = {
        "scope": "read-only saved blocks; vegetation is cover and slabs/unknown solids are explicit obstructions",
        "world": str(args.world.resolve()),
        "columns": {"leftX": args.left_x, "rightX": args.right_x, "z": [args.min_z, args.max_z],
                    "y": [args.min_y, args.max_y]},
        "rows": rows,
        "pairedNaturalGround": {"count": len(paired), "rightMinusLeft": {
            "min": min(paired, default=None), "max": max(paired, default=None),
            "atLeast3": sum(abs(value) >= 3 for value in paired)}},
        "sourceRegionsStable": True,
        "regionSha256": {str(path): digest for path, digest in world.hashes.items()},
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), **report["pairedNaturalGround"]}))


if __name__ == "__main__":
    main()
