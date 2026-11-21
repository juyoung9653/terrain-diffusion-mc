"""Read-only fixed-outlet check for the R2 marsh crossing acceptance site.

It intentionally accepts no arbitrary route around the original dry strip.  A
candidate must take a 4-neighbor water route from the fixed pond source through
both two-cell gates at x=571..572/z=223 and z=222, then reach the existing brook
window.  Adjacent surface water levels may differ by at most one block.
"""
from __future__ import annotations

import argparse
from collections import deque
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from paired_marsh_surface import column
from measure import SavedWorld

BOUNDS = (568, 196, 575, 230)
SOURCE = {(572, 225)}
GATES = ({(571, 223), (572, 223)}, {(571, 222), (572, 222)})
OUTLET = {(x, z) for x in range(571, 573) for z in range(209, 212)}


def cells(world: SavedWorld) -> dict[tuple[int, int], dict]:
    x0, z0, x1, z1 = BOUNDS
    return {(x, z): column(world, x, z, 32, 128)
            for z in range(z0, z1 + 1) for x in range(x0, x1 + 1)}


def route(data: dict[tuple[int, int], dict]) -> dict:
    """Shortest in-window route that has visited source, both gates, and outlet."""
    start = [(point, 0) for point in SOURCE if data.get(point, {}).get("waterTop") is not None]
    queue = deque(start); prior = {state: None for state in start}; end = None
    while queue:
        (x, z), stage = queue.popleft()
        if stage == 2 and (x, z) in OUTLET:
            end = ((x, z), stage); break
        top = data[x, z]["waterTop"]
        for nx, nz in ((x - 1, z), (x + 1, z), (x, z - 1), (x, z + 1)):
            neighbor = data.get((nx, nz))
            if neighbor is None or neighbor.get("waterTop") is None:
                continue
            if abs(top - neighbor["waterTop"]) > 1:
                continue
            next_stage = stage
            if stage == 0 and (nx, nz) in GATES[0]:
                next_stage = 1
            if next_stage == 1 and (nx, nz) in GATES[1]:
                next_stage = 2
            state = ((nx, nz), next_stage)
            if state not in prior:
                prior[state] = ((x, z), stage); queue.append(state)
    if end is None:
        return {"passes": False, "path": []}
    path = []
    state = end
    while state is not None:
        path.append([state[0][0], state[0][1], data[state[0]]["waterTop"]])
        state = prior[state]
    path.reverse()
    return {"passes": True, "path": path,
            "maxAdjacentSurfaceDelta": max(abs(a[2] - b[2]) for a, b in zip(path, path[1:])) if len(path) > 1 else 0}


def profile(data: dict[tuple[int, int], dict], x: int) -> list[dict]:
    return [{"z": z, "waterTop": data[x, z]["waterTop"], "bedY": data[x, z]["bedY"],
             "bedBlock": data[x, z]["bedBlock"], "streamSurface": data[x, z]["hasStreamInSurfaceWater"]}
            for z in range(196, 231)]


def report(reference_path: Path, candidate_path: Path) -> dict:
    reference, candidate = SavedWorld(reference_path), SavedWorld(candidate_path)
    before, after = cells(reference), cells(candidate)
    result = {"schema": 1, "kind": "fixed-marsh-outlet", "bounds": list(BOUNDS),
              "reference": str(reference_path.resolve()), "candidate": str(candidate_path.resolve()),
              "source": sorted(SOURCE), "gates": [sorted(gate) for gate in GATES], "outlet": sorted(OUTLET),
              "referenceOriginal": {"sourceWet": all(before[p]["waterTop"] is not None for p in SOURCE),
                                    "stripDry": {f"{x},{z}": before[x, z]["waterTop"] is None
                                                 for z in (222, 223) for x in range(568, 576)}},
              "candidateRoute": route(after),
              "profiles": {"reference": {"x571": profile(before, 571), "x572": profile(before, 572)},
                           "candidate": {"x571": profile(after, 571), "x572": profile(after, 572)}}}
    if (reference.missing_chunks or reference.non_full_chunks or not reference.stable()
            or candidate.missing_chunks or candidate.non_full_chunks or not candidate.stable()):
        raise RuntimeError("both worlds must be stable and provide FULL chunks")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or args.output.resolve().is_relative_to(args.reference.resolve()) or args.output.resolve().is_relative_to(args.candidate.resolve()):
        parser.error("output must be a new file outside both worlds")
    data = report(args.reference, args.candidate)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passes": data["candidateRoute"]["passes"],
                      "pathCells": len(data["candidateRoute"]["path"])}))


if __name__ == "__main__":
    main()
