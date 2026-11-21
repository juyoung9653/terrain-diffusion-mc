"""Read a saved wetland around a reported river crossing without changing it.

The report deliberately separates a natural shallow pond from a low, narrow
river cut.  It reads only FULL Anvil chunks and records enough raw columns to
review whether a stream was incorrectly carved *through* standing marsh water.
"""
from __future__ import annotations

import argparse
from collections import Counter, deque
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from measure import AIR, SavedWorld

STREAM = "streamsreflowing:stream"
VANILLA_WATER = {"minecraft:water", "minecraft:flowing_water"}
SOFT = ("short_grass", "tall_grass", "fern", "flower", "vine", "bush", "sapling",
        "lily_pad", "sugar_cane", "seagrass", "kelp", "moss_carpet", "propagule",
        "greeneries:bushy_grass", "greeneries:medium_grass")


def wet(state: tuple[str, bool] | None) -> bool:
    return state is not None and (state[1] or state[0] in VANILLA_WATER or state[0] == STREAM)


def passable(block: str) -> bool:
    return block in AIR or any(word in block.removeprefix("minecraft:") for word in SOFT)


def column(world: SavedWorld, x: int, z: int, low: int, high: int) -> dict:
    states = {y: world.state(x, y, z) for y in range(low, high + 1)}
    if any(state is None for state in states.values()):
        return {"x": x, "z": z, "readable": False}
    wet_ys = [y for y, state in states.items() if wet(state)]
    stream_ys = [y for y, state in states.items() if state[0] == STREAM]
    top = max(wet_ys) if wet_ys else None
    bed = None
    if wet_ys:
        for y in range(min(wet_ys) - 1, low - 1, -1):
            state = states[y]
            if not wet(state) and not passable(state[0]):
                bed = y
                break
    ground = max((y for y, state in states.items() if not wet(state) and not passable(state[0])
                  and "_leaves" not in state[0] and "_log" not in state[0]), default=None)
    # A wet block below a solid ceiling is groundwater/cave water.  It must not
    # connect a surface-marsh component to a stream or an ocean in this report.
    exposed_top = top if top is not None and (ground is None or top >= ground) else None
    return {"x": x, "z": z, "readable": True, "waterTop": top, "bedY": bed,
            "groundY": ground, "exposedWaterTop": exposed_top, "streamY": stream_ys,
            "vanillaWaterY": [y for y, state in states.items() if state[0] in VANILLA_WATER],
            "stack": [{"y": y, "block": state[0], "waterlogged": state[1]}
                      for y, state in states.items() if state[0] not in AIR]}


def components(cells: dict[tuple[int, int], dict]) -> list[list[tuple[int, int]]]:
    unseen = {point for point, value in cells.items() if value.get("exposedWaterTop") is not None}
    output: list[list[tuple[int, int]]] = []
    while unseen:
        point = unseen.pop(); todo = deque([point]); component = [point]
        while todo:
            x, z = todo.popleft()
            for next_point in ((x - 1, z), (x + 1, z), (x, z - 1), (x, z + 1)):
                if next_point in unseen:
                    unseen.remove(next_point); todo.append(next_point); component.append(next_point)
        output.append(component)
    return sorted(output, key=len, reverse=True)


def nearest_plane(cells: dict[tuple[int, int], dict], x: int, z: int, radius: int) -> int | None:
    # A ring avoids accidentally using the stream column's own low surface as
    # its reference.  Require two water columns to keep isolated puddles out.
    for distance in range(1, radius + 1):
        tops = []
        for dx in range(-distance, distance + 1):
            dz = distance - abs(dx)
            for sign in (-1, 1) if dz else (1,):
                cell = cells.get((x + dx, z + sign * dz))
                if cell and cell.get("exposedWaterTop") is not None:
                    tops.append(cell["exposedWaterTop"])
        if len(tops) >= 2:
            return Counter(tops).most_common(1)[0][0]
    return None


def report(world_path: Path, x: int, z: int, radius: int, low: int, high: int) -> dict:
    world = SavedWorld(world_path)
    bounds = (x - radius, z - radius, x + radius, z + radius)
    cells = {(px, pz): column(world, px, pz, low, high)
             for pz in range(bounds[1], bounds[3] + 1)
             for px in range(bounds[0], bounds[2] + 1)}
    streams = [cell for cell in cells.values() if cell.get("streamY")]
    exposed = [cell for cell in cells.values() if cell.get("exposedWaterTop") is not None]
    low_streams, crossings, low_wet, steep_banks = [], [], [], []
    for cell in streams:
        reference = nearest_plane(cells, cell["x"], cell["z"], 8)
        if reference is None:
            continue
        top = max(cell["streamY"])
        entry = {"x": cell["x"], "z": cell["z"], "streamTop": top,
                 "nearbyStandingWaterTop": reference, "difference": reference - top,
                 "bedY": cell["bedY"]}
        if reference - top >= 2:
            low_streams.append(entry)
        if cell["vanillaWaterY"] and reference - top >= 1:
            crossings.append(entry)
    # This is deliberately source-agnostic: late repairs can replace a Streams
    # block with vanilla water.  Treat it as an inspection candidate rather than
    # assigning a writer.  The comparison ring supplies the adjacent standing
    # marsh plane seen by the player.
    for cell in exposed:
        reference = nearest_plane(cells, cell["x"], cell["z"], 8)
        top = cell["exposedWaterTop"]
        if reference is not None and reference - top >= 2:
            low_wet.append({"x": cell["x"], "z": cell["z"], "waterTop": top,
                            "nearbyStandingWaterTop": reference,
                            "difference": reference - top, "bedY": cell["bedY"],
                            "hasStreamBlock": bool(cell["streamY"])})
        # A still-water marsh naturally has a one-block lip.  A drop of three or
        # more from a dry neighboring surface is the visual signature of the
        # carved terraces in the reported view.  It remains a raw observation;
        # natural ravines need visual review before a writer is blamed.
        for nx, nz in ((cell["x"] - 1, cell["z"]), (cell["x"] + 1, cell["z"]),
                       (cell["x"], cell["z"] - 1), (cell["x"], cell["z"] + 1)):
            neighbor = cells.get((nx, nz))
            if not neighbor or neighbor.get("exposedWaterTop") is not None:
                continue
            bank_y = neighbor.get("groundY")
            if bank_y is None or bank_y - top < 3:
                continue
            nearby_stream = any(cells.get((sx, sz), {}).get("streamY")
                                for sx in range(cell["x"] - 8, cell["x"] + 9)
                                for sz in range(cell["z"] - 8, cell["z"] + 9))
            steep_banks.append({"waterX": cell["x"], "waterZ": cell["z"],
                                "waterTop": top, "bankX": nx, "bankZ": nz,
                                "bankY": bank_y, "drop": bank_y - top,
                                "nearbyStreamBlock": nearby_stream})
    component_rows = []
    for component in components(cells):
        sample = [cells[point] for point in component]
        tops = Counter(c["exposedWaterTop"] for c in sample)
        stream_count = sum(bool(c["streamY"]) for c in sample)
        component_rows.append({"columns": len(component),
                               "bounds": [min(p[0] for p in component), min(p[1] for p in component),
                                          max(p[0] for p in component), max(p[1] for p in component)],
                               "waterTopHistogram": dict(sorted(tops.items())),
                               "waterTopSpan": max(tops) - min(tops),
                               "streamColumns": stream_count})
    result = {"schema": 1, "kind": "wetland-channel-profile", "world": str(world_path.resolve()),
              "anchor": {"x": x, "z": z}, "bounds": list(bounds),
              "surface": {"wetColumns": sum(c.get("waterTop") is not None for c in cells.values()),
                          "exposedWetColumns": len(exposed),
                          "streamColumns": len(streams),
                          "waterTopHistogram": dict(sorted(Counter(c["exposedWaterTop"] for c in exposed).items()))},
              "components": component_rows[:12],
              "lowStreamAgainstStandingWater": low_streams[:256],
              "streamInStandingWaterCandidates": crossings[:256],
              "lowWetAgainstStandingWaterCandidates": low_wet[:256],
              "steepBankCandidates": steep_banks[:256],
              "candidateCounts": {"lowStreamAgainstStandingWater": len(low_streams),
                                  "streamInStandingWater": len(crossings),
                                  "lowWetAgainstStandingWater": len(low_wet),
                                  "steepBanks": len(steep_banks)},
              "anchorColumn": cells[x, z],
              "acceptance": {"noLowRiver": "lowStreamAgainstStandingWater must be empty",
                             "noWetlandTraversal": "streamInStandingWaterCandidates must be empty",
                             "noArtificialWaterTrench": "lowWetAgainstStandingWaterCandidates must be empty after visual review",
                             "noForcedTerrace": "steepBankCandidates near the channel must be absent or explained by natural terrain",
                             "standingWaterPreserved": "compare component count and dominant planes with a non-contact control"}}
    if world.missing_chunks or world.non_full_chunks or not world.stable():
        raise RuntimeError("requires stable, FULL saved chunks")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True)
    parser.add_argument("--x", type=int, required=True)
    parser.add_argument("--z", type=int, required=True)
    parser.add_argument("--radius", type=int, default=80)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--low", type=int, default=32)
    parser.add_argument("--high", type=int, default=128)
    args = parser.parse_args()
    if args.radius < 8 or args.radius > 128 or args.low >= args.high:
        parser.error("radius must be 8..128 and low < high")
    if args.output.exists() or args.output.resolve().is_relative_to(args.world.resolve()):
        parser.error("output must be a new file outside the world")
    data = report(args.world, args.x, args.z, args.radius, args.low, args.high)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"wet": data["surface"]["wetColumns"], "streams": data["surface"]["streamColumns"],
                      "low": len(data["lowStreamAgainstStandingWater"]),
                      "crossings": len(data["streamInStandingWaterCandidates"])}))


if __name__ == "__main__":
    main()
