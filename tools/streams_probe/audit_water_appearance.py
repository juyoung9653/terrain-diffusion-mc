#!/usr/bin/env python3
"""Read saved river-bed and kelp appearance evidence without changing a world.

The report deliberately describes observed block states.  It does not call a
one-block bed depression, or a difference between adjacent kelp chunks, a pass
or failure: the caller must choose an acceptance policy for its fixture.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "ecology"))
from compare_saved_vegetation import WorldReader  # noqa: E402

RIVER = (-80, -33, 1680, 1727, 65, 100)
OCEAN = (-208, -65, 1184, 1311, 0, 100)
WATER = frozenset({"minecraft:water"})
AIR = frozenset({"minecraft:air", "minecraft:cave_air", "minecraft:void_air"})
KELP = frozenset({"minecraft:kelp", "minecraft:kelp_plant"})


@dataclass(frozen=True)
class RiverColumn:
    water_surface: int | None
    water_bottom: int | None
    bed_y: int | None
    bed_block: str | None
    air_below_water: bool
    terrain_slab_count: int


def positions(bounds: tuple[int, int, int, int, int, int]) -> Iterable[tuple[int, int]]:
    min_x, max_x, min_z, max_z, _, _ = bounds
    for z in range(min_z, max_z + 1):
        for x in range(min_x, max_x + 1):
            yield x, z


def require_full(reader: WorldReader, bounds: tuple[int, int, int, int, int, int]) -> None:
    missing = sorted({(x // 16, z // 16) for x, z in positions(bounds)
                      if reader.chunk(x // 16, z // 16) is None})
    if missing:
        raise ValueError("required FULL chunks are missing or non-FULL: "
                         + ";".join(f"{x},{z}" for x, z in missing))


def river_column(block: Callable[[int, int, int], str], x: int, z: int,
                 min_y: int, max_y: int) -> RiverColumn:
    water_ys = [y for y in range(max_y, min_y - 1, -1) if block(x, y, z) in WATER]
    if not water_ys:
        slabs = sum(block(x, y, z).startswith("terrain_slabs:")
                    for y in range(min_y, max_y + 1))
        return RiverColumn(None, None, None, None, False, slabs)
    surface, bottom = water_ys[0], water_ys[0]
    # The water stack may be interrupted by a solid cap; use only its contiguous
    # bottom for support evidence, rather than treating a separate underground
    # water pocket as part of the surface column.
    while bottom > min_y and block(x, bottom - 1, z) in WATER:
        bottom -= 1
    below = block(x, bottom - 1, z) if bottom > min_y else None
    slabs = sum(block(x, y, z).startswith("terrain_slabs:")
                for y in range(min_y, max_y + 1))
    return RiverColumn(surface, bottom, bottom - 1 if below is not None else None,
                       below, below in AIR, slabs)


def isolated_dips(columns: dict[tuple[int, int], RiverColumn]) -> list[tuple[int, int]]:
    """Find a one-cell lower supported bed with the same water surface on all sides."""
    found = []
    for (x, z), column in columns.items():
        if column.water_surface is None or column.bed_y is None or column.air_below_water:
            continue
        neighbors = [columns.get((x + dx, z + dz)) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        if any(n is None or n.water_surface != column.water_surface or n.bed_y is None
               or n.air_below_water for n in neighbors):
            continue
        if all(n.bed_y >= column.bed_y + 1 for n in neighbors):
            found.append((x, z))
    return found


def river_report(reader: WorldReader, bounds=RIVER) -> dict:
    require_full(reader, bounds)
    min_x, max_x, min_z, max_z, min_y, max_y = bounds
    columns = {(x, z): river_column(reader.block, x, z, min_y, max_y)
               for x, z in positions(bounds)}
    dips = set(isolated_dips(columns))
    rows = []
    for (x, z), value in columns.items():
        rows.append({"x": x, "z": z, "waterSurfaceY": value.water_surface,
                     "waterBottomY": value.water_bottom, "bedY": value.bed_y,
                     "bedBlock": value.bed_block, "airBelowWater": value.air_below_water,
                     "terrainSlabCount": value.terrain_slab_count,
                     "isolatedOneCellDip": (x, z) in dips})
    materials = Counter(row["bedBlock"] for row in rows if row["bedBlock"])
    return {"bounds": {"minX": min_x, "maxX": max_x, "minZ": min_z, "maxZ": max_z,
                       "minY": min_y, "maxY": max_y},
            "method": "same-surface four-neighbor supported lower bed; descriptive only",
            "columns": rows, "waterColumns": sum(r["waterSurfaceY"] is not None for r in rows),
            "isolatedOneCellDipCount": len(dips), "isolatedOneCellDips": [list(p) for p in sorted(dips)],
            "airBelowWaterCount": sum(r["airBelowWater"] for r in rows),
            "terrainSlabCount": sum(r["terrainSlabCount"] for r in rows),
            "bedMaterials": dict(sorted(materials.items()))}


def kelp_report(reader: WorldReader, bounds=OCEAN) -> dict:
    require_full(reader, bounds)
    min_x, max_x, min_z, max_z, min_y, max_y = bounds
    chunks: dict[tuple[int, int], dict] = {}
    for x, z in positions(bounds):
        key = x // 16, z // 16
        row = chunks.setdefault(key, {"chunkX": key[0], "chunkZ": key[1], "kelpStemColumns": 0,
                                      "kelpBlocks": 0, "kelpPlantBlocks": 0,
                                      "northEdgeOccupied": 0, "southEdgeOccupied": 0,
                                      "westEdgeOccupied": 0, "eastEdgeOccupied": 0})
        names = [reader.block(x, y, z) for y in range(min_y, max_y + 1)]
        has = any(name in KELP for name in names)
        if has:
            row["kelpStemColumns"] += 1
            lx, lz = x & 15, z & 15
            if lz == 0: row["northEdgeOccupied"] += 1
            if lz == 15: row["southEdgeOccupied"] += 1
            if lx == 0: row["westEdgeOccupied"] += 1
            if lx == 15: row["eastEdgeOccupied"] += 1
        row["kelpBlocks"] += names.count("minecraft:kelp")
        row["kelpPlantBlocks"] += names.count("minecraft:kelp_plant")
    rows = [chunks[key] for key in sorted(chunks)]
    by_key = {(row["chunkX"], row["chunkZ"]): row for row in rows}
    seams = []
    for key, row in sorted(by_key.items()):
        for direction, other_key in (("east", (key[0] + 1, key[1])), ("south", (key[0], key[1] + 1))):
            other = by_key.get(other_key)
            if other:
                seams.append({"from": list(key), "to": list(other_key), "direction": direction,
                              "stemColumnDifference": abs(row["kelpStemColumns"] - other["kelpStemColumns"]),
                              "edgeOccupied": (row["eastEdgeOccupied"], other["westEdgeOccupied"])
                              if direction == "east" else (row["southEdgeOccupied"], other["northEdgeOccupied"])})
    return {"bounds": {"minX": min_x, "maxX": max_x, "minZ": min_z, "maxZ": max_z,
                       "minY": min_y, "maxY": max_y},
            "method": "per-16-block saved kelp counts and shared-edge occupancy; descriptive only",
            "chunks": rows, "adjacentChunkSeams": seams,
            "meanAdjacentStemColumnDifference": (sum(s["stemColumnDifference"] for s in seams) / len(seams)
                                                if seams else None)}


def compare(current: dict, baseline: dict) -> dict:
    def river_rows(report): return {(r["x"], r["z"]): r for r in report["river"]["columns"]}
    before, after = river_rows(baseline), river_rows(current)
    changed_water = sorted([list(key) for key in after
                            if (before[key]["waterSurfaceY"], before[key]["waterBottomY"])
                            != (after[key]["waterSurfaceY"], after[key]["waterBottomY"])])
    changed_bed = sorted([list(key) for key in after if before[key]["bedY"] != after[key]["bedY"]])
    def kelp_rows(report): return {(r["chunkX"], r["chunkZ"]): r for r in report["ocean"]["chunks"]}
    kb, ka = kelp_rows(baseline), kelp_rows(current)
    changed_kelp = sorted([list(key) for key in ka if kb[key]["kelpStemColumns"] != ka[key]["kelpStemColumns"]])
    return {"waterSurfaceOrBottomChangedColumns": changed_water,
            "bedDepthChangedColumns": changed_bed, "kelpStemCountChangedChunks": changed_kelp}


def report(world: Path) -> tuple[dict, WorldReader]:
    reader = WorldReader(world)
    value = {"world": str(reader.world), "river": river_report(reader), "ocean": kelp_report(reader)}
    return value, reader


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--baseline-world", type=Path)
    args = parser.parse_args()
    world, output = args.world.resolve(), args.output.resolve()
    if output.is_relative_to(world):
        raise ValueError("output must be outside saved world")
    if args.baseline_world and output.is_relative_to(args.baseline_world.resolve()):
        raise ValueError("output must be outside baseline saved world")
    current, current_reader = report(world)
    if args.baseline_world:
        baseline, baseline_reader = report(args.baseline_world.resolve())
        current["baselineComparison"] = compare(current, baseline)
        current["baselineSourceRegionsUnchangedAfterRead"] = baseline_reader.unchanged()
    current["sourceRegionsUnchangedAfterRead"] = current_reader.unchanged()
    if not current["sourceRegionsUnchangedAfterRead"] or not current.get("baselineSourceRegionsUnchangedAfterRead", True):
        raise RuntimeError("source region changed while reading; refusing report")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(current, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), "isolatedOneCellDips": current["river"]["isolatedOneCellDipCount"],
                      "kelpChunks": len(current["ocean"]["chunks"]), "sourceUnchanged": True}))


if __name__ == "__main__":
    main()
