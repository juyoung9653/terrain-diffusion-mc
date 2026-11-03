"""Render actual saved Minecraft block states as a geology cross-section.

This tool only decodes region NBT; it never instantiates GeologyModel.  Pass a
fixture's ``world`` directory after a native generation run, for example:
``python tools/geology/render_saved.py build/structure-density/geology-v2-s2-deep/world build/geology-saved-s2``.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path
import runpy
import struct
import zlib

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
NBT_FIELDS = runpy.run_path(str(ROOT / "tools/river_planner/prepare-playtest.py"))["nbt_fields"]
AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
GROUND = {"minecraft:stone", "minecraft:deepslate", "minecraft:dirt", "minecraft:grass_block",
          "minecraft:coarse_dirt", "minecraft:sand", "minecraft:red_sand", "minecraft:gravel",
          "minecraft:clay", "minecraft:terracotta", "minecraft:water", "minecraft:bedrock",
          "minecraft:calcite", "minecraft:tuff", "minecraft:granite", "minecraft:diorite",
          "minecraft:andesite", "minecraft:sandstone", "minecraft:red_sandstone"}
COLORS = {
    "minecraft:stone": (120, 120, 120), "minecraft:deepslate": (68, 71, 80),
    "minecraft:granite": (151, 112, 94), "minecraft:diorite": (194, 192, 191),
    "minecraft:andesite": (133, 133, 133), "minecraft:tuff": (96, 104, 103),
    "minecraft:calcite": (220, 219, 205), "minecraft:sandstone": (206, 184, 127),
    "minecraft:dirt": (128, 92, 63), "minecraft:grass_block": (104, 142, 74),
    "minecraft:coarse_dirt": (119, 86, 57), "minecraft:sand": (221, 207, 148),
    "minecraft:gravel": (133, 127, 124), "minecraft:water": (48, 105, 184),
    "minecraft:lava": (240, 88, 25), "minecraft:bedrock": (48, 48, 48),
    "minecraft:coal_ore": (62, 62, 62), "minecraft:copper_ore": (184, 111, 61),
    "minecraft:iron_ore": (202, 188, 151), "minecraft:gold_ore": (235, 181, 60),
    "minecraft:redstone_ore": (210, 40, 36), "minecraft:lapis_ore": (56, 97, 203),
    "minecraft:diamond_ore": (85, 220, 228),
}


def array_values(raw: bytes, field: tuple[int, int, int, object]) -> list[int]:
    _, start, end, _ = field
    count = struct.unpack(">i", raw[start:start + 4])[0]
    return list(struct.unpack(">" + "Q" * count, raw[start + 4:end]))


def unpack(values: list[int], bits: int, index: int) -> int:
    per = 64 // bits
    return (values[index // per] >> ((index % per) * bits)) & ((1 << bits) - 1)


def raw_chunk(world: Path, chunk_x: int, chunk_z: int) -> bytes | None:
    region = world / "region" / f"r.{chunk_x >> 5}.{chunk_z >> 5}.mca"
    if not region.exists(): return None
    data = region.read_bytes(); index = ((chunk_z & 31) * 32 + (chunk_x & 31)) * 4
    offset = int.from_bytes(data[index:index + 3], "big") * 4096
    if not offset: return None
    length = int.from_bytes(data[offset:offset + 4], "big"); compression = data[offset + 4]
    payload = data[offset + 5:offset + 4 + length]
    return gzip.decompress(payload) if compression == 1 else zlib.decompress(payload) if compression == 2 else payload


class Chunk:
    def __init__(self, raw: bytes):
        self.raw = raw; self.fields = NBT_FIELDS(raw); self.status = self.fields.get(("Status",), (0, 0, 0, ""))[3]
        self.sections: dict[int, tuple[list[str], int, list[int]]] = {}; grouped: dict[tuple[object, ...], dict[tuple[object, ...], tuple[int, int, int, object]]] = {}
        for key, value in self.fields.items():
            if len(key) >= 2 and key[0] == "sections": grouped.setdefault(key[:2], {})[key[2:]] = value
        for group in grouped.values():
            if ("Y",) not in group: continue
            section_y = group[("Y",)][3]
            palette = {int(key[-2]): value[3] for key, value in group.items()
                       if key[:2] == ("block_states", "palette") and len(key) == 4 and key[-1] == "Name"}
            if not palette: continue
            packed = group.get(("block_states", "data")); names = [palette[i] for i in range(max(palette) + 1)]
            self.sections[section_y] = names, max(4, (len(names) - 1).bit_length()), array_values(raw, packed) if packed else []

    def state(self, x: int, y: int, z: int) -> str:
        section = self.sections.get(y >> 4)
        if section is None: return "minecraft:air"
        names, bits, packed = section
        if not packed: return names[0]
        return names[unpack(packed, bits, (y & 15) * 256 + (z & 15) * 16 + (x & 15))]


def read_chunks(world: Path, min_chunk_x: int, max_chunk_x: int, min_chunk_z: int, max_chunk_z: int) -> dict[tuple[int, int], Chunk]:
    result = {}
    for region in (world / "region").glob("r.*.*.mca"):
        _, rx, rz, _ = region.name.split("."); rx, rz = int(rx), int(rz)
        data = region.read_bytes()
        for local in range(1024):
            if not int.from_bytes(data[local * 4:local * 4 + 3], "big"): continue
            cx, cz = rx * 32 + local % 32, rz * 32 + local // 32
            if not (min_chunk_x <= cx <= max_chunk_x and min_chunk_z <= cz <= max_chunk_z): continue
            offset = int.from_bytes(data[local * 4:local * 4 + 3], "big") * 4096
            length, compression = int.from_bytes(data[offset:offset + 4], "big"), data[offset + 4]
            payload = data[offset + 5:offset + 4 + length]
            raw = gzip.decompress(payload) if compression == 1 else zlib.decompress(payload) if compression == 2 else payload
            if raw is not None:
                chunk = Chunk(raw)
                if chunk.status == "minecraft:full": result[cx, cz] = chunk
    return result


def state_at(chunks: dict[tuple[int, int], Chunk], x: int, y: int, z: int) -> str:
    chunk = chunks.get((x >> 4, z >> 4))
    return chunk.state(x, y, z) if chunk else "missing:chunk"


def color(state: str) -> tuple[int, int, int]:
    if state == "missing:chunk": return (170, 0, 180)
    if state in AIR: return (20, 20, 24)
    if state in COLORS: return COLORS[state]
    if state.endswith("_ore"):
        return (235, 88, 220)
    digest = hashlib.blake2b(state.encode(), digest_size=3).digest()
    return tuple(80 + value // 2 for value in digest)


def top_ground(chunks: dict[tuple[int, int], Chunk], x: int, z: int, minimum: int, maximum: int) -> int:
    for y in range(maximum, minimum - 1, -1):
        if state_at(chunks, x, y, z) in GROUND: return y
    return minimum


def choose_z(chunks: dict[tuple[int, int], Chunk], minimum: int, maximum: int,
             min_x: int, max_x: int, min_z: int, max_z: int) -> tuple[int, int]:
    best = (0, 0)
    for z in range(min_z, max_z + 1):
        count = 0
        for x in range(min_x, max_x + 1):
            ground = top_ground(chunks, x, z, minimum, maximum)
            for y in range(minimum + 9, ground - 23):
                if state_at(chunks, x, y, z) in AIR: count += 1
        best = max(best, (count, z))
    return best[1], best[0]


def cross_section(chunks: dict[tuple[int, int], Chunk], z: int, minimum: int, maximum: int,
                  min_x: int, max_x: int, output: Path) -> None:
    scale, band_height, margin = 3, 320, 64
    rows = math.ceil((maximum - minimum + 1) / band_height)
    width = max_x - min_x + 1
    image = Image.new("RGB", (width * scale, rows * (band_height * scale + margin) + 42), "white"); draw = ImageDraw.Draw(image)
    ground = {x: top_ground(chunks, x, z, minimum, maximum) for x in range(min_x, max_x + 1)}
    for band in range(rows):
        top, bottom = maximum - band * band_height, max(minimum, maximum - (band + 1) * band_height + 1)
        origin = band * (band_height * scale + margin)
        draw.text((2, origin + 2), "actual saved blocks", fill="black")
        draw.text((2, origin + 16), f"z={z}; x={min_x}..{max_x}", fill="black")
        draw.text((2, origin + 30), f"y={top}..{bottom}; display: {scale} px/block", fill="black")
        for y in range(top, bottom - 1, -1):
            py = origin + margin + (top - y) * scale
            for x in range(min_x, max_x + 1):
                state = state_at(chunks, x, y, z)
                pixel = (255, 255, 255) if state in AIR and y > ground[x] else color(state)
                ImageDraw.Draw(image).rectangle(((x - min_x) * scale, py, (x - min_x + 1) * scale - 1, py + scale - 1), fill=pixel)
    labels = [("stone", "minecraft:stone"), ("calcite", "minecraft:calcite"), ("diorite", "minecraft:diorite"),
              ("granite", "minecraft:granite"), ("tuff", "minecraft:tuff"), ("deepslate", "minecraft:deepslate"),
              ("ore", "minecraft:diamond_ore"), ("water", "minecraft:water"), ("lava", "minecraft:lava"),
              ("bedrock", "minecraft:bedrock"), ("cave air", "minecraft:cave_air"), ("missing", "missing:chunk")]
    base = rows * (band_height * scale + margin) + 3
    for index, (label, state) in enumerate(labels):
        x = 3 + (index % 4) * 96; y = base + (index // 4) * 13
        draw.rectangle((x, y, x + 9, y + 9), fill=color(state), outline="black")
        draw.text((x + 12, y), label, fill="black")
    image.save(output)


def plan_maps(chunks: dict[tuple[int, int], Chunk], minimum: int, min_x: int, max_x: int,
              min_z: int, max_z: int, output: Path) -> None:
    ys = [round(minimum + (63 - minimum) * part) for part in (.15, .40, .65, .85)]
    width, height, scale = max_x - min_x + 1, max_z - min_z + 1, 3
    image = Image.new("RGB", (4 * width * scale, height * scale + 34), "white"); draw = ImageDraw.Draw(image)
    for index, y in enumerate(ys):
        draw.text((index * width * scale + 2, 2), f"actual top-down y={y}", fill="black")
        for z in range(min_z, max_z + 1):
            for x in range(min_x, max_x + 1):
                px, py = index * width * scale + (x - min_x) * scale, 20 + (z - min_z) * scale
                draw.rectangle((px, py, px + scale - 1, py + scale - 1), fill=color(state_at(chunks, x, y, z)))
    image.save(output)


def locate_deep_cave(chunks: dict[tuple[int, int], Chunk], z: int, minimum: int, maximum: int,
                     min_x: int, max_x: int) -> tuple[int, int] | None:
    """Return the centre of the widest vertical dry-air run in the chosen slice."""
    best: tuple[int, int, int] | None = None
    for x in range(min_x, max_x + 1):
        run_start: int | None = None
        for y in range(minimum + 9, min(maximum, -129) + 2):
            if y <= min(maximum, -129) and state_at(chunks, x, y, z) in AIR:
                if run_start is None: run_start = y
            elif run_start is not None:
                candidate = (y - run_start, x, (run_start + y - 1) // 2)
                if best is None or candidate[0] > best[0]: best = candidate
                run_start = None
    return None if best is None else (best[1], best[2])


def cave_window(chunks: dict[tuple[int, int], Chunk], z: int, cave: tuple[int, int], minimum: int, maximum: int,
                min_x: int, max_x: int, output: Path) -> None:
    centre_x, centre_y = cave; half = 96; top, bottom = min(maximum, centre_y + half), max(minimum, centre_y - half)
    pixel = 3; width = max_x - min_x + 1
    margin = 66
    image = Image.new("RGB", (width * pixel, (top - bottom + 1) * pixel + margin), "white"); draw = ImageDraw.Draw(image)
    draw.text((2, 3), "actual saved deep-cave window", fill="black")
    draw.text((2, 17), f"z={z}; x={min_x}..{max_x}", fill="black")
    draw.text((2, 31), f"cave center≈({centre_x},{centre_y},{z})", fill="black")
    draw.text((2, 45), f"y={top}..{bottom}; display: {pixel} px/block", fill="black")
    for y in range(top, bottom - 1, -1):
        py = margin + (top - y) * pixel
        for x in range(min_x, max_x + 1):
            px = (x - min_x) * pixel
            draw.rectangle((px, py, px + pixel - 1, py + pixel - 1), fill=color(state_at(chunks, x, y, z)))
    image.save(output)


def audit_bounds(world: Path) -> tuple[int, int, tuple[int, int], int]:
    audit = json.loads((world / "density-audit.json").read_text(encoding="utf-8"))
    underground = audit.get("underground")
    if not isinstance(underground, dict) or not isinstance(underground.get("minY"), int) or not isinstance(underground.get("maxY"), int):
        raise SystemExit("Provide --min-y/--max-y or a world/density-audit.json underground bounds record")
    center, radius = audit.get("centerChunk"), audit.get("radiusChunks")
    if not isinstance(center, list) or len(center) != 2 or not all(isinstance(value, int) for value in center) or not isinstance(radius, int):
        raise SystemExit("density-audit.json must include centerChunk=[x,z] and radiusChunks")
    return underground["minY"], underground["maxY"], (center[0], center[1]), radius


def main() -> None:
    parser = argparse.ArgumentParser(description="Render actual saved NBT blocks, never synthetic geology.")
    parser.add_argument("world", type=Path); parser.add_argument("output", type=Path)
    parser.add_argument("--min-y", type=int); parser.add_argument("--max-y", type=int)
    parser.add_argument("--center-chunk", type=int, nargs=2, metavar=("X", "Z")); parser.add_argument("--radius-chunks", type=int)
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    need_audit = args.min_y is None or args.max_y is None or args.center_chunk is None or args.radius_chunks is None
    audited_minimum, audited_maximum, audited_center, audited_radius = audit_bounds(args.world) if need_audit else (0, 0, (0, 0), 0)
    minimum, maximum = args.min_y if args.min_y is not None else audited_minimum, args.max_y if args.max_y is not None else audited_maximum
    center = tuple(args.center_chunk) if args.center_chunk else audited_center; radius = args.radius_chunks if args.radius_chunks is not None else audited_radius
    min_chunk_x, max_chunk_x = center[0] - radius, center[0] + radius - 1
    min_chunk_z, max_chunk_z = center[1] - radius, center[1] + radius - 1
    min_x, max_x, min_z, max_z = min_chunk_x * 16, (max_chunk_x + 1) * 16 - 1, min_chunk_z * 16, (max_chunk_z + 1) * 16 - 1
    chunks = read_chunks(args.world, min_chunk_x, max_chunk_x, min_chunk_z, max_chunk_z)
    if not chunks: raise SystemExit(f"No saved region chunks in {args.world}")
    z, dry_air = choose_z(chunks, minimum, maximum, min_x, max_x, min_z, max_z)
    cross_section(chunks, z, minimum, maximum, min_x, max_x, args.output / "saved-cross-section.png")
    plan_maps(chunks, minimum, min_x, max_x, min_z, max_z, args.output / "saved-topdown-plans.png")
    cave = locate_deep_cave(chunks, z, minimum, maximum, min_x, max_x)
    if cave is not None:
        cave_window(chunks, z, cave, minimum, maximum, min_x, max_x, args.output / "saved-deep-cave-window.png")
    metadata = {"world": str(args.world), "chunkBounds": [[min_chunk_x, max_chunk_x], [min_chunk_z, max_chunk_z]],
                "blockBounds": [[min_x, max_x], [min_z, max_z]], "yBounds": [minimum, maximum],
                "chosenZ": z, "chosenZDryAirBlocks": dry_air, "deepCaveWindowCenter": list(cave) if cave else None,
                "planY": [round(minimum + (63 - minimum) * part) for part in (.15, .40, .65, .85)]}
    (args.output / "saved-render-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(f"Rendered actual saved blocks from {args.world}: z={z}; chunks={len(chunks)}; y={minimum}..{maximum}")


if __name__ == "__main__": main()
