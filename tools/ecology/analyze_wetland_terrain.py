"""Read-only physical terrain metrics for saved freshwater and mangrove wetlands.

The scanner only accepts saved ``minecraft:full`` chunks.  It measures blocks
after normal generation and settling; it does not infer feature ownership or
claim that a window-wide component extends beyond the selected bounds.
"""
from __future__ import annotations

import argparse
from collections import Counter, deque
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "ecology"))
from compare_saved_vegetation import WorldReader, is_ground_plant  # noqa: E402

DEFAULT_WINDOWS = {
    "mangrove": (17504, 17759, 3264, 3519),
    "freshwater": (17248, 17503, 3120, 3375),
}
AIR = frozenset(("minecraft:air", "minecraft:cave_air", "minecraft:void_air"))
WATER = "minecraft:water"
WETLAND_BIOMES = ("terrain-diffusion-mc:ecology/freshwater_swamp",
                  "terrain-diffusion-mc:ecology/mangrove_swamp")
NATURAL_SOLID = frozenset(("minecraft:dirt", "minecraft:coarse_dirt", "minecraft:podzol",
                           "minecraft:rooted_dirt", "minecraft:grass_block", "minecraft:mud",
                           "minecraft:clay", "minecraft:sand", "minecraft:red_sand",
                           "minecraft:gravel", "minecraft:stone", "minecraft:deepslate"))
AQUATIC_COVER = frozenset(("minecraft:lily_pad", "minecraft:seagrass", "minecraft:tall_seagrass"))


@dataclass(frozen=True)
class Column:
    kind: str
    surface_y: int | None
    ground_y: int | None
    water_depth: int
    support: str | None
    biome: str


def _cover(block: str) -> bool:
    # Keep this deliberately narrow.  ``is_ground_plant`` owns the reviewed
    # vanilla + Greeneries plant list; arbitrary mod blocks stay visible rather
    # than being silently treated as non-solid ground.
    return block in AQUATIC_COVER or is_ground_plant(block) or block.endswith("_leaves") or block.endswith("_log") or block.endswith("_wood")


def observe(chunk, x: int, z: int) -> Column:
    """Describe the visible water surface or natural solid surface in one column."""
    top = chunk.max_y
    for y in range(top, chunk.min_y - 1, -1):
        block = chunk.block(x, y, z)
        if block in AIR or _cover(block):
            continue
        if block == WATER:
            # A surface-water column cannot have a solid above its first water cell.
            if any(chunk.block(x, above, z) not in AIR and not _cover(chunk.block(x, above, z))
                   for above in range(y + 1, top + 1)):
                continue
            depth = 1
            while chunk.block(x, y - depth, z) == WATER:
                depth += 1
            below = chunk.block(x, y - depth, z)
            support = "unsupported_air" if below in AIR else ("natural_solid" if below in NATURAL_SOLID
                       else "unknown_solid")
            return Column("water", y, y - depth, depth, support,
                          chunk.biome(x, y, z))
        return Column("land", y, y, 0, None, chunk.biome(x, y, z))
    return Column("missing_surface", None, None, 0, None, "")


def _components(mask: set[tuple[int, int]], bounds: tuple[int, int, int, int]) -> list[dict]:
    x0, x1, z0, z1 = bounds
    unseen, result = set(mask), []
    while unseen:
        seed = unseen.pop(); queue = deque((seed,)); cells = {seed}
        while queue:
            x, z = queue.popleft()
            for nxt in ((x - 1, z), (x + 1, z), (x, z - 1), (x, z + 1)):
                if nxt in unseen:
                    unseen.remove(nxt); cells.add(nxt); queue.append(nxt)
        xs, zs = zip(*cells)
        result.append({"columns": len(cells), "bboxInclusive": {"x": [min(xs), max(xs)], "z": [min(zs), max(zs)]},
                       "windowBoundaryClipped": any(x in (x0, x1) or z in (z0, z1) for x, z in cells)})
    return sorted(result, key=lambda row: (-row["columns"], row["bboxInclusive"]["z"], row["bboxInclusive"]["x"]))


def _step_metrics(grid: dict[tuple[int, int], Column], abrupt: int = 3) -> dict:
    groups = {"chunkBoundary": [], "interior": []}
    for (x, z), current in grid.items():
        if current.ground_y is None:
            continue
        for dx, dz in ((1, 0), (0, 1)):
            other = grid.get((x + dx, z + dz))
            if other is None or other.ground_y is None:
                continue
            delta = abs(current.ground_y - other.ground_y)
            boundary = (dx and (x + 1) % 16 == 0) or (dz and (z + 1) % 16 == 0)
            groups["chunkBoundary" if boundary else "interior"].append(delta)
    def summarise(values: list[int]) -> dict:
        return {"pairs": len(values), "meanAbsoluteStep": sum(values) / len(values) if values else None,
                "maxAbsoluteStep": max(values, default=None), "abruptStepsAtLeast3": sum(v >= abrupt for v in values)}
    return {name: summarise(values) for name, values in groups.items()} | {"abruptStepThresholdBlocks": abrupt}


def analyse_grid(grid: dict[tuple[int, int], Column], bounds: tuple[int, int, int, int]) -> dict:
    water = {point for point, value in grid.items() if value.kind == "water"}
    dry = {point for point, value in grid.items() if value.kind == "land"}
    support = Counter(grid[point].support for point in water)
    unsupported = [point for point in water if grid[point].support == "unsupported_air"]
    exposed = 0
    for x, z in water:
        y = grid[(x, z)].surface_y
        exposed += sum(1 for point in ((x - 1, z), (x + 1, z), (x, z - 1), (x, z + 1))
                       if point in grid and grid[point].kind != "water" and grid[point].surface_y is not None
                       and grid[point].surface_y < y)
    depths = Counter(grid[point].water_depth for point in water)
    islands = [row for row in _components(dry, bounds) if not row["windowBoundaryClipped"]]
    return {"observedColumns": len(grid), "waterColumns": len(water), "dryLandColumns": len(dry),
            "missingSurfaceColumns": sum(value.kind == "missing_surface" for value in grid.values()),
            "waterDepthColumns": {str(depth): count for depth, count in sorted(depths.items())},
            "depthOneOrTwoColumns": sum(count for depth, count in depths.items() if depth in (1, 2)),
            "waterComponents": _components(water, bounds), "enclosedDryIslandComponents": islands,
            "waterSupport": dict(sorted((key or "unknown", value) for key, value in support.items())),
            "unsupportedSurfaceWaterColumns": len(unsupported), "unsupportedSurfaceWaterSamples": [list(point) for point in unsupported[:20]],
            "waterToLowerLandEdges": exposed,
            "waterToLowerLandEdgesScope": "Observed cardinal water-to-lower-land edges; descriptive bank/leak review signal, not proof of a fluid leak.",
            "groundStepMetrics": _step_metrics(grid)}


def _water_by_biome(grid: dict[tuple[int, int], Column], bounds: tuple[int, int, int, int]) -> dict:
    """Keep ocean water out of the two wetland-biome acceptance measurements."""
    names = sorted({column.biome for column in grid.values() if column.biome} | set(WETLAND_BIOMES))
    result = {}
    for biome in names:
        columns = {point for point, column in grid.items() if column.biome == biome}
        water = {point for point in columns if grid[point].kind == "water"}
        result[biome] = {"observedColumns": len(columns), "waterColumns": len(water),
                         "waterFraction": len(water) / len(columns) if columns else None,
                         "waterComponents": _components(water, bounds)}
    return result


def _save_terrain_map(name: str, grid: dict[tuple[int, int], Column], bounds: tuple[int, int, int, int], output: Path) -> str:
    """Render measured saved columns: gray natural bed, blue water, cyan TD-wetland water."""
    from PIL import Image, ImageDraw
    x0, x1, z0, z1 = bounds; width, height = x1 - x0 + 1, z1 - z0 + 1
    heights = [column.ground_y for column in grid.values() if column.ground_y is not None]
    low, high = min(heights, default=0), max(heights, default=1)
    image = Image.new("RGB", (width, height), (0, 0, 0))
    for (x, z), column in grid.items():
        if column.ground_y is None:
            color = (0, 0, 0)
        else:
            shade = 45 + round(180 * (column.ground_y - low) / max(1, high - low))
            color = (shade, shade, shade)
            if column.kind == "water":
                color = (30, 190, 220) if column.biome in WETLAND_BIOMES else (32, 83, 170)
        image.putpixel((x - x0, z - z0), color)
    scale = 3
    canvas = Image.new("RGB", (width * scale + 300, height * scale + 82), "white")
    canvas.paste(image.resize((width * scale, height * scale), Image.Resampling.NEAREST), (0, 82))
    draw = ImageDraw.Draw(canvas)
    draw.text((8, 8), f"Saved FULL blocks: {name}; X {x0}..{x1}, Z {z0}..{z1}; north is up", fill="black")
    draw.text((8, 28), f"Gray = observed natural-bed/land elevation Y {low}..{high}; dark blue = water outside TD wetland biome; cyan = water in TD freshwater/mangrove biome", fill="black")
    draw.text((8, 48), "16-block grid lines are not drawn; map pixels are actual block columns.", fill="black")
    path = output / f"{name}-saved-terrain.png"
    canvas.save(path)
    return path.name


def _hash(path: Path) -> str:
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def analyse_window(reader: WorldReader, name: str, bounds: tuple[int, int, int, int], output: Path) -> dict:
    x0, x1, z0, z1 = bounds
    expected = {(chunk_x, chunk_z) for chunk_x in range(x0 // 16, x1 // 16 + 1)
                for chunk_z in range(z0 // 16, z1 // 16 + 1)}
    chunks = {key: reader.chunk(*key) for key in expected}
    full = {key for key, chunk in chunks.items() if chunk is not None}
    grid = {}
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            chunk = chunks[(x // 16, z // 16)]
            if chunk is not None:
                grid[(x, z)] = observe(chunk, x & 15, z & 15)
    return {"boundsInclusive": {"x": [x0, x1], "z": [z0, z1]}, "expectedFullChunks": len(expected),
            "fullChunks": len(full), "missingOrNonFullChunks": [list(key) for key in sorted(expected - full)],
            **analyse_grid(grid, bounds), "waterByBiome": _water_by_biome(grid, bounds),
            "wetlandBiomeWaterScope": "Only saved columns carrying each TD wetland biome; this excludes water in other biomes such as ocean from wetland acceptance.",
            "savedTerrainMap": _save_terrain_map(name, grid, bounds, output)}


def _parse_window(raw: str) -> tuple[str, tuple[int, int, int, int]]:
    name, *values = raw.split(",")
    if not name or len(values) != 4:
        raise ValueError("--window must be NAME,X0,X1,Z0,Z1")
    x0, x1, z0, z1 = map(int, values)
    if x1 < x0 or z1 < z0:
        raise ValueError("window maximum must not be smaller than minimum")
    return name, (x0, x1, z0, z1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--window", action="append", default=[], metavar="NAME,X0,X1,Z0,Z1")
    parser.add_argument("--require-full", action="store_true")
    args = parser.parse_args()
    world, output = args.world.resolve(), args.output.resolve()
    if not (world / "level.dat").is_file():
        raise ValueError("--world must contain level.dat")
    if output.is_relative_to(world):
        raise ValueError("--output must be outside --world")
    windows = dict(_parse_window(value) for value in args.window) if args.window else DEFAULT_WINDOWS
    metadata = [world / "level.dat", world / "data" / "terrain_diffusion_world_settings.dat"]
    before = {str(path.relative_to(world)): _hash(path) for path in metadata if path.is_file()}
    output.mkdir(parents=True, exist_ok=True)
    reader = WorldReader(world)
    result = {name: analyse_window(reader, name, bounds, output) for name, bounds in windows.items()}
    after_regions = {str(path.relative_to(world)): _hash(path) for path in reader.hashes}
    if {str(path.relative_to(world)): digest for path, digest in reader.hashes.items()} != after_regions or before != {str(path.relative_to(world)): _hash(path) for path in metadata if path.is_file()}:
        raise RuntimeError("source saved-world file changed during read-only measurement")
    if args.require_full and any(row["fullChunks"] != row["expectedFullChunks"] for row in result.values()):
        raise RuntimeError("--require-full: at least one requested window has a missing/non-FULL chunk")
    report = {"scope": "Saved FULL-chunk physical terrain after generation. Water writer ownership and landscape quality beyond the window are not inferred.",
              "world": str(world), "sourceWorldMetadataSha256": before, "sourceFilesUnchanged": True,
              "accessedRegionSha256": {str(path.relative_to(world)): digest for path, digest in reader.hashes.items()}, "windows": result}
    (output / "wetland-terrain-summary.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({name: {key: row[key] for key in ("fullChunks", "waterColumns", "depthOneOrTwoColumns", "unsupportedSurfaceWaterColumns")} for name, row in result.items()}))


if __name__ == "__main__":
    main()
