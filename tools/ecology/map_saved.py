"""Render observed saved chunk surfaces and biome IDs without loading Minecraft.

Vegetation is skipped to expose ground materials. Unknown solid/decorative blocks
remain visible as gray; this is a diagnostic map, not the game's map renderer.
"""
from __future__ import annotations

import argparse
from collections import Counter
import gzip
import hashlib
import json
from pathlib import Path
import runpy
import struct
import zlib

ROOT = Path(__file__).resolve().parents[2]
nbt_fields = runpy.run_path(str(ROOT / "tools/river_planner/prepare-playtest.py"))["nbt_fields"]

COLORS = {
    "minecraft:grass_block": (105, 155, 72), "minecraft:coarse_dirt": (121, 85, 58),
    "minecraft:dirt": (130, 94, 66), "minecraft:podzol": (91, 65, 43),
    "minecraft:sand": (221, 207, 135), "minecraft:water": (55, 113, 159),
    "minecraft:stone": (128, 128, 128), "minecraft:gravel": (154, 149, 141),
    "immersive_weathering:grassy_earthen_clay": (156, 143, 89),
    "immersive_weathering:grassy_sandy_dirt": (172, 172, 104),
    "immersive_weathering:earthen_clay": (163, 103, 69),
    "immersive_weathering:sandy_dirt": (186, 158, 100),
}
IGNORED = {
    "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:short_grass",
    "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern", "minecraft:dead_bush",
    "minecraft:snow", "minecraft:poppy", "minecraft:dandelion", "minecraft:seagrass",
    "minecraft:tall_seagrass", "minecraft:vine",
}


def packed_values(raw: bytes, field, palette_size: int, count: int, min_bits: int) -> list[int]:
    if palette_size < 1:
        raise ValueError("Empty palette")
    if field is None:
        if palette_size != 1:
            raise ValueError("Missing packed values for multi-entry palette")
        return [0] * count
    kind, start, end, _ = field
    if kind != 12:
        raise ValueError("Expected NBT long array")
    bits = max(min_bits, (palette_size - 1).bit_length())
    per = 64 // bits
    length = struct.unpack_from(">i", raw, start)[0]
    if length != (count + per - 1) // per or end - start != 4 + length * 8:
        raise ValueError("Invalid packed value count")
    words = struct.unpack_from(">" + "Q" * length, raw, start + 4)
    values = [(words[i // per] >> ((i % per) * bits)) & ((1 << bits) - 1) for i in range(count)]
    if any(value >= palette_size for value in values):
        raise ValueError("Palette index out of range")
    return values


def chunk_columns(raw: bytes) -> dict[int, tuple[str, str, int]]:
    fields = nbt_fields(raw)
    if fields.get(("Status",), (0, 0, 0, ""))[3] != "minecraft:full":
        return {}
    sections = []
    for key, value in fields.items():
        if len(key) != 3 or key[0] != "sections" or key[2] != "Y":
            continue
        prefix = key[:2]
        names = {int(k[4]): v[3] for k, v in fields.items()
                 if len(k) == 6 and k[:4] == prefix + ("block_states", "palette") and k[5] == "Name"}
        if not names or all(ignored(name) for name in names.values()):
            continue
        if set(names) != set(range(len(names))):
            raise ValueError("Noncontiguous block palette")
        values = packed_values(raw, fields.get(prefix + ("block_states", "data")), len(names), 4096, 4)
        biomes = {int(k[4]): v[3] for k, v in fields.items()
                  if len(k) == 5 and k[:4] == prefix + ("biomes", "palette")}
        if not biomes or set(biomes) != set(range(len(biomes))):
            raise ValueError("Missing or noncontiguous biome palette")
        biome_values = packed_values(raw, fields.get(prefix + ("biomes", "data")), len(biomes), 64, 1)
        sections.append((int(value[3]), names, values, biomes, biome_values))
    columns = {}
    for sy, names, values, biomes, biome_values in sorted(sections, key=lambda s: s[0], reverse=True):
        for col in range(256):
            if col in columns:
                continue
            for ly in range(15, -1, -1):
                name = names[values[ly * 256 + col]]
                if ignored(name):
                    continue
                quart = (ly // 4) * 16 + ((col // 16) // 4) * 4 + ((col % 16) // 4)
                columns[col] = (name, biomes[biome_values[quart]], sy * 16 + ly)
                break
        if len(columns) == 256:
            break
    return columns


def ignored(name: str) -> bool:
    return name in IGNORED or name.startswith("greeneries:") or name.endswith("_leaves")


def render(world: Path, output: Path, x: int, z: int, radius: int) -> dict:
    from PIL import Image, ImageDraw
    world, output = world.resolve(), output.resolve()
    if output.is_relative_to(world):
        raise ValueError("Output must be outside the saved world")
    if not (world / "level.dat").is_file():
        raise ValueError("Expected saved world with level.dat")
    if not 1 <= radius <= 32:
        raise ValueError("Chunk radius must be 1..32")
    output.mkdir(parents=True, exist_ok=True)
    width = radius * 32
    surfaces = Image.new("RGB", (width, width), "black")
    biome_image = Image.new("RGB", (width, width), "black")
    source_hashes, regions = {}, {}
    counts, biome_counts = Counter(), Counter()
    min_cx, min_cz = x // 16 - radius, z // 16 - radius
    full = 0
    for cz in range(min_cz, min_cz + 2 * radius):
        for cx in range(min_cx, min_cx + 2 * radius):
            key = cx // 32, cz // 32
            if key not in regions:
                path = world / "region" / f"r.{key[0]}.{key[1]}.mca"
                data = path.read_bytes() if path.exists() else b""
                regions[key] = data
                if data:
                    source_hashes[path.name] = hashlib.sha256(data).hexdigest()
            data = regions[key]
            if not data:
                continue
            if len(data) < 8192:
                raise ValueError("Truncated region header")
            index = ((cz % 32) * 32 + cx % 32) * 4
            offset = int.from_bytes(data[index:index + 3], "big") * 4096
            sectors = data[index + 3]
            if not offset:
                if sectors:
                    raise ValueError("Invalid empty chunk allocation")
                continue
            if offset < 8192 or not sectors or offset + sectors * 4096 > len(data):
                raise ValueError("Invalid chunk allocation")
            length = int.from_bytes(data[offset:offset + 4], "big")
            if length < 1 or length + 4 > sectors * 4096:
                raise ValueError("Invalid compressed chunk length")
            compression = data[offset + 4]
            if compression not in (1, 2, 3):
                raise ValueError(f"Unsupported/external chunk compression: {compression}")
            payload = data[offset + 5:offset + 4 + length]
            raw = {1: gzip.decompress, 2: zlib.decompress, 3: lambda p: p}[compression](payload)
            columns = chunk_columns(raw)
            if not columns:
                continue
            full += 1
            for col, (name, biome, height) in columns.items():
                px, pz = (cx - min_cx) * 16 + col % 16, (cz - min_cz) * 16 + col // 16
                counts[name] += 1
                biome_counts[biome] += 1
                surfaces.putpixel((px, pz), COLORS.get(name, (150, 150, 150)))
                color = tuple(70 + b % 150 for b in hashlib.sha256(biome.encode()).digest()[:3])
                biome_image.putpixel((px, pz), color)
    for name, picture in (("saved-surface", surfaces), ("saved-biomes", biome_image)):
        canvas = Image.new("RGB", (width * 2 + 430, max(600, width * 2 + 100)), "white")
        draw = ImageDraw.Draw(canvas)
        draw.text((12, 12), f"OBSERVED SAVED CHUNKS - {name}", fill="black")
        draw.text((12, 32), f"X {min_cx * 16}..{(min_cx + radius * 2) * 16 - 1}, Z {min_cz * 16}..{(min_cz + radius * 2) * 16 - 1}; north up", fill="black")
        draw.text((12, 52), "Vegetation skipped; unknown blocks gray; unloaded chunks black. No game launched.", fill="black")
        canvas.paste(picture.resize((width * 2, width * 2), Image.Resampling.NEAREST), (0, 90))
        entries = counts.most_common(14) if name == "saved-surface" else biome_counts.most_common(14)
        legend_x = width * 2 + 12
        draw.text((legend_x, 92), "Observed columns (diagnostic colors)", fill="black")
        for index, (entry, count) in enumerate(entries):
            color = COLORS.get(entry, (150, 150, 150)) if name == "saved-surface" else tuple(
                70 + b % 150 for b in hashlib.sha256(entry.encode()).digest()[:3])
            ly = 118 + index * 30
            draw.rectangle((legend_x, ly, legend_x + 12, ly + 12), fill=color)
            draw.text((legend_x + 18, ly), entry, fill="black")
            draw.text((legend_x + 18, ly + 12), f"{count:,} ({count / max(1, sum(counts.values())):.1%})", fill="black")
        canvas.save(output / f"{name}.png")
    unchanged = all(hashlib.sha256((world / "region" / name).read_bytes()).hexdigest() == digest
                    for name, digest in source_hashes.items())
    report = {"kind": "observed_saved_chunks", "world": world.name, "full_chunks": full,
              "columns": sum(counts.values()), "blocks": dict(counts.most_common()),
              "biomes_at_observed_surface": dict(biome_counts.most_common()),
              "region_sha256": source_hashes, "source_regions_unchanged": unchanged,
              "surface_palette": COLORS, "biome_palette": {
                  biome: [70 + b % 150 for b in hashlib.sha256(biome.encode()).digest()[:3]]
                  for biome in biome_counts},
              "limitations": "Observed pre-update blocks, vegetation skipped; not a Minecraft render or regenerated result."}
    (output / "saved-report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if not unchanged:
        raise RuntimeError("Source changed during scan; result is not a stable snapshot")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/ecology-audit/saved")
    parser.add_argument("--x", type=int, default=5572)
    parser.add_argument("--z", type=int, default=1208)
    parser.add_argument("--radius", type=int, default=8, help="Chunk radius; 8 reads 256 chunks")
    args = parser.parse_args()
    result = render(args.world, args.output, args.x, args.z, args.radius)
    print(json.dumps({key: result[key] for key in ("full_chunks", "columns", "source_regions_unchanged")}, indent=2))
