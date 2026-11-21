"""Render the fixed saved-world marsh-river review transects as SVG.

This deliberately reads saved FULL chunks only.  It makes no Minecraft launch
and it writes a companion JSON block array so the illustration is auditable.
"""
from __future__ import annotations

import argparse
from html import escape
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from measure import AIR, SavedWorld
from marsh_river_physical import PLANTS, STREAM, VANILLA_WATER, wet


PANELS = (
    ("terminal east-west (z=559)", [(x, 559) for x in range(904, 915)], 58, 68),
    ("terminal north-south (x=912)", [(912, z) for z in range(554, 565)], 58, 68),
    ("overlap dry-bank transect (z=254)", [(x, 254) for x in range(570, 603)], 58, 70),
)


def block_name(world: SavedWorld, x: int, y: int, z: int) -> str:
    state = world.state(x, y, z)
    return "__missing__" if state is None else state[0]


def category(name: str) -> str:
    path = name.removeprefix("minecraft:")
    if name == "__missing__":
        return "missing"
    if name in AIR:
        return "air"
    if name == STREAM:
        return "stream"
    if name in VANILLA_WATER:
        return "water"
    if any(part in path for part in PLANTS):
        return "plant"
    if name == "terrain_slabs:grass_slab" or "grass_block" in path:
        return "grass"
    if "mud" in path:
        return "mud"
    if "dirt" in path or "podzol" in path or "mycelium" in path:
        return "dirt"
    if "sand" in path or "gravel" in path or "clay" in path:
        return "sediment"
    if "stone" in path or "deepslate" in path or "ore" in path:
        return "stone"
    if "log" in path or "leaves" in path:
        return "tree"
    return "other"


COLORS = {
    "air": "#ffffff", "missing": "#20242a", "water": "#2b8ec6",
    "stream": "#08749d", "plant": "#94c973", "grass": "#5b9d46",
    "mud": "#735d45", "dirt": "#956842", "sediment": "#cbb47b",
    "stone": "#7b8085", "tree": "#3f6b37", "other": "#c75b43",
}


def raw_panel(world: SavedWorld, coords: list[tuple[int, int]], low: int, high: int) -> list[dict]:
    return [{"x": x, "z": z, "blocks": [{"y": y, "block": block_name(world, x, y, z)}
                                                 for y in range(low, high + 1)]}
            for x, z in coords]


def svg_blocks(parts: list[str], blocks: list[dict], x0: int, y0: int, cell: int, low: int, high: int) -> None:
    for ix, column in enumerate(blocks):
        for block in column["blocks"]:
            y = block["y"]
            draw_y = y0 + (high - y) * cell
            label = escape(block["block"])
            parts.append(f'<rect x="{x0 + ix * cell}" y="{draw_y}" width="{cell}" height="{cell}" '
                         f'fill="{COLORS[category(block["block"])]}" stroke="#303030" stroke-width=".35">'
                         f'<title>{column["x"]},{y},{column["z"]}: {label}</title></rect>')
    for y in range(low, high + 1):
        parts.append(f'<text x="{x0 - 5}" y="{y0 + (high-y)*cell + cell-2}" text-anchor="end" '
                     f'class="axis">{y}</text>')
    step = 1 if len(blocks) <= 15 else 2
    for ix, column in enumerate(blocks):
        if ix % step == 0:
            coord = str(column["x"] if len({c["z"] for c in blocks}) == 1 else column["z"])
            parts.append(f'<text x="{x0 + ix*cell + cell/2}" y="{y0 + (high-low+1)*cell + 13}" '
                         f'text-anchor="middle" class="axis">{coord}</text>')


def render(before: SavedWorld, after: SavedWorld, before_label: str, after_label: str, output: Path) -> dict:
    raw = {"schema": 1, "beforeLabel": before_label, "afterLabel": after_label, "panels": []}
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="1120" height="1120" viewBox="0 0 1120 1120">',
             '<style>text{font-family:monospace;fill:#222}.title{font-size:16px;font-weight:bold}.label{font-size:13px;font-weight:bold}.axis{font-size:9px}.note{font-size:11px}</style>',
             '<rect width="100%" height="100%" fill="#fafafa"/>',
             '<text x="24" y="25" class="title">Saved block cross-sections: fixed marsh-river review sites</text>',
             '<text x="24" y="42" class="note">Each square is an exact saved block (hover for x,y,z and block id). Blue is vanilla water; teal is Streams.</text>']
    y_cursor = 68
    for title, coords, low, high in PANELS:
        b = raw_panel(before, coords, low, high)
        a = raw_panel(after, coords, low, high)
        raw["panels"].append({"title": title, "rangeY": [low, high], "before": b, "after": a})
        cell = 16 if len(coords) <= 15 else 10
        width = len(coords) * cell
        parts.append(f'<text x="24" y="{y_cursor}" class="label">{escape(title)}</text>')
        y_cursor += 18
        parts.append(f'<text x="24" y="{y_cursor}" class="label">{escape(before_label)}</text>')
        svg_blocks(parts, b, 70, y_cursor + 8, cell, low, high)
        after_x = max(470, 100 + width + 100)
        parts.append(f'<text x="{after_x - 46}" y="{y_cursor}" class="label">{escape(after_label)}</text>')
        svg_blocks(parts, a, after_x, y_cursor + 8, cell, low, high)
        y_cursor += (high - low + 1) * cell + 50
    legend_x, legend_y = 25, 1084
    for i, key in enumerate(("water", "stream", "plant", "grass", "mud", "dirt", "sediment", "stone", "tree", "other", "missing")):
        x = legend_x + i * 96
        parts.append(f'<rect x="{x}" y="{legend_y-11}" width="11" height="11" fill="{COLORS[key]}" stroke="#303030"/>')
        parts.append(f'<text x="{x+15}" y="{legend_y}" class="axis">{key}</text>')
    parts.append('</svg>')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(parts) + "\n", encoding="utf-8")
    output.with_suffix(".json").write_text(json.dumps(raw, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return raw


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before-world", required=True, type=Path)
    parser.add_argument("--after-world", required=True, type=Path)
    parser.add_argument("--before-label", default="before")
    parser.add_argument("--after-label", default="after")
    parser.add_argument("--output", required=True, type=Path, help="SVG path; raw JSON gets the same stem")
    args = parser.parse_args()
    before, after = SavedWorld(args.before_world), SavedWorld(args.after_world)
    render(before, after, args.before_label, args.after_label, args.output)
    if before.missing_chunks or before.non_full_chunks or not before.stable() or after.missing_chunks or after.non_full_chunks or not after.stable():
        raise RuntimeError("world changed or does not provide stable FULL chunks")


if __name__ == "__main__":
    main()
