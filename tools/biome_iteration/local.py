"""Plan a small paired biome check or measure a previously generated candidate."""
from __future__ import annotations

import argparse
import gzip
import json
import re
from pathlib import Path

from measure import analyse, DEFAULT_MATERIALS, nbt_fields


def sites(case):
    rows = case["sites"]
    if not 2 <= len(rows) <= 12 or {r["role"] for r in rows} != {"reported", "control"}:
        raise ValueError("provide a reported site and a separate healthy control (2..12 sites)")
    names = set()
    for r in rows:
        bounds = r["bounds"]
        if (not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", r["id"]) or r["id"] in names
                or len(bounds) != 4 or any(type(v) is not int for v in bounds)
                or not 0 <= bounds[2] - bounds[0] < 128 or not 0 <= bounds[3] - bounds[1] < 128):
            raise ValueError("each site needs a unique name and bounded inclusive minX,minZ,maxX,maxZ")
        if r.get("coral") not in {None, "absent", "present"}:
            raise ValueError("unknown coral expectation")
        names.add(r["id"])
    if len({tuple(r["bounds"]) for r in rows}) != len(rows):
        raise ValueError("a control cannot duplicate the reported patch")
    return rows


def chunk_list(rows):
    return list(dict.fromkeys((x, z) for row in rows
                for x in range(row["bounds"][0] // 16, row["bounds"][2] // 16 + 1)
                for z in range(row["bounds"][1] // 16, row["bounds"][3] // 16 + 1)))

def verify_world(case, world):
    level = nbt_fields(gzip.decompress((world / "level.dat").read_bytes()))
    seed = level[("Data", "WorldGenSettings", "seed")][3]
    path = world / "data/terrain_diffusion_world_settings.dat"
    if not path.is_file():
        raise ValueError("missing saved TD scale: choose a fixture with explicit settings")
    scale = nbt_fields(gzip.decompress(path.read_bytes()))[("data", "scale")][3]
    if (seed, scale) != (case["seed"], case["scale"]):
        raise ValueError(f"world seed/scale differ from case: {seed} / {scale}")


def evaluate(rows, report):
    results = {}
    for r in rows:
        patch = report["candidate"][r["id"]]
        count = patch["livingCoralBlocks"]
        expected = r.get("coral")
        if (not report["candidateSourceRegionsStable"] or report["candidateMissingChunks"]
                or report["candidateNonFullChunks"] or patch["unreadableColumns"]):
            results[r["id"]] = "inconclusive"
        elif expected:
            results[r["id"]] = "matches" if (count > 0) == (expected == "present") else "mismatch"
        else:
            results[r["id"]] = "inspect-material-metrics"
    return results


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("case", type=Path)
    p.add_argument("--world", type=Path, help="already generated, closed fixture world (read-only)")
    p.add_argument("--compare", type=Path, help="optional saved baseline for comparison only")
    p.add_argument("--output", type=Path, help="new report file outside either world")
    args = p.parse_args()
    case = json.loads(args.case.read_text(encoding="utf-8"))
    rows = sites(case)
    xs = chunk_list(rows)
    print(f"{case['issue']}\nHypothesis: {case['hypothesis']}\nFalsifier: {case['disproof']}")
    print(f"Intended seed/scale: {case['seed']} / {case['scale']}; Streams: {case['streams']}")
    print("Target chunks for a fresh local fixture: " + ";".join(f"{x},{z}" for x, z in xs))
    if args.world is None:
        if args.compare or args.output:
            p.error("--compare and --output require --world")
        return 0
    if not args.output or any(args.output.resolve().is_relative_to(path.resolve())
                              for path in (args.world, args.compare) if path):
        p.error("use a new output file outside the world(s)")
    if args.output.exists():
        p.error("output exists")
    for world in (args.world, args.compare):
        if world:
            verify_world(case, world)
    report = analyse(args.world, [(r["id"], tuple(r["bounds"])) for r in rows],
                     set(DEFAULT_MATERIALS), *case["y"], args.compare)
    result = evaluate(rows, report)
    report.update(case=case, localExpectations=result, releaseEvidence=False)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(report, output, indent=2)
    print(json.dumps(result))
    return 1 if "mismatch" in result.values() else 2 if "inconclusive" in result.values() else 0


if __name__ == "__main__":
    raise SystemExit(main())
