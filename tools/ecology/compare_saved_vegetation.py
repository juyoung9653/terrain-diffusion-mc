"""Compare observed ground-plant coverage in small saved-world samples.

The scanner is read-only. It counts a column only when the highest terrain
block is grass_block and everything above it is air or a recognized ground
plant. This keeps water, snow, trees, structures, and covered forest floor out
of the denominator. Saved worlds from different seeds are descriptive samples,
not a causal world-generation comparison.
"""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import gzip
import hashlib
import json
from pathlib import Path
import zlib

try:
    from .map_saved import nbt_fields, packed_values
except ImportError:  # Direct script execution.
    from map_saved import nbt_fields, packed_values


AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
VANILLA_GROUND_PLANTS = {
    "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern",
    "minecraft:large_fern", "minecraft:dead_bush", "minecraft:sugar_cane",
    "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
    "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip",
    "minecraft:orange_tulip", "minecraft:white_tulip", "minecraft:pink_tulip",
    "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:lily_of_the_valley",
    "minecraft:torchflower", "minecraft:sunflower", "minecraft:lilac",
    "minecraft:rose_bush", "minecraft:peony", "minecraft:brown_mushroom",
    "minecraft:red_mushroom", "minecraft:sweet_berry_bush", "minecraft:bush",
}
ROOT = Path(__file__).resolve().parents[2]


def load_block_tag(path: str) -> frozenset[str]:
    source = ROOT / "common/src/main/resources/data/terrain-diffusion-mc/tags/block" / path
    values = json.loads(source.read_text(encoding="utf-8"))["values"]
    return frozenset(value if isinstance(value, str) else value["id"] for value in values)


GRASS_SUPPORTS = load_block_tag("greeneries_grass_soils.json")


def is_ground_plant(name: str) -> bool:
    """Return blocks that can occupy the cell immediately above grass soil."""
    return name.startswith("greeneries:") or name in VANILLA_GROUND_PLANTS


@dataclass(frozen=True)
class Section:
    blocks: tuple[str, ...]
    block_values: tuple[int, ...]
    biomes: tuple[str, ...]
    biome_values: tuple[int, ...]


@dataclass(frozen=True)
class Chunk:
    sections: dict[int, Section]

    @property
    def min_y(self) -> int:
        return min(self.sections, default=-4) * 16

    @property
    def max_y(self) -> int:
        return (max(self.sections, default=19) + 1) * 16 - 1

    def block(self, x: int, y: int, z: int) -> str:
        section = self.sections.get(y // 16)
        if section is None:
            return "minecraft:air"
        index = (y % 16) * 256 + z * 16 + x
        return section.blocks[section.block_values[index]]

    def biome(self, x: int, y: int, z: int) -> str:
        section = self.sections.get(y // 16)
        if section is None:
            return ""
        index = (y % 16 // 4) * 16 + (z // 4) * 4 + x // 4
        return section.biomes[section.biome_values[index]]


def decode_chunk(raw: bytes) -> Chunk | None:
    fields = nbt_fields(raw)
    if fields.get(("Status",), (0, 0, 0, ""))[3] != "minecraft:full":
        return None
    sections: dict[int, Section] = {}
    for key, y_field in fields.items():
        if len(key) != 3 or key[0] != "sections" or key[2] != "Y":
            continue
        prefix = key[:2]
        block_names = {int(k[4]): value[3] for k, value in fields.items()
                       if len(k) == 6 and k[:4] == prefix + ("block_states", "palette")
                       and k[5] == "Name"}
        biome_names = {int(k[4]): value[3] for k, value in fields.items()
                       if len(k) == 5 and k[:4] == prefix + ("biomes", "palette")}
        if not block_names or not biome_names:
            continue
        if set(block_names) != set(range(len(block_names))):
            raise ValueError("Noncontiguous block palette")
        if set(biome_names) != set(range(len(biome_names))):
            raise ValueError("Noncontiguous biome palette")
        blocks = tuple(block_names[i] for i in range(len(block_names)))
        biomes = tuple(biome_names[i] for i in range(len(biome_names)))
        block_values = tuple(packed_values(
            raw, fields.get(prefix + ("block_states", "data")), len(blocks), 4096, 4))
        biome_values = tuple(packed_values(
            raw, fields.get(prefix + ("biomes", "data")), len(biomes), 64, 1))
        sections[int(y_field[3])] = Section(blocks, block_values, biomes, biome_values)
    return Chunk(sections) if sections else None


class WorldReader:
    def __init__(self, world: Path):
        self.world = world.resolve()
        if not (self.world / "level.dat").is_file():
            raise ValueError(f"Expected saved world with level.dat: {self.world}")
        self.regions: dict[tuple[int, int], bytes] = {}
        self.hashes: dict[Path, str] = {}
        self.chunks: dict[tuple[int, int], Chunk | None] = {}

    def chunk(self, cx: int, cz: int) -> Chunk | None:
        key = cx, cz
        if key in self.chunks:
            return self.chunks[key]
        region_key = cx // 32, cz // 32
        if region_key not in self.regions:
            path = self.world / "region" / f"r.{region_key[0]}.{region_key[1]}.mca"
            data = path.read_bytes() if path.is_file() else b""
            self.regions[region_key] = data
            if data:
                self.hashes[path] = hashlib.sha256(data).hexdigest()
        data = self.regions[region_key]
        if not data:
            self.chunks[key] = None
            return None
        if len(data) < 8192:
            raise ValueError("Truncated region header")
        index = ((cz % 32) * 32 + cx % 32) * 4
        offset = int.from_bytes(data[index:index + 3], "big") * 4096
        sectors = data[index + 3]
        if not offset:
            self.chunks[key] = None
            return None
        if offset < 8192 or not sectors or offset + sectors * 4096 > len(data):
            raise ValueError("Invalid chunk allocation")
        length = int.from_bytes(data[offset:offset + 4], "big")
        if length < 1 or length + 4 > sectors * 4096:
            raise ValueError("Invalid compressed chunk length")
        compression = data[offset + 4]
        payload = data[offset + 5:offset + 4 + length]
        if compression not in (1, 2, 3):
            raise ValueError(f"Unsupported/external chunk compression: {compression}")
        raw = {1: gzip.decompress, 2: zlib.decompress, 3: lambda value: value}[compression](payload)
        self.chunks[key] = decode_chunk(raw)
        return self.chunks[key]

    def block(self, x: int, y: int, z: int) -> str:
        chunk = self.chunk(x // 16, z // 16)
        return "minecraft:air" if chunk is None else chunk.block(x % 16, y, z % 16)

    def column(self, x: int, z: int) -> tuple[int, str, str, str] | None:
        chunk = self.chunk(x // 16, z // 16)
        if chunk is None:
            return None
        plant = ""
        for y in range(chunk.max_y, chunk.min_y - 1, -1):
            block = chunk.block(x % 16, y, z % 16)
            if block in AIR:
                continue
            if is_ground_plant(block):
                plant = block
                continue
            direct = self.block(x, y + 1, z)
            if direct in AIR:
                plant = ""
            elif not is_ground_plant(direct):
                plant = ""
            return y, block, plant, chunk.biome(x % 16, y, z % 16)
        return None

    def unchanged(self) -> bool:
        return all(path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == digest
                   for path, digest in self.hashes.items())


def slope_bin(delta: int) -> str:
    if delta == 0:
        return "flat_0"
    if delta == 1:
        return "gentle_1"
    if delta <= 3:
        return "moderate_2_3"
    return "steep_4_plus"


def summarize_columns(columns: dict[tuple[int, int], tuple[int, str, str, str] | None],
                      bounds: tuple[int, int, int, int], target_biome: str) -> dict:
    min_x, max_x, min_z, max_z = bounds
    species: Counter[str] = Counter()
    surfaces: Counter[str] = Counter()
    support_bins: dict[str, Counter[str]] = {}
    slopes: dict[str, Counter[str]] = {}
    biome_columns = eligible = planted = 0
    for z in range(min_z, max_z + 1):
        for x in range(min_x, max_x + 1):
            column = columns.get((x, z))
            if column is None or column[3] != target_biome:
                continue
            biome_columns += 1
            y, support, plant, _ = column
            surfaces[support] += 1
            if support not in GRASS_SUPPORTS:
                continue
            neighbors = [columns.get((x - 1, z)), columns.get((x + 1, z)),
                         columns.get((x, z - 1)), columns.get((x, z + 1))]
            neighbors = [neighbor for neighbor in neighbors if neighbor is not None]
            if not neighbors:
                continue
            delta = max(abs(y - neighbor[0]) for neighbor in neighbors)
            name = slope_bin(delta)
            bucket = slopes.setdefault(name, Counter())
            support_bucket = support_bins.setdefault(support, Counter())
            eligible += 1
            bucket["eligible"] += 1
            support_bucket["eligible"] += 1
            if plant:
                planted += 1
                bucket["planted"] += 1
                support_bucket["planted"] += 1
                species[plant] += 1
    slope_report = {
        name: {"eligible": values["eligible"], "planted": values["planted"],
               "coverage": values["planted"] / values["eligible"]}
        for name, values in sorted(slopes.items())
    }
    support_report = {
        name: {"eligible": values["eligible"], "planted": values["planted"],
               "coverage": values["planted"] / values["eligible"]}
        for name, values in sorted(support_bins.items())
    }
    return {
        "target_biome_columns": biome_columns,
        "target_surface_blocks": dict(surfaces.most_common()),
        "eligible_exposed_grass_columns": eligible,
        "planted_columns": planted,
        "plant_coverage": planted / eligible if eligible else None,
        "species": dict(species.most_common()),
        "grass_support_bins": support_report,
        "slope_bins": slope_report,
    }


def scan_sample(label: str, world: Path, x: int, z: int, biome: str,
                chunk_radius: int = 2) -> dict:
    if chunk_radius < 0:
        raise ValueError("chunk_radius must be nonnegative")
    reader = WorldReader(world)
    center_cx, center_cz = x // 16, z // 16
    min_x, max_x = (center_cx - chunk_radius) * 16, (center_cx + chunk_radius + 1) * 16 - 1
    min_z, max_z = (center_cz - chunk_radius) * 16, (center_cz + chunk_radius + 1) * 16 - 1
    columns = {(px, pz): reader.column(px, pz)
               for pz in range(min_z, max_z + 1)
               for px in range(min_x, max_x + 1)}
    result = {
        "label": label,
        "world": reader.world.name,
        "center": [x, z],
        "chunk_bounds": [center_cx - chunk_radius, center_cz - chunk_radius,
                         center_cx + chunk_radius, center_cz + chunk_radius],
        "target_biome": biome,
        **summarize_columns(columns, (min_x, max_x, min_z, max_z), biome),
        "full_chunks_read": sum(chunk is not None for chunk in reader.chunks.values()),
        "region_sha256": {path.name: digest for path, digest in reader.hashes.items()},
        "source_regions_unchanged": reader.unchanged(),
    }
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sample", action="append", nargs=5, required=True,
                        metavar=("LABEL", "WORLD", "X", "Z", "BIOME"))
    parser.add_argument("--chunk-radius", type=int, default=2,
                        help="Radius around the center chunk; 2 reads a 5x5 sample")
    parser.add_argument("--output", type=Path,
                        help="Optional report path; the saved worlds are never written")
    args = parser.parse_args()
    samples = [scan_sample(label, Path(world), int(x), int(z), biome, args.chunk_radius)
               for label, world, x, z, biome in args.sample]
    report = {
        "kind": "observed_saved_vegetation",
        "method": "plants per exposed 100%-acceptance grass-soil column, grouped by maximum available cardinal height delta",
        "samples": samples,
        "limitations": ("Different saved worlds, seeds, and biome-scoped mod features make this "
                        "descriptive evidence, not a causal generation comparison."),
    }
    rendered = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")


if __name__ == "__main__":
    main()
