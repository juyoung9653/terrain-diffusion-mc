"""Compare matching saved-world marsh surfaces without modifying either world.

The reference is normally a no-Streams capture and the candidate is a native
Streams capture.  Each x/z belongs to one mutually exclusive class, so a pond
that remains wet cannot also be counted as a drained floor or a new channel.
Tree/roof-covered dry columns are retained as coverage evidence but excluded
from surface/soil assertions: a canopy is not terrain.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from measure import AIR, SavedWorld

WATER = {"minecraft:water", "minecraft:flowing_water", "streamsreflowing:stream"}
SOFT = ("short_grass", "tall_grass", "fern", "flower", "vine", "bush", "sapling",
        "lily_pad", "sugar_cane", "seagrass", "kelp", "moss_carpet", "propagule",
        "reed", "cattail", "rush", "greeneries:bushy_grass", "greeneries:medium_grass")
SOIL = ("dirt", "grass_block", "mud", "sand", "gravel", "clay", "podzol", "mycelium",
        "peat", "silt", "loam")
DEFAULT_WINDOWS = {"reported_view": (475, 147, 635, 307),
                   "inland_upstream": (590, 147, 624, 190),
                   "noncontact_pool": (550, 295, 561, 305)}


def wet(state: tuple[str, bool] | None) -> bool:
    return state is not None and (state[1] or state[0] in WATER)


def passable(block: str) -> bool:
    path = block.removeprefix("minecraft:")
    return block in AIR or any(token in path for token in SOFT)


def soil_kind(block: str | None) -> str:
    if block is None:
        return "none"
    path = block.removeprefix("minecraft:")
    return "soil" if any(token in path for token in SOIL) else "other-solid"


def column(world: SavedWorld, x: int, z: int, low: int, high: int) -> dict:
    try:
        states = {y: world.state(x, y, z) for y in range(low, high + 1)}
    except ValueError:
        return {"x": x, "z": z, "readable": False}
    if any(state is None for state in states.values()):
        return {"x": x, "z": z, "readable": False}
    wet_ys = [y for y, state in states.items() if wet(state)]
    water_top = max(wet_ys) if wet_ys else None
    cover = any(("_log" in state[0] or "_leaves" in state[0])
                for state in states.values())
    roof = water_top is not None and any(
        not wet(state) and not passable(state[0]) and "_log" not in state[0] and "_leaves" not in state[0]
        for y, state in states.items() if y > water_top)
    bed_y = None
    bed_block = None
    contiguous_bottom = None
    if water_top is not None:
        contiguous_bottom = water_top
        while contiguous_bottom > low and wet(states[contiguous_bottom - 1]):
            contiguous_bottom -= 1
        for y in range(contiguous_bottom - 1, low - 1, -1):
            state = states[y]
            if not wet(state) and not passable(state[0]):
                bed_y, bed_block = y, state[0]
                break
    dry_y = dry_block = None
    surface_wet = water_top if not roof else None
    if surface_wet is None:
        for y in range(high, low - 1, -1):
            state = states[y]
            if not passable(state[0]) and "_log" not in state[0] and "_leaves" not in state[0]:
                dry_y, dry_block = y, state[0]
                break
    return {"x": x, "z": z, "readable": True, "waterTop": surface_wet,
            "bedY": bed_y, "bedBlock": bed_block, "bedSoil": soil_kind(bed_block),
            "dryTop": dry_y, "dryBlock": dry_block, "drySoil": soil_kind(dry_block),
            "covered": cover or roof, "caveWaterExcluded": water_top is not None and roof,
            "hasStream": any(state[0] == "streamsreflowing:stream"
                              for state in states.values()),
            "hasStreamInSurfaceWater": (surface_wet is not None and any(
                states[y][0] == "streamsreflowing:stream"
                for y in range(contiguous_bottom, water_top + 1)))}


def summarize(counter: Counter) -> dict:
    return dict(sorted(counter.items()))


def dry_banks(cells: dict[tuple[int, int], dict]) -> list[dict]:
    """Return directed, exposed-water-to-dry bank faces with a >=2-block rise."""
    rows = []
    for (x, z), water in cells.items():
        if water.get("waterTop") is None or water.get("covered"):
            continue
        for direction, (dx, dz) in {"west": (-1, 0), "east": (1, 0),
                                    "north": (0, -1), "south": (0, 1)}.items():
            shore = cells.get((x + dx, z + dz))
            if not shore or shore.get("waterTop") is not None or shore.get("covered"):
                continue
            if shore.get("dryTop") is None:
                continue
            rise = shore["dryTop"] - water["waterTop"]
            if rise >= 2:
                rows.append({"waterX": x, "waterZ": z, "waterTop": water["waterTop"],
                             "shoreX": x + dx, "shoreZ": z + dz, "shoreTop": shore["dryTop"],
                             "shoreBlock": shore["dryBlock"], "direction": direction, "rise": rise})
    return rows


def compare_window(reference: SavedWorld, candidate: SavedWorld,
                   bounds: tuple[int, int, int, int], low: int, high: int) -> dict:
    x0, z0, x1, z1 = bounds
    classes = Counter(); wet_material = Counter(); dry_material = Counter()
    wet_height = Counter(); wet_bed_height = Counter(); dry_height = Counter()
    surface_streams = Counter(); examples = []
    covered = 0; cave_water = 0; unreadable = 0
    reference_cells, candidate_cells = {}, {}
    for z in range(z0, z1 + 1):
        for x in range(x0, x1 + 1):
            before, after = column(reference, x, z, low, high), column(candidate, x, z, low, high)
            reference_cells[x, z], candidate_cells[x, z] = before, after
            if not before["readable"] or not after["readable"]:
                unreadable += 1; continue
            if before["covered"] or after["covered"]:
                covered += 1
            if before["caveWaterExcluded"] or after["caveWaterExcluded"]:
                cave_water += 1
            ref_wet, out_wet = before["waterTop"] is not None, after["waterTop"] is not None
            if ref_wet and out_wet:
                category = "reference_wet_stays_wet"
                delta = after["waterTop"] - before["waterTop"]
                wet_height[delta] += 1
                if before["bedY"] is not None and after["bedY"] is not None:
                    wet_bed_height[after["bedY"] - before["bedY"]] += 1
                wet_material[f"{before['bedBlock']} -> {after['bedBlock']}"] += 1
            elif ref_wet:
                category = "reference_wet_drained"
                wet_material[f"{before['bedBlock']} -> {after['dryBlock']}"] += 1
                if before["bedY"] is not None and after["dryTop"] is not None:
                    wet_bed_height[after["dryTop"] - before["bedY"]] += 1
                delta = None
            elif out_wet:
                category = "reference_dry_new_wet"
                wet_material[f"{before['dryBlock']} -> {after['bedBlock']}"] += 1
                delta = None
            else:
                category = "reference_dry_stays_dry"
                usable_dry = not before["covered"] and not after["covered"]
                if usable_dry:
                    dry_material[f"{before['dryBlock']} -> {after['dryBlock']}"] += 1
                delta = (after["dryTop"] - before["dryTop"] if usable_dry else None)
                if delta is not None:
                    dry_height[delta] += 1
            classes[category] += 1
            if ref_wet or out_wet:
                surface_streams[f"{category}: reference={before['hasStreamInSurfaceWater']},candidate={after['hasStreamInSurfaceWater']}"] += 1
            # Keep a bounded, auditable set of all meaningful conversion types.
            if category != "reference_dry_stays_dry" or delta not in (None, 0):
                if len(examples) < 256:
                    examples.append({"x": x, "z": z, "class": category,
                                     "reference": before, "candidate": after,
                                     "referenceSurfaceStream": before["hasStreamInSurfaceWater"],
                                     "candidateSurfaceStream": after["hasStreamInSurfaceWater"]})
    before_banks, after_banks = dry_banks(reference_cells), dry_banks(candidate_cells)
    return {"bounds": list(bounds), "columns": (x1 - x0 + 1) * (z1 - z0 + 1),
            "unreadableColumns": unreadable, "coveredColumnsExcludedFromDrySurface": covered,
            "caveWaterColumnsExcluded": cave_water,
            "exclusiveClasses": summarize(classes),
            "wetSurfaceHeightDelta": summarize(wet_height),
            "originallyWetBedHeightDelta": summarize(wet_bed_height),
            "drySurfaceHeightDeltaUncoveredOnly": summarize(dry_height),
            "surfaceWaterStreamPresence": summarize(surface_streams),
            "wetBedMaterialTransitions": summarize(wet_material),
            "drySurfaceMaterialTransitionsUncoveredOnly": summarize(dry_material),
            "directDryBanksAtLeast2": {"referenceCount": len(before_banks),
                                        "candidateCount": len(after_banks),
                                        "referenceExamples": before_banks[:128],
                                        "candidateExamples": after_banks[:128]},
            "examples": examples}


def parse_window(value: str) -> tuple[str, tuple[int, int, int, int]]:
    name, raw = value.split(":", 1)
    values = tuple(int(part) for part in raw.split(","))
    if len(values) != 4 or values[0] > values[2] or values[1] > values[3]:
        raise argparse.ArgumentTypeError("window must be name:x0,z0,x1,z1")
    return name, values


def report(reference_path: Path, candidate_path: Path, windows: dict[str, tuple[int, int, int, int]],
           low: int, high: int) -> dict:
    reference, candidate = SavedWorld(reference_path), SavedWorld(candidate_path)
    output = {"schema": 1, "kind": "paired-marsh-surface", "reference": str(reference_path.resolve()),
              "candidate": str(candidate_path.resolve()), "waterDefinition": sorted(WATER), "windows": {}}
    for name, bounds in windows.items():
        output["windows"][name] = compare_window(reference, candidate, bounds, low, high)
    if (reference.missing_chunks or reference.non_full_chunks or not reference.stable()
            or candidate.missing_chunks or candidate.non_full_chunks or not candidate.stable()):
        raise RuntimeError("both worlds must be stable and provide FULL chunks")
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--window", action="append", type=parse_window)
    parser.add_argument("--low", type=int, default=32); parser.add_argument("--high", type=int, default=128)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.low >= args.high or args.output.exists() or args.output.resolve().is_relative_to(args.reference.resolve()) or args.output.resolve().is_relative_to(args.candidate.resolve()):
        parser.error("need low < high and a new output outside both worlds")
    windows = dict(args.window) if args.window else DEFAULT_WINDOWS
    result = report(args.reference, args.candidate, windows, args.low, args.high)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value["exclusiveClasses"] for key, value in result["windows"].items()}))


if __name__ == "__main__":
    main()
