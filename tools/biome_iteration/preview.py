"""Fast, explicitly non-release material-map previews for biome iteration.

This is deliberately not a Minecraft scene renderer.  It turns settled native
column observations into a small SVG map so that an implementer can inspect a
candidate material boundary before spending a formal independent-review round.
The emitted manifest is marked unusable as validation or review evidence.
"""
from __future__ import annotations

import argparse
import html
import hashlib
import json
import importlib.util
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
KIND = "biome-iteration-material-map-preview"
PALETTE = {
    "minecraft:sand": "#d8c38b", "minecraft:red_sand": "#c88758",
    "minecraft:mud": "#725849", "minecraft:clay": "#a6a9ad",
    "minecraft:grass_block": "#67864b", "minecraft:dirt": "#806044",
    "immersive_weathering:silt": "#625c4f",
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_columns(path: Path) -> list[dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    rows = data.get("columns") if isinstance(data, dict) else None
    if not isinstance(rows, list) or not rows:
        raise ValueError("columns input must contain a nonempty columns array")
    checked = []
    for row in rows:
        if not isinstance(row, dict) or not all(isinstance(row.get(k), int) for k in ("x", "z")):
            raise ValueError("each column requires integer x/z")
        if not isinstance(row.get("bedBlock"), str):
            raise ValueError("each column requires bedBlock")
        checked.append(row)
    return checked


def parse_window(raw: str) -> tuple[int, int, int, int]:
    try:
        min_x, max_x, min_z, max_z = (int(value) for value in raw.split(","))
    except (TypeError, ValueError) as error:
        raise ValueError("window must be minX,maxX,minZ,maxZ integer values") from error
    if min_x > max_x or min_z > max_z:
        raise ValueError("window bounds are inverted")
    return min_x, max_x, min_z, max_z


def svg(rows: list[dict[str, Any]], window: tuple[int, int, int, int], title: str) -> str:
    min_x, max_x, min_z, max_z = window
    selected = [row for row in rows if min_x <= row["x"] <= max_x and min_z <= row["z"] <= max_z]
    if not selected:
        raise ValueError("window has no sampled material columns")
    width, height = max_x - min_x + 1, max_z - min_z + 1
    cells = []
    for row in selected:
        classification = row.get("classification", "selected")
        color = (PALETTE.get(row["bedBlock"], "url(#unknown)") if classification == "selected"
                 else {"covered": "url(#cover)", "unreadable": "url(#unknown)",
                       "transparent-or-empty": "url(#transparent)", "other": "url(#other)"}.get(classification, "url(#unknown)"))
        cells.append(f'<rect x="{row["x"] - min_x}" y="{row["z"] - min_z}" width="1" height="1" fill="{color}"/>')
    legend = " | ".join(f"{name}: {color}" for name, color in PALETTE.items()) + " | cover: green hatch | unreadable: pink hatch | other: dark | transparent: blue | unsampled: gray"
    safe_title, safe_legend = html.escape(title, quote=True), html.escape(legend, quote=True)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height + 8}" '
            f'viewBox="0 0 {width} {height + 8}" aria-label="{safe_title}"><title>{safe_title}</title>'
            '<defs><pattern id="unknown" width="2" height="2" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="2" height="2" fill="#8f3f65"/><line x1="0" y1="0" x2="0" y2="2" stroke="#f2c2d4" stroke-width=".5"/></pattern><pattern id="cover" width="2" height="2" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="2" height="2" fill="#547b57"/><line x1="0" y1="0" x2="0" y2="2" stroke="#c4e5b9" stroke-width=".5"/></pattern><pattern id="transparent" width="2" height="2" patternUnits="userSpaceOnUse"><rect width="2" height="2" fill="#769eb5"/><circle cx="1" cy="1" r=".3" fill="#d3edf6"/></pattern><pattern id="other" width="2" height="2" patternUnits="userSpaceOnUse"><rect width="2" height="2" fill="#3b3b3b"/></pattern></defs>'
            f'<rect width="{width}" height="{height}" fill="#767676"/>{"".join(cells)}'
            f'<text x="0" y="{height + 4}" font-size="2" fill="#111">{safe_legend}</text></svg>\n')


def render(columns: Path, output: Path, window: tuple[int, int, int, int], title: str) -> dict[str, Any]:
    rows = load_columns(columns)
    rendered = svg(rows, window, title)
    output.mkdir(parents=True, exist_ok=False)
    image = output / "material-map.svg"
    image.write_text(rendered, encoding="utf-8")
    area = (window[1] - window[0] + 1) * (window[3] - window[2] + 1)
    sampled = len({(row["x"], row["z"]) for row in rows
                   if window[0] <= row["x"] <= window[1] and window[2] <= row["z"] <= window[3]})
    manifest = {
        "schema": 1, "kind": KIND, "status": "preview-only",
        "sourceColumns": {"path": str(columns.resolve()), "sha256": digest(columns)},
        "window": {"minX": window[0], "maxX": window[1], "minZ": window[2], "maxZ": window[3]},
        "sampling": {"sampledColumns": sampled, "windowColumns": area, "unsampledColumns": area - sampled,
                     "coverageDensity": sampled / area, "partial": sampled != area},
        "artifacts": [{"path": str(image.resolve()), "sha256": digest(image), "role": "offline-material-map"}],
        "releaseEvidence": False, "independentReview": False, "sceneCapture": False,
        "formalReviewTaskClaimed": False,
        "limitations": [
            "This is an offline map of already observed columns, not a rendered Minecraft scene.",
            "It cannot satisfy build validation, installation, or independent biome-review requirements.",
            "A final candidate still needs a fresh full-modpack native capture and formal independent review.",
        ],
    }
    (output / "preview.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def measure_module():
    spec = importlib.util.spec_from_file_location("biome_iteration_measure", Path(__file__).with_name("measure.py"))
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def grid_rows(report: dict[str, Any]) -> list[dict[str, Any]]:
    rows = report.get("grid")
    if not isinstance(rows, list) or not rows:
        raise ValueError("saved-world measurement did not return a grid")
    return rows


def render_world(world_path: Path, output: Path, window: tuple[int, int, int, int], title: str,
                 min_y: int, max_y: int, compare_world: Path | None = None) -> dict[str, Any]:
    if not 0 <= max_y - min_y < 1024:
        raise ValueError("choose a bounded vertical interval (1..1024)")
    output_path = output.resolve()
    for source in (world_path, compare_world):
        if source is not None and output_path.is_relative_to(source.resolve()):
            raise ValueError("preview output must be outside supplied saved-world directories")
    measure = measure_module()
    bounds = (window[0], window[2], window[1], window[3])
    worlds = [("candidate", world_path)] + ([] if compare_world is None else [("control", compare_world)])
    output.mkdir(parents=True, exist_ok=False)
    sources, artifacts = {}, []
    for label, path in worlds:
        world = measure.SavedWorld(path)
        report = measure.scan_patch(world, label, bounds, set(PALETTE), min_y, max_y, include_grid=True)
        rows = grid_rows(report)
        image = output / ("material-map.svg" if label == "candidate" else f"{label}-material-map.svg")
        image.write_text(svg(rows, window, f"{title} ({label})"), encoding="utf-8")
        readable = sum(row.get("classification") != "unreadable" for row in rows)
        sources[label] = {"path": str(path.resolve()), "regionsStable": world.stable(), "regionSha256": world.hashes,
                          "measurement": report, "readableColumns": readable, "windowColumns": len(rows)}
        artifacts.append({"path": str(image.resolve()), "sha256": digest(image), "role": f"offline-saved-world-material-map-{label}"})
    manifest = {
        "schema": 1, "kind": KIND, "status": "preview-only", "worldSources": sources,
        "window": {"minX": window[0], "maxX": window[1], "minZ": window[2], "maxZ": window[3],
                   "minY": min_y, "maxY": max_y}, "artifacts": artifacts,
        "releaseEvidence": False, "independentReview": False, "sceneCapture": False,
        "formalReviewTaskClaimed": False,
        "limitations": [
            "This is a read-only saved-Anvil block map, not a rendered Minecraft scene.",
            "Covered, unreadable, transparent, and other top blocks are displayed separately from selected substrates.",
            "It cannot satisfy build validation, installation, or independent biome-review requirements.",
        ],
    }
    (output / "preview.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Write a non-release offline biome material preview")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--columns", type=Path, help="native capture columns.json")
    source.add_argument("--world", type=Path, help="closed saved world, read only")
    parser.add_argument("--compare-world", type=Path, help="optional aligned closed saved world, read only")
    parser.add_argument("--output", required=True, type=Path, help="new empty preview directory")
    parser.add_argument("--window", required=True, help="minX,maxX,minZ,maxZ")
    parser.add_argument("--title", default="Biome iteration material preview")
    parser.add_argument("--min-y", type=int)
    parser.add_argument("--max-y", type=int)
    args = parser.parse_args()
    if args.columns is not None:
        if args.compare_world is not None or args.min_y is not None or args.max_y is not None:
            parser.error("--compare-world/--min-y/--max-y require --world")
        manifest = render(args.columns, args.output, parse_window(args.window), args.title)
    else:
        if args.min_y is None or args.max_y is None:
            parser.error("--world requires --min-y and --max-y")
        manifest = render_world(args.world, args.output, parse_window(args.window), args.title,
                                args.min_y, args.max_y, args.compare_world)
    print(json.dumps({"preview": str(args.output), "kind": manifest["kind"], "releaseEvidence": False}))


if __name__ == "__main__":
    main()
