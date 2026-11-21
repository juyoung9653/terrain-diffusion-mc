"""Read-only spatial checks for the fixed marsh-river review sites.

The checker reads saved FULL Anvil chunks only.  It is intentionally not a
world-generation test: its output is a compact before/after physical record for
the visual review, including enough raw column data to diagnose a flagged edge.
"""
from __future__ import annotations

import argparse
from collections import deque
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from measure import AIR, SavedWorld

STREAM = "streamsreflowing:stream"
VANILLA_WATER = {"minecraft:water", "minecraft:flowing_water"}
PLANTS = ("short_grass", "tall_grass", "fern", "flower", "vine", "bush", "sapling",
          "lily_pad", "sugar_cane", "seagrass", "kelp", "moss_carpet", "propagule",
          # Explicit modded soft plants seen in the fixed control.  Do not use a
          # broad "grass" match: grass_block and terrain_slabs:grass_slab are
          # supported terrain, whereas these are passable vegetation.
          "greeneries:bushy_grass", "greeneries:medium_grass")
SOIL_WORDS = ("dirt", "grass_block", "mud", "sand", "gravel", "clay", "podzol",
              "mycelium")
WINDOWS = {
    "overlap": (544, 240, 639, 335),
    "terminus": (864, 544, 927, 607),
    "control": (880, 608, 943, 671),
}


def name(world: SavedWorld, x: int, y: int, z: int) -> tuple[str, bool] | None:
    return world.state(x, y, z)


def wet(state: tuple[str, bool] | None) -> bool:
    return state is not None and (state[1] or state[0] in VANILLA_WATER or state[0] == STREAM)


def passable(block: str) -> bool:
    path = block.removeprefix("minecraft:")
    return block in AIR or any(part in path for part in PLANTS)


def exposed_to_open_air(source: tuple[str, bool] | None,
                        neighbor: tuple[str, bool] | None) -> bool:
    """True only for a literal fluid-to-open/passable face.

    Seagrass and waterlogged vegetation occupy a passable block state but are
    still wet; treating either as air turns a one-block shoreline into a false
    two-block exposed face.
    """
    return wet(source) and neighbor is not None and not wet(neighbor) and passable(neighbor[0])


def soil(block: str) -> bool:
    return any(word in block.removeprefix("minecraft:") for word in SOIL_WORDS)


def column(world: SavedWorld, x: int, z: int, low: int = 40, high: int = 96) -> dict:
    states = [(y, state) for y in range(low, high + 1)
              if (state := name(world, x, y, z)) is not None]
    wet_ys = [y for y, state in states if wet(state)]
    stream_ys = [y for y, state in states if state[0] == STREAM]
    solid = [(y, state[0]) for y, state in states if not wet(state) and not passable(state[0])]
    surface = max(wet_ys) if wet_ys else None
    bed = None
    if wet_ys:
        for y in range(min(wet_ys) - 1, low - 1, -1):
            state = name(world, x, y, z)
            if state is not None and not wet(state) and not passable(state[0]):
                bed = y
                break
    top = max(solid, default=(None, None))
    tree = any(("_log" in state[0] or "_leaves" in state[0]) for _, state in states)
    roof = surface is not None and any(
        not wet(state) and not passable(state[0]) for y, state in states if y > surface)
    return {"x": x, "z": z, "waterTop": surface, "bedY": bed,
            "dryTop": top[0] if surface is None else None,
            "dryTopBlock": top[1] if surface is None else None,
            "streamY": stream_ys, "treeCover": tree, "roof": roof,
            "readable": bool(states)}


def grid(world: SavedWorld, bounds: tuple[int, int, int, int]) -> dict[tuple[int, int], dict]:
    x0, z0, x1, z1 = bounds
    return {(x, z): column(world, x, z) for z in range(z0, z1 + 1) for x in range(x0, x1 + 1)}


def components(points: set[tuple[int, int]]) -> list[dict]:
    result = []
    while points:
        start = points.pop(); queue = deque([start]); group = [start]
        while queue:
            x, z = queue.popleft()
            for other in ((x - 1, z), (x + 1, z), (x, z - 1), (x, z + 1)):
                if other in points:
                    points.remove(other); queue.append(other); group.append(other)
        result.append({"size": len(group), "bounds": [min(x for x, _ in group), min(z for _, z in group),
                                                          max(x for x, _ in group), max(z for _, z in group)]})
    return sorted(result, key=lambda entry: entry["size"], reverse=True)


def physical_edges(world: SavedWorld, cells: dict[tuple[int, int], dict]) -> dict:
    drops, open_faces, dry_banks, shelves, unknown, covered = [], [], [], [], [], []
    for (x, z), current in cells.items():
        if not current["readable"]:
            unknown.append([x, z]); continue
        # A soil block directly over vegetation/air is reported separately from
        # a bank; it does not make a water face.
        for y in range(41, 96):
            here, below = name(world, x, y, z), name(world, x, y - 1, z)
            if here and below and soil(here[0]) and passable(below[0]):
                shelves.append({"x": x, "y": y, "z": z, "block": here[0], "below": below[0]})
        # This is a literal adjacent-block test, so foliage or a roof higher in
        # either column must not hide a genuine vertical wet-to-open face.
        # Canopy exclusion remains below for inferred water-top and dry-bank
        # metrics, which cannot be trusted under cover.
        if current["waterTop"] is not None:
            for direction, (dx, dz) in {"west": (-1, 0), "east": (1, 0),
                                        "north": (0, -1), "south": (0, 1)}.items():
                other = cells.get((x + dx, z + dz))
                if other is None or not other["readable"]:
                    continue
                run = []
                for y in range(40, 97):
                    source, neighbor = name(world, x, y, z), name(world, x + dx, y, z + dz)
                    if exposed_to_open_air(source, neighbor):
                        run.append(y)
                    elif len(run) >= 2:
                        open_faces.append({"x": x, "z": z, "direction": direction,
                                           "minY": run[0], "maxY": run[-1], "height": len(run),
                                           "neighbor": "dry-passable"})
                        run = []
                    else:
                        run = []
                if len(run) >= 2:
                    open_faces.append({"x": x, "z": z, "direction": direction,
                                       "minY": run[0], "maxY": run[-1], "height": len(run),
                                       "neighbor": "dry-passable"})
        if current["roof"] or current["treeCover"]:
            covered.append({"x": x, "z": z, "treeCover": current["treeCover"],
                            "roof": current["roof"]})
        if current["waterTop"] is None or current["roof"] or current["treeCover"]:
            continue
        for direction, (dx, dz) in {"west": (-1, 0), "east": (1, 0),
                                    "north": (0, -1), "south": (0, 1)}.items():
            other = cells.get((x + dx, z + dz))
            if other is None or not other["readable"] or other["roof"] or other["treeCover"]:
                continue
            if other["waterTop"] is not None:
                delta = current["waterTop"] - other["waterTop"]
                if delta >= 2:
                    drops.append({"x": x, "z": z, "direction": direction,
                                  "top": current["waterTop"], "neighborTop": other["waterTop"],
                                  "drop": delta, "neighborWet": True})
            else:
                rise = (other["dryTop"] or -999) - current["waterTop"]
                if rise >= 2:
                    dry_banks.append({"x": x, "z": z, "direction": direction,
                                      "waterTop": current["waterTop"], "dryTop": other["dryTop"],
                                      "rise": rise, "distance": 1})
                # A second ring catches a thin first-bank ledge followed by a
                # promontory, while retaining the direct neighbor in the record.
                far = cells.get((x + 2 * dx, z + 2 * dz))
                if far and far["waterTop"] is None and not far["roof"] and not far["treeCover"]:
                    far_rise = (far["dryTop"] or -999) - current["waterTop"]
                    if far_rise >= 2:
                        dry_banks.append({"x": x, "z": z, "direction": direction,
                                          "waterTop": current["waterTop"], "dryTop": far["dryTop"],
                                          "rise": far_rise, "distance": 2})
    return {"abruptWetSurfaceDrops": drops, "exposedOpenFluidFaces": open_faces,
            "steepDryBanks": dry_banks,
            "summary": {"wetSurfaceDropsAtLeast2": len(drops),
                        "openFluidFacesAtLeast2": len(open_faces),
                        "steepDryBanksAtDistance1": sum(edge["distance"] == 1 for edge in dry_banks),
                        "steepDryBanksAtDistance2": sum(edge["distance"] == 2 for edge in dry_banks),
                        "dryCapPromontories": len(shelves)},
            "dryCapPromontories": shelves, "unknownColumns": unknown,
            "excludedTreeOrRoofColumns": covered}


def fixed_column(world: SavedWorld, x: int, z: int) -> dict:
    data = column(world, x, z)
    data["blocks"] = [{"y": y, "block": state[0], "waterlogged": state[1]}
                      for y in range(58, 71) if (state := name(world, x, y, z)) and state[0] not in AIR]
    return data


def report(world_path: Path, label: str) -> dict:
    world = SavedWorld(world_path)
    windows = {key: grid(world, bounds) for key, bounds in WINDOWS.items()}
    output = {"schema": 1, "kind": "marsh-river-spatial-physical", "label": label,
              "world": str(world_path.resolve()), "windows": {}, "fixed": {}}
    for key, cells in windows.items():
        water = {point for point, cell in cells.items() if cell["waterTop"] is not None}
        vanilla = {point for point in water if not cells[point]["streamY"]}
        stream = {point for point, cell in cells.items() if cell["streamY"]}
        output["windows"][key] = {"bounds": WINDOWS[key], "waterColumns": len(water),
            "streamColumns": len(stream), "vanillaOnlyColumns": len(vanilla),
            "waterComponents": components(set(water))[:20],
            "edges": physical_edges(world, cells)}
    # Fixed reported bed and cross-window transects.  Each entry keeps the raw
    # saved block stack, so a future comparison never relies on a derived count.
    for x, z in [(581, 254), (582, 254), (583, 254), (885, 561), (899, 560),
                 (899, 561), (911, 559), (911, 560), (912, 560), (911, 610)]:
        output["fixed"][f"{x},{z}"] = fixed_column(world, x, z)
    output["transects"] = {
        "overlap_z254_x570_602": [fixed_column(world, x, 254) for x in range(570, 603)],
        "mouth_891_554": {direction: [fixed_column(world, 891 + dx * step, 554 + dz * step)
                                        for step in range(3)]
                           for direction, (dx, dz) in {"west": (-1, 0), "east": (1, 0),
                                                        "north": (0, -1), "south": (0, 1)}.items()},
        "terminal_z559_560_x904_914": [fixed_column(world, x, z)
                                         for z in (559, 560) for x in range(904, 915)],
    }
    if world.missing_chunks or world.non_full_chunks or not world.stable():
        raise RuntimeError("world changed or does not provide stable FULL chunks")
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", required=True, type=Path)
    parser.add_argument("--label", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = report(args.world, args.label)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
