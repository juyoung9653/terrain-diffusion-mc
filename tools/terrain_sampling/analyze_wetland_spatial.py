#!/usr/bin/env python3
"""Summarise sampled wetland habitat windows without assigning pass/fail targets.

The input is a CSV emitted on a regular square grid.  It may contain several
named windows and additional columns; only the documented spatial/ecology
columns are consumed.  Output consists of one deterministic PNG per window and
a JSON report suitable for comparing a wetland patch with its neighbouring
base ecology labels.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from collections import Counter, defaultdict, deque
from pathlib import Path

WETLAND_IDS = frozenset((20, 21, 22, 23))
WEIGHT_COLUMNS = ("reedWeight", "swampWeight", "mangroveWeight", "riparianWeight")
REQUIRED_COLUMNS = ("window", "x", "z", "biomeId", "elevation", "wetness", "marineInfluence", *WEIGHT_COLUMNS)
COLORS = {
    20: (192, 188, 55),  # reed: yellow-green
    21: (34, 143, 142),  # freshwater: teal
    22: (128, 74, 156),  # mangrove: purple
    23: (53, 150, 78),   # riparian: green
}
BIOME_NAMES = {20: "reed_marsh", 21: "freshwater_swamp", 22: "mangrove_swamp", 23: "riparian_forest"}


def _number(row: dict[str, str], key: str) -> float:
    try:
        value = float(row[key])
    except (KeyError, ValueError) as exc:
        raise ValueError(f"invalid {key!r} at window={row.get('window')!r}, x={row.get('x')!r}, z={row.get('z')!r}") from exc
    if not math.isfinite(value):
        raise ValueError(f"non-finite {key!r} at window={row.get('window')!r}, x={row.get('x')!r}, z={row.get('z')!r}")
    return value


def _integer(row: dict[str, str], key: str) -> int:
    value = _number(row, key)
    if not value.is_integer():
        raise ValueError(f"non-integral {key!r} at window={row.get('window')!r}, x={row.get('x')!r}, z={row.get('z')!r}")
    return int(value)


def read_samples(path: Path) -> dict[str, list[dict]]:
    with path.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames is None:
            raise ValueError("CSV has no header")
        missing = [key for key in REQUIRED_COLUMNS if key not in reader.fieldnames]
        if missing:
            raise ValueError(f"CSV is missing required columns: {', '.join(missing)}")
        windows: dict[str, list[dict]] = defaultdict(list)
        for line, row in enumerate(reader, start=2):
            try:
                sample = {"x": _integer(row, "x"), "z": _integer(row, "z"),
                          "biomeId": _integer(row, "biomeId"), "elevation": _number(row, "elevation"),
                          "wetness": _number(row, "wetness"), "marineInfluence": _number(row, "marineInfluence")}
                sample.update({key: _number(row, key) for key in WEIGHT_COLUMNS})
            except ValueError as exc:
                raise ValueError(f"CSV line {line}: {exc}") from exc
            windows[row["window"]].append(sample)
    if not windows:
        raise ValueError("CSV contains no samples")
    return dict(windows)


def spacing(values: list[int], axis: str) -> int:
    distinct = sorted(set(values))
    if len(distinct) < 2:
        raise ValueError(f"window needs at least two {axis} coordinates")
    differences = [right - left for left, right in zip(distinct, distinct[1:])]
    step = min(differences)
    if step <= 0 or any(delta != step for delta in differences):
        raise ValueError(f"irregular {axis} coordinate spacing")
    return step


def color_for(biome_id: int) -> tuple[int, int, int]:
    if biome_id in COLORS:
        return COLORS[biome_id]
    # Reserve hues for the four measured wetlands so base forests cannot be
    # mistaken for purple mangroves in the area map.
    shade = 65 + (biome_id * 17) % 65
    return (shade, shade, shade)


def interior_width(cells: list[tuple[int, int]], step: int, clipped: bool) -> dict:
    """Approximate component thickness from cell-centre EDT distances.

    The false padded border prevents an array edge from looking like continued
    habitat.  A clipped component can continue outside the sampled map, so its
    value is a lower bound.  A singleton lacks an interior width measurement.
    """
    if len(cells) == 1:
        return {"maximumBlocks": None, "medianBlocks": None,
                "status": "singleton_no_interior_width"}
    try:
        from scipy.ndimage import distance_transform_edt
    except ImportError:
        return {"maximumBlocks": None, "medianBlocks": None,
                "status": "scipy_unavailable"}
    min_x, max_x = min(x for x, _ in cells), max(x for x, _ in cells)
    min_z, max_z = min(z for _, z in cells), max(z for _, z in cells)
    rows, cols = (max_z - min_z) // step + 3, (max_x - min_x) // step + 3
    mask = [[False] * cols for _ in range(rows)]
    for x, z in cells:
        mask[(z - min_z) // step + 1][(x - min_x) // step + 1] = True
    distances = distance_transform_edt(mask)
    values = [float(distances[(z - min_z) // step + 1][(x - min_x) // step + 1]) for x, z in cells]
    return {"maximumBlocks": max(values) * 2 * step,
            "medianBlocks": statistics.median(values) * 2 * step,
            "status": "lower_bound_map_clipped" if clipped else "estimate",
            "method": "padded_false_distance_transform_cell_centres_times_two",
            "gridResolutionBiasBlocks": "+/-4 (one input-cell resolution; not an exact physical width)"}


def component_metrics(grid: dict[tuple[int, int], dict], step: int) -> dict[str, list[dict]]:
    unseen = {position for position, sample in grid.items() if sample["biomeId"] in WETLAND_IDS}
    result: dict[str, list[dict]] = defaultdict(list)
    while unseen:
        start = unseen.pop()
        biome_id = grid[start]["biomeId"]
        cells, queue = [], deque((start,))
        touches_edge = False
        while queue:
            x, z = queue.popleft(); cells.append((x, z))
            for neighbor in ((x - step, z), (x + step, z), (x, z - step), (x, z + step)):
                other = grid.get(neighbor)
                if other is None:
                    touches_edge = True
                elif other["biomeId"] == biome_id and neighbor in unseen:
                    unseen.remove(neighbor); queue.append(neighbor)
        xs, zs = [cell[0] for cell in cells], [cell[1] for cell in cells]
        width = (max(xs) - min(xs)) + step
        height = (max(zs) - min(zs)) + step
        result[str(biome_id)].append({
            "biomeId": biome_id, "cells": len(cells), "areaBlocks2": len(cells) * step * step,
            "boundingBoxWidthBlocks": width, "boundingBoxHeightBlocks": height,
            # This is descriptive geometry only; use approximateInteriorWidth
            # below for thickness of a curved or branched patch.
            "minimumBoundingBoxSpanBlocks": min(width, height),
            "mapBoundaryClipped": touches_edge,
            "approximateInteriorWidth": interior_width(cells, step, touches_edge),
        })
    for entries in result.values():
        entries.sort(key=lambda entry: (-entry["cells"], entry["biomeId"]))
    return dict(result)


def boundary_metrics(grid: dict[tuple[int, int], dict], step: int) -> dict[str, dict]:
    values: dict[str, list[dict[str, float]]] = defaultdict(list)
    for (x, z), left in grid.items():
        for neighbor in ((x + step, z), (x, z + step)):
            right = grid.get(neighbor)
            if right is None or right["biomeId"] == left["biomeId"]:
                continue
            pair = f"{min(left['biomeId'], right['biomeId'])}:{max(left['biomeId'], right['biomeId'])}"
            values[pair].append({key: abs(left[key] - right[key]) for key in (*WEIGHT_COLUMNS, "wetness", "marineInfluence")})
    result = {}
    for pair, rows in sorted(values.items()):
        result[pair] = {"edges": len(rows), "meanAbsoluteDifference": {key: sum(row[key] for row in rows) / len(rows) for key in rows[0]},
                        "maxAbsoluteDifference": {key: max(row[key] for row in rows) for key in rows[0]}}
    return result


def adjacent_wetness_metrics(grid: dict[tuple[int, int], dict], step: int) -> dict:
    # Include same-label neighbors: fragmented habitat can hide within one biome.
    differences = sorted(abs(left["wetness"] - grid[neighbor]["wetness"])
                         for (x, z), left in grid.items()
                         for neighbor in ((x + step, z), (x, z + step))
                         if neighbor in grid)
    return {"edges": len(differences), "quantileMethod": "nearest_rank",
            "p90AbsoluteDifference": differences[math.ceil(.9 * len(differences)) - 1],
            "maxAbsoluteDifference": differences[-1]}


def render_png(name: str, grid: dict[tuple[int, int], dict], xs: list[int], zs: list[int], step: int, path: Path) -> bool:
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        # The numerical report deliberately has no plotting dependency.
        return False
    map_image = Image.new("RGB", (len(xs), len(zs)), (20, 20, 20))
    pixels = map_image.load()
    for row, z in enumerate(zs):
        for col, x in enumerate(xs):
            sample = grid.get((x, z))
            if sample is not None:
                pixels[col, row] = color_for(sample["biomeId"])
    # Each input cell is a square of `step` blocks; nearest neighbour preserves labels.
    map_image = map_image.resize((len(xs) * 4, len(zs) * 4), Image.Resampling.NEAREST)
    legend_height = 94
    image = Image.new("RGB", (max(map_image.width, 480), map_image.height + legend_height), (242, 242, 242))
    image.paste(map_image, (0, 0))
    draw = ImageDraw.Draw(image)
    draw.text((6, map_image.height + 5),
              f"{name}  origin=({min(xs)},{min(zs)})  resolution={step} blocks/cell", fill=(20, 20, 20))
    for index, biome_id in enumerate(sorted(WETLAND_IDS)):
        x = 6 + (index % 2) * 235; y = map_image.height + 26 + (index // 2) * 29
        draw.rectangle((x, y, x + 17, y + 17), fill=color_for(biome_id))
        draw.text((x + 23, y + 2), f"{biome_id} {BIOME_NAMES[biome_id]}", fill=(20, 20, 20))
    draw.text((6, map_image.height + 84), "Other biome IDs use stable muted colours; black cells are outside the sampled grid.", fill=(45, 45, 45))
    image.save(path)
    return True


def analyse_window(name: str, samples: list[dict], output: Path) -> dict:
    xs, zs = sorted({sample["x"] for sample in samples}), sorted({sample["z"] for sample in samples})
    step_x, step_z = spacing(xs, "x"), spacing(zs, "z")
    if step_x != step_z:
        raise ValueError(f"window {name!r} has non-square grid steps ({step_x}, {step_z})")
    step = step_x
    if step != 4:
        raise ValueError(f"window {name!r} must use the CSV contract's 4-block grid, got {step}")
    grid = {(sample["x"], sample["z"]): sample for sample in samples}
    if len(grid) != len(samples):
        raise ValueError(f"window {name!r} has duplicate x,z samples")
    expected = {(x, z) for x in xs for z in zs}
    if set(grid) != expected:
        raise ValueError(f"window {name!r} is not a complete square-cell grid")
    total, land = len(samples), [sample for sample in samples if sample["elevation"] >= 0]
    ids = sorted({sample["biomeId"] for sample in samples})
    overall, land_counts = Counter(sample["biomeId"] for sample in samples), Counter(sample["biomeId"] for sample in land)
    png_written = render_png(name, grid, xs, zs, step, output / f"{name}.png")
    components = component_metrics(grid, step)
    return {
        "sampleCount": total, "landSampleCount": len(land), "gridStepBlocks": step,
        "extentBlocks": {"x": [min(xs), max(xs) + step], "z": [min(zs), max(zs) + step]},
        "biomeFractions": {str(identifier): {"all": overall[identifier] / total,
                            "land": land_counts[identifier] / len(land) if land else None}
                           for identifier in ids},
        "wetlandFraction": {"all": sum(overall[item] for item in WETLAND_IDS) / total,
                            "land": sum(land_counts[item] for item in WETLAND_IDS) / len(land) if land else None},
        "componentInteriorWidthMetadata": {
            "method": "scipy.ndimage.distance_transform_edt on a false-padded component mask",
            "units": "blocks", "cellCentreDiameterApproximation": True,
            "gridResolutionBiasBlocks": "+/-4; this is not an exact physical width",
            "clippedComponentInterpretation": "lower_bound", "singletonInterpretation": "null",
        },
        "components": components,
        "fragmentation": {
            str(identifier): {
                "cells": overall[identifier],
                "componentCount": len(components.get(str(identifier), [])),
                "largestAreaBlocks2": max((item["areaBlocks2"] for item in
                                          components.get(str(identifier), [])), default=0),
                "isolatedCellFraction": (sum(item["cells"] == 1 for item in
                                              components.get(str(identifier), [])) / overall[identifier]
                                         if overall[identifier] else None),
            } for identifier in sorted(WETLAND_IDS)
        },
        "adjacentWetness": adjacent_wetness_metrics(grid, step),
        "boundaryContinuity": boundary_metrics(grid, step),
        "map": f"{name}.png" if png_written else None,
    }


def analyse(path: Path, output: Path) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    report = {"input": str(path), "wetlandBiomeIds": sorted(WETLAND_IDS), "windows": {},
              "qualityAcceptance": "review_required",
              "scope": "local sampled habitat; not global frequency or rendered scenery"}
    for name, samples in sorted(read_samples(path).items()):
        report["windows"][name] = analyse_window(name, samples, output)
    (output / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path, help="sample CSV")
    parser.add_argument("--output", type=Path, required=True, help="new or existing report directory")
    args = parser.parse_args()
    report = analyse(args.csv, args.output)
    print(json.dumps({"windows": sorted(report["windows"]), "summary": str(args.output / "summary.json")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
