#!/usr/bin/env python3
"""Compare planned cave-stream columns with saved native chunks.

This is a saved-block audit.  It distinguishes present water and persisted river
markers from columns that the carver did not record; it cannot reconstruct the
pre-carve block which caused a column to be skipped.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter, deque
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "geology"))
from render_saved import AIR, Chunk, raw_chunk  # noqa: E402


def planned(path: Path) -> dict[tuple[int, int], dict[str, object]]:
    result: dict[tuple[int, int], dict[str, object]] = {}
    with path.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            if row["wet"] != "true":
                continue
            row = {**row, **{key: int(row[key]) for key in ("x", "z", "bed", "water", "roof")}}
            result[row["x"], row["z"]] = row
    return result


def marker_data(world: Path, chunks: set[tuple[int, int]]) -> tuple[dict[tuple[int, int], dict[str, object]], dict[tuple[int, int], int], dict[tuple[int, int], list[str]]]:
    result: dict[tuple[int, int], dict[str, object]] = {}
    counts: dict[tuple[int, int], int] = {}
    starts: dict[tuple[int, int], list[str]] = {}
    for cx, cz in chunks:
        raw = raw_chunk(world, cx, cz)
        if raw is None:
            continue
        fields = Chunk(raw).fields
        starts[cx, cz] = sorted({str(key[2]) for key in fields if len(key) >= 3 and key[:2] == ("structures", "starts")})
        prefix = ("terrain_diffusion_river_channels",)
        indices = {int(key[1]) for key in fields if len(key) >= 2 and key[:1] == prefix and str(key[1]).isdigit()}
        for index in indices:
            def value(key: str):
                field = fields.get(prefix + (str(index), key))
                return None if field is None else field[3]
            x, z = value("x"), value("z")
            if type(x) is int and type(z) is int:
                result[cx * 16 + x, cz * 16 + z] = {key: value(key) for key in ("bed", "water", "roof", "flow_x", "flow_z", "lake")}
        counts[cx, cz] = sum(1 for point in result if point[0] >> 4 == cx and point[1] >> 4 == cz)
    return result, counts, starts


def components(points: set[tuple[int, int]]) -> list[int]:
    sizes: list[int] = []
    pending = set(points)
    while pending:
        start = pending.pop(); size = 1; queue = deque([start])
        while queue:
            x, z = queue.popleft()
            for neighbor in ((x - 1, z), (x + 1, z), (x, z - 1), (x, z + 1)):
                if neighbor in pending:
                    pending.remove(neighbor); queue.append(neighbor); size += 1
        sizes.append(size)
    return sorted(sizes, reverse=True)


def audit(world: Path, csv_path: Path) -> dict[str, object]:
    rows = planned(csv_path)
    chunk_keys = {(x >> 4, z >> 4) for x, z in rows}
    chunks: dict[tuple[int, int], Chunk] = {}
    for key in chunk_keys:
        raw = raw_chunk(world, *key)
        if raw is not None:
            chunks[key] = Chunk(raw)
    marked, marker_counts, structure_starts = marker_data(world, chunk_keys)
    present: set[tuple[int, int]] = set()
    missing: list[dict[str, object]] = []
    states = Counter()
    for point, row in rows.items():
        chunk = chunks.get((point[0] >> 4, point[1] >> 4))
        if chunk is None:
            missing.append({"x": point[0], "z": point[1], "reason": "missing_full_chunk"})
            continue
        water = chunk.state(point[0], int(row["water"]), point[1])
        bed = chunk.state(point[0], int(row["bed"]), point[1])
        above = chunk.state(point[0], int(row["water"]) + 1, point[1])
        marker = marked.get(point)
        if water == "minecraft:water" and marker is not None:
            present.add(point)
            continue
        states[(water, bed, above, "marker" if marker else "unmarked")] += 1
        interval = [{"y": y, "state": chunk.state(point[0], y, point[1])}
                    for y in range(int(row["bed"]), int(row["roof"]) + 1)]
        key = point[0] >> 4, point[1] >> 4
        missing.append({"x": point[0], "z": point[1], "chunk": list(key), "waterY": row["water"], "bedY": row["bed"], "roofY": row["roof"],
                        "waterState": water, "bedState": bed, "aboveState": above,
                        "marker": marker is not None,
                        "bedIsAir": bed in AIR, "waterIsAir": water in AIR,
                        "bedThroughRoof": interval, "chunkMarkerColumns": marker_counts.get(key, 0),
                        "chunkStructureStartKeys": structure_starts.get(key, [])})
    present_components = components(present)
    planned_components = components(set(rows))
    # A missing point adjacent to a recorded point is a potential in-channel gap;
    # this says nothing about whether surrounding cave air was naturally present.
    gaps = [row for row in missing if any((row["x"] + dx, row["z"] + dz) in present
                                          for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)))]
    return {
        "world": str(world), "csv": str(csv_path), "plannedWetColumns": len(rows),
        "fullChunksRead": len(chunks), "markerColumns": len(marked),
        "markerColumnsByChunk": {f"{x},{z}": count for (x, z), count in sorted(marker_counts.items())},
        "structureStartKeysByChunk": {f"{x},{z}": value for (x, z), value in sorted(structure_starts.items()) if value},
        "actualWaterAndMarker": len(present), "missingOrUnmarked": len(missing),
        "coverage": len(present) / len(rows) if rows else 0,
        "plannedComponents": planned_components, "actualComponents": present_components,
        "potentialRecordedChannelGapColumns": len(gaps), "missingStateGroups": [
            {"count": count, "waterState": key[0], "bedState": key[1], "aboveState": key[2], "marker": key[3]}
            for key, count in states.most_common()],
        "missingColumns": missing,
        "limits": ["Final block states cannot establish the pre-carve cause of a skipped column, even where lava is present.",
                   "Empty saved structure-start keys do not prove no protected structure overlapped during generation; this audit does not decode piece bounding boxes.",
                   "Four-neighbor components are a conservative raster connectivity measure; diagonal water contact is not joined."],
    }


def plot_scale6(result: dict[str, object], output: Path) -> None:
    rows = result["missingColumns"]
    # Include all planned columns from the CSV, then overlay confirmed saved state.
    planned_rows = planned(Path(result["csv"]))
    xs, zs = zip(*planned_rows)
    low_x, high_x, low_z, high_z = min(xs), max(xs), min(zs), max(zs)
    scale, header = 8, 44
    image = Image.new("RGB", ((high_x - low_x + 1) * scale, (high_z - low_z + 1) * scale + header), "white")
    draw = ImageDraw.Draw(image)
    # Dark blue means water plus persisted marker; pale gray is planned but absent.
    actual = set(planned_rows) - {(row["x"], row["z"]) for row in rows}
    for x, z in planned_rows:
        color = (49, 115, 190) if (x, z) in actual else (205, 205, 205)
        px, py = (x - low_x) * scale, header + (z - low_z) * scale
        draw.rectangle((px, py, px + scale - 1, py + scale - 1), fill=color)
    # Orange: lava at planned bed; red: lava at planned water level; purple: other missing state.
    for row in rows:
        color = (238, 132, 35) if row["bedState"] == "minecraft:lava" else (142, 50, 170)
        if row["waterState"] == "minecraft:lava": color = (220, 50, 35)
        px, py = (row["x"] - low_x) * scale, header + (row["z"] - low_z) * scale
        draw.rectangle((px, py, px + scale - 1, py + scale - 1), fill=color)
    draw.text((3, 3), "S6 planned cave stream: blue=recorded water, gray=missing, orange=lava bed, red=lava water", fill="black")
    draw.text((3, 19), f"x={low_x}..{high_x}; z={low_z}..{high_z}; saved-state audit only", fill="black")
    image.save(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--s2-world", type=Path, required=True)
    parser.add_argument("--s2-csv", type=Path, required=True)
    parser.add_argument("--s6-world", type=Path, required=True)
    parser.add_argument("--s6-csv", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/underground-v3-saved-audit.json")
    args = parser.parse_args()
    result = {"scale2": audit(args.s2_world, args.s2_csv), "scale6": audit(args.s6_world, args.s6_csv)}
    plot = args.output.with_name(args.output.stem + "-s6-map.png")
    plot_scale6(result["scale6"], plot)
    result["scale6"]["savedStateMap"] = str(plot)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: {sub: result[key][sub] for sub in ("plannedWetColumns", "actualWaterAndMarker", "missingOrUnmarked", "coverage", "potentialRecordedChannelGapColumns")} for key in result}, indent=2))


if __name__ == "__main__":
    main()
