"""Read bounded saved-world patches and measure substrate topology.

This is an offline Anvil reader.  It never launches Minecraft, generates chunks,
or writes into either supplied world.  The numbers describe saved blocks only;
they are diagnostics and are not visual acceptance.
"""
from __future__ import annotations

import argparse
from collections import Counter, deque
import functools
import gzip
import hashlib
import json
from pathlib import Path
import runpy
import struct
import zlib

ROOT = Path(__file__).resolve().parents[2]
nbt_fields = runpy.run_path(str(ROOT / "tools/river_planner/prepare-playtest.py"))["nbt_fields"]
AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
WATER = {"minecraft:water", "minecraft:kelp", "minecraft:kelp_plant",
         "minecraft:seagrass", "minecraft:tall_seagrass"}
DEFAULT_MATERIALS = ("minecraft:sand", "minecraft:mud", "minecraft:clay",
                     "immersive_weathering:silt")


def _packed(raw: bytes, field: tuple | None, palette_size: int, count: int) -> list[int]:
    if not palette_size:
        raise ValueError("empty block-state palette")
    if field is None:
        if palette_size != 1:
            raise ValueError("missing block-state data for multi-entry palette")
        return [0] * count
    kind, start, end, _ = field
    if kind != 12:
        raise ValueError("block-state data is not a long array")
    bits = max(4, (palette_size - 1).bit_length())
    per_word = 64 // bits
    length = struct.unpack_from(">i", raw, start)[0]
    expected = (count + per_word - 1) // per_word
    if length != expected or end - start != 4 + length * 8:
        raise ValueError("invalid packed block-state data")
    words = struct.unpack_from(">" + "Q" * length, raw, start + 4)
    mask = (1 << bits) - 1
    values = [(words[i // per_word] >> ((i % per_word) * bits)) & mask for i in range(count)]
    if any(value >= palette_size for value in values):
        raise ValueError("block-state palette index out of range")
    return values


def _palette(fields: dict, prefix: tuple) -> dict[int, tuple[str, bool]]:
    names = {int(key[4]): value[3] for key, value in fields.items()
             if len(key) == 6 and key[:4] == prefix + ("block_states", "palette")
             and key[5] == "Name"}
    if not names:
        return {}
    if set(names) != set(range(len(names))):
        raise ValueError("noncontiguous block-state palette")
    wet = set()
    for key, value in fields.items():
        if len(key) == 7 and key[:4] == prefix + ("block_states", "palette") \
                and key[-2:] == ("Properties", "waterlogged") and value[3] == "true":
            wet.add(int(key[4]))
    return {index: (name, index in wet or name in WATER) for index, name in names.items()}


class SavedWorld:
    """Read-only cached Anvil access. Missing/non-FULL chunks are explicit."""
    def __init__(self, world: Path):
        self.world = world.resolve()
        if not (self.world / "level.dat").is_file():
            raise ValueError(f"expected saved world with level.dat: {self.world}")
        self.hashes: dict[str, str] = {}
        self.missing_chunks: set[tuple[int, int]] = set()
        self.non_full_chunks: dict[tuple[int, int], str] = {}

    @functools.lru_cache(maxsize=64)
    def _region(self, rx: int, rz: int) -> bytes:
        path = self.world / "region" / f"r.{rx}.{rz}.mca"
        if not path.exists():
            return b""
        data = path.read_bytes()
        self.hashes[path.name] = hashlib.sha256(data).hexdigest()
        return data

    @functools.lru_cache(maxsize=4096)
    def _chunk(self, cx: int, cz: int) -> dict[int, tuple[dict[int, tuple[str, bool]], list[int]]]:
        data = self._region(cx >> 5, cz >> 5)
        if len(data) < 8192:
            self.missing_chunks.add((cx, cz)); return {}
        index = ((cz & 31) * 32 + (cx & 31)) * 4
        offset = int.from_bytes(data[index:index + 3], "big") * 4096
        sectors = data[index + 3]
        if not offset:
            if sectors: raise ValueError(f"invalid empty allocation at {cx},{cz}")
            self.missing_chunks.add((cx, cz)); return {}
        if offset < 8192 or not sectors or offset + sectors * 4096 > len(data):
            raise ValueError(f"invalid chunk allocation at {cx},{cz}")
        length = int.from_bytes(data[offset:offset + 4], "big")
        if length < 1 or length + 4 > sectors * 4096:
            raise ValueError(f"invalid compressed chunk length at {cx},{cz}")
        codec, payload = data[offset + 4], data[offset + 5:offset + 4 + length]
        if codec == 1: raw = gzip.decompress(payload)
        elif codec == 2: raw = zlib.decompress(payload)
        elif codec == 3: raw = payload
        else: raise ValueError(f"unsupported Anvil compression {codec} at {cx},{cz}")
        fields = nbt_fields(raw)
        status = fields.get(("Status",), (0, 0, 0, ""))[3]
        if status != "minecraft:full":
            self.non_full_chunks[(cx, cz)] = status; return {}
        sections = {}
        for key, value in fields.items():
            if len(key) != 3 or key[0] != "sections" or key[2] != "Y": continue
            prefix, palette = key[:2], _palette(fields, key[:2])
            if palette:
                sections[int(value[3])] = (palette, _packed(raw, fields.get(prefix + ("block_states", "data")), len(palette), 4096))
        return sections

    def state(self, x: int, y: int, z: int) -> tuple[str, bool] | None:
        section = self._chunk(x >> 4, z >> 4).get(y >> 4)
        if section is None: return None
        palette, values = section
        index = (y & 15) * 256 + (z & 15) * 16 + (x & 15)
        return palette[values[index]]

    def stable(self) -> bool:
        return all(hashlib.sha256((self.world / "region" / name).read_bytes()).hexdigest() == digest
                   for name, digest in self.hashes.items())


def components(grid: dict[tuple[int, int], str], material: str) -> dict:
    unseen, sizes = {point for point, value in grid.items() if value == material}, []
    while unseen:
        start = unseen.pop(); queue = deque([start]); count = 0
        while queue:
            x, z = queue.popleft(); count += 1
            for neighbor in ((x - 1, z), (x + 1, z), (x, z - 1), (x, z + 1)):
                if neighbor in unseen:
                    unseen.remove(neighbor); queue.append(neighbor)
        sizes.append(count)
    sizes.sort(reverse=True)
    return {"components": len(sizes), "largest": sizes[0] if sizes else 0,
            "singletons": sum(size == 1 for size in sizes), "sizes": sizes[:20]}


def diagonal_pairs(grid: dict[tuple[int, int], str], material: str) -> int:
    count = 0
    for x, z in grid:
        points = [(x, z), (x + 1, z), (x, z + 1), (x + 1, z + 1)]
        if any(point not in grid or grid[point].startswith("__") for point in points): continue
        square = [grid[point] for point in points]
        mask = [value == material for value in square]
        if sum(mask) == 2 and ((mask[0] and mask[3]) or (mask[1] and mask[2])): count += 1
    return count


def straight_contact_runs(grid: dict[tuple[int, int], str], material: str) -> dict[str, int]:
    runs = {"northSouth": 0, "eastWest": 0}
    def side(point: tuple[int, int], axis: tuple[int, int]) -> int | None:
        x, z = point; dx, dz = axis; left, right = (-dz, dx), (dz, -dx)
        value, left_value, right_value = grid.get(point), grid.get((x + left[0], z + left[1])), grid.get((x + right[0], z + right[1]))
        if value != material or left_value is None or right_value is None: return None
        if left_value.startswith("__") or right_value.startswith("__"): return None
        if left_value == material and right_value != material: return 1
        if right_value == material and left_value != material: return -1
        return None
    # The key describes the line's orientation, hence north/south increments z.
    for axis, key in (((0, 1), "northSouth"), ((1, 0), "eastWest")):
        dx, dz = axis
        for point in sorted(grid):
            interface = side(point, axis)
            if interface is None or side((point[0] - dx, point[1] - dz), axis) == interface: continue
            length, cursor = 0, point
            while side(cursor, axis) == interface:
                length += 1; cursor = (cursor[0] + dx, cursor[1] + dz)
            runs[key] = max(runs[key], length)
    return runs


def scan_patch(world: SavedWorld, name: str, bounds: tuple[int, int, int, int], materials: set[str], min_y: int, max_y: int,
               *, include_grid: bool = False) -> dict:
    min_x, min_z, max_x, max_z = bounds
    grid, counts, waterlogged, coral, clearance = {}, Counter(), Counter(), 0, Counter()
    unclassified, unreadable, rows = 0, 0, []
    for z in range(min_z, max_z + 1):
        for x in range(min_x, max_x + 1):
            found, observed = None, False
            for y in range(max_y, min_y - 1, -1):
                state = world.state(x, y, z)
                if state is None: continue
                observed = True
                block, wet = state
                if "coral" in block and "dead_" not in block:
                    coral += 1
                    above = world.state(x, y + 1, z)
                    if wet: clearance["coralStateHasFluid"] += 1
                    if above is None: clearance["aboveUnknown"] += 1
                    elif above[1]: clearance["aboveHasFluidOrWaterloggedBlock"] += 1
                    else: clearance["aboveHasNoFluid"] += 1
                # Air and fluid are transparent for a submerged bed. The first
                # other state is the exposed substrate (or its covering); do not
                # dig below it merely to find a selected buried block.
                if block in AIR or block in WATER: continue
                if found is None: found = (block, y, wet)
            if found is None:
                grid[(x, z)] = "__unreadable__" if not observed else "__no_selected_material__"
                if observed: unclassified += 1
                else: unreadable += 1
                if include_grid:
                    rows.append({"x": x, "z": z, "classification": "transparent-or-empty" if observed else "unreadable"})
                continue
            block, y, wet = found
            if block not in materials:
                grid[(x, z)] = "__no_selected_material__"; unclassified += 1
                if include_grid:
                    rows.append({"x": x, "z": z, "classification": "covered" if wet else "other",
                                 "topBlock": block, "topY": y, "waterlogged": wet})
                continue
            grid[(x, z)] = block; counts[block] += 1
            if wet: waterlogged[block] += 1
            if include_grid:
                rows.append({"x": x, "z": z, "classification": "selected", "bedBlock": block,
                             "topY": y, "waterlogged": wet})
    result = {"bounds": {"x": [min_x, max_x], "z": [min_z, max_z], "y": [min_y, max_y]},
              "columns": len(grid), "unclassifiedColumns": unclassified, "unreadableColumns": unreadable,
              "materials": dict(sorted(counts.items())), "waterloggedSelectedBlocks": dict(sorted(waterlogged.items())),
              "livingCoralBlocks": coral, "coralFluidClearance": dict(clearance), "topology": {}}
    if include_grid: result["grid"] = rows
    for material in sorted(materials):
        result["topology"][material] = {**components(grid, material), "diagonalTwoByTwo": diagonal_pairs(grid, material),
                                        "maximumStraightContactRun": straight_contact_runs(grid, material)}
    return result


def parse_patch(text: str) -> tuple[str, tuple[int, int, int, int]]:
    try:
        name, raw = text.split(":", 1); values = tuple(int(value) for value in raw.split(","))
    except ValueError as exc: raise argparse.ArgumentTypeError("patch must be NAME:minX,minZ,maxX,maxZ") from exc
    if (not name or len(values) != 4 or not 0 <= values[2]-values[0] < 256
            or not 0 <= values[3]-values[1] < 256):
        raise argparse.ArgumentTypeError("invalid patch bounds")
    return name, values


def analyse(world_path: Path, patches: list[tuple[str, tuple[int, int, int, int]]], materials: set[str], min_y: int, max_y: int, compare: Path | None = None) -> dict:
    worlds = [("candidate", SavedWorld(world_path))] + ([("control", SavedWorld(compare))] if compare else [])
    result = {"kind": "saved_chunk_material_measurement", "visualAcceptance": "not_assessed",
              "materials": sorted(materials), "scope": "read-only saved Anvil blocks; missing/unclassified columns remain explicit"}
    for label, world in worlds:
        result[label] = {name: scan_patch(world, name, bounds, materials, min_y, max_y) for name, bounds in patches}
        result[label + "SourceRegionsStable"] = world.stable()
        result[label + "Source"] = {"path": str(world.world.resolve()), "regionSha256": world.hashes}
        result[label + "MissingChunks"] = sorted([list(item) for item in world.missing_chunks])
        result[label + "NonFullChunks"] = {f"{x},{z}": status for (x, z), status in sorted(world.non_full_chunks.items())}
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True, help="candidate saved world (read-only)")
    parser.add_argument("--compare", type=Path, help="optional control saved world (read-only)")
    parser.add_argument("--patch", action="append", type=parse_patch, required=True, help="NAME:minX,minZ,maxX,maxZ; repeatable")
    parser.add_argument("--materials", default=",".join(DEFAULT_MATERIALS), help="comma-separated block IDs")
    parser.add_argument("--min-y", type=int, default=-64); parser.add_argument("--max-y", type=int, default=319)
    parser.add_argument("--output", type=Path, required=True, help="report JSON outside the worlds")
    args = parser.parse_args()
    if not 0 <= args.max_y - args.min_y < 1024: parser.error("choose a bounded vertical interval (1..1024)")
    if len(args.patch) > 16 or len(dict(args.patch)) != len(args.patch): parser.error("choose at most 16 uniquely named patches")
    if args.output.resolve().is_relative_to(args.world.resolve()) or args.compare and args.output.resolve().is_relative_to(args.compare.resolve()):
        parser.error("--output must be outside supplied world directories")
    materials = {entry.strip() for entry in args.materials.split(",") if entry.strip()}
    if not materials: parser.error("--materials must contain a block ID")
    report = analyse(args.world, args.patch, materials, args.min_y, args.max_y, args.compare)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), "patches": [name for name, _ in args.patch], "visualAcceptance": "not_assessed"}))


if __name__ == "__main__": main()
