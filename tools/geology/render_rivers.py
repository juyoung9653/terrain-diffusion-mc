"""Compare planned river columns with actual saved blocks; never synthesize a game image."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw
from render_saved import audit_bounds, color, read_chunks, state_at

PIXEL = 3


def load_columns(path: Path) -> list[dict]:
    with path.open() as stream:
        return [{**row, "x": int(row["x"]), "z": int(row["z"]), "bed": int(row["bed"]),
                 "water": int(row["water"]), "roof": int(row["roof"]),
                 "wet": row["wet"] == "true", "lake": row["lake"] == "true"}
                for row in csv.DictReader(stream)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("world", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("model_csv", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    rows = load_columns(args.model_csv)
    _, _, center, radius = audit_bounds(args.world)
    chunk_bounds = ((center[0] - radius, center[0] + radius - 1), (center[1] - radius, center[1] + radius - 1))
    low_x, low_z = chunk_bounds[0][0] * 16, chunk_bounds[1][0] * 16
    high_x, high_z = (chunk_bounds[0][1] + 1) * 16 - 1, (chunk_bounds[1][1] + 1) * 16 - 1
    chunks = read_chunks(args.world, chunk_bounds[0][0], chunk_bounds[0][1], chunk_bounds[1][0], chunk_bounds[1][1])
    planned = {(row["x"], row["z"]): row for row in rows if row["wet"]}
    if not planned:
        raise SystemExit("No planned wet columns in audited fixture bounds")
    counts = water_plan(chunks, planned, low_x, high_x, low_z, high_z, args.output)
    best_z = max(range(low_z, high_z + 1), key=lambda z: sum((x, z) in planned for x in range(low_x, high_x + 1)))
    cross_section(rows, chunks, best_z, low_x, high_x, args.output)
    metadata = {"world": str(args.world), "sourceCsv": str(args.model_csv),
                "sourceCsvSha256": hashlib.sha256(args.model_csv.read_bytes()).hexdigest(),
                "chunkBounds": [list(chunk_bounds[0]), list(chunk_bounds[1])],
                "blockBounds": [[low_x, high_x], [low_z, high_z]], "plannedWetColumns": len(planned),
                **counts, "crossSectionZ": best_z}
    (args.output / "river-render-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")


def water_plan(chunks, planned, low_x, high_x, low_z, high_z, output: Path) -> dict:
    width, height, header = high_x - low_x + 1, high_z - low_z + 1, 42
    image = Image.new("RGB", (width * PIXEL, height * PIXEL + header), "white")
    draw = ImageDraw.Draw(image)
    matched = mismatch = missing = 0
    for (x, z), column in planned.items():
        if not (low_x <= x <= high_x and low_z <= z <= high_z):
            continue
        state = state_at(chunks, x, column["water"], z)
        good = state == "minecraft:water"
        pixel = (48, 116, 198) if good else (170, 0, 180) if state == "missing:chunk" else (220, 40, 35)
        matched += good; missing += state == "missing:chunk"; mismatch += not good and state != "missing:chunk"
        px, py = (x - low_x) * PIXEL, header + (z - low_z) * PIXEL
        draw.rectangle((px, py, px + PIXEL - 1, py + PIXEL - 1), fill=pixel)
    draw.text((3, 3), "actual saved water sampled at each planned column waterY", fill="black")
    draw.text((3, 16), f"blue match={matched}; red mismatch={mismatch}; purple missing={missing}", fill="black")
    draw.text((3, 29), f"blocks x={low_x}..{high_x}; z={low_z}..{high_z}; display: {PIXEL} px/block", fill="black")
    image.save(output / "actual-water-at-planned-y.png")
    return {"matched": matched, "mismatch": mismatch, "missing": missing}


def cross_section(rows, chunks, z, low_x, high_x, output: Path) -> None:
    relevant = [row for row in rows if row["z"] == z and row["wet"] and low_x <= row["x"] <= high_x]
    if not relevant:
        raise SystemExit("Chosen cross-section has no planned wet columns")
    low, high = min(row["bed"] for row in relevant) - 12, max(row["roof"] for row in relevant) + 12
    image = Image.new("RGB", ((high_x - low_x + 1) * PIXEL, (high - low + 1) * PIXEL + 50), "white")
    draw = ImageDraw.Draw(image)
    draw.text((3, 3), "Actual saved blocks: river cross-section", fill="black")
    draw.text((3, 16), f"z={z}; y={high}..{low}; {PIXEL} px/block", fill="black")
    draw.text((3, 29), "blue = water; black = air; other colors = rock/ore", fill="black")
    for y in range(high, low - 1, -1):
        for x in range(low_x, high_x + 1):
            px, py = (x - low_x) * PIXEL, 50 + (high - y) * PIXEL
            draw.rectangle((px, py, px + PIXEL - 1, py + PIXEL - 1), fill=color(state_at(chunks, x, y, z)))
    image.save(output / "actual-river-cross-section.png")


if __name__ == "__main__":
    main()
