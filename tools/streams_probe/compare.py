"""Compare waterway-on/off runs at identical coordinates, seed and base terrain."""
import argparse
import csv
import json
from pathlib import Path


def load(run):
    records = [json.loads(line) for line in (run / "world/td-streams-probe.jsonl").read_text(encoding="utf-8").splitlines()]
    complete = [r for r in records if r["event"] == "complete"]
    if not complete or complete[-1]["status"] != "success":
        raise ValueError(f"incomplete probe: {run.name}")
    chunks = [r for r in records if r["event"] == "chunk"]
    rows = {}
    for chunk in chunks:
        path = run / "world" / f"td-streams-probe-columns-{chunk['chunk'].replace(',', '-')}.csv"
        with path.open(encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                rows[(int(row["x"]), int(row["z"]))] = row
    return chunks, rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("enabled", type=Path)
    parser.add_argument("control", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    enabled, a = load(args.enabled)
    control, b = load(args.control)
    if not a or a.keys() != b.keys():
        raise ValueError("comparison requires matching, nonempty sampled coordinates")
    manifests = [json.loads((r / "input-manifest.json").read_text(encoding="utf-8")) for r in [args.enabled, args.control]]
    if manifests[0]["seed"] != manifests[1]["seed"]:
        raise ValueError("comparison seeds differ")
    differences = [key for key in a if a[key]["baseHeightOceanFloorWG"] != b[key]["baseHeightOceanFloorWG"]
                   or a[key]["naturalBiome"] != b[key]["naturalBiome"]]
    report = {"enabledRun": args.enabled.name, "controlRun": args.control.name,
              "seed": manifests[0]["seed"], "comparedColumns": len(a),
              "baseHeightOrBiomeDifferences": len(differences),
              "enabledWaterBlocks": sum(int(r["waterBlocks"]) for r in enabled),
              "controlWaterBlocks": sum(int(r["waterBlocks"]) for r in control),
              "enabledWaterReplacingSolid": sum(int(r["waterReplacingSolid"]) for r in enabled),
              "controlWaterReplacingSolid": sum(int(r["waterReplacingSolid"]) for r in control),
              "enabledSurfaceWaterColumns": sum(int(r["surfaceWaterColumns"]) for r in enabled),
              "controlSurfaceWaterColumns": sum(int(r["surfaceWaterColumns"]) for r in control),
              "newSurfaceWaterBelowControlBaseHeightColumns": sum(
                  int(a[k]["topWaterY"]) == int(a[k]["surfaceY"])
                  and int(a[k]["topWaterY"]) < int(b[k]["baseHeightOceanFloorWG"])
                  and int(b[k]["topWaterY"]) < int(b[k]["surfaceY"])
                  for k in a),
              "totalWaterCaveat": "Includes underground water; use matched surface columns for stream attribution.",
              "controlChange": ("Streams and lakes disabled together" if manifests[1].get("waterwaysDisabled")
                                else manifests[1].get("tagControl", "Not specified; inspect input manifests")),
              "attributionCaveat": "Base height is a terrain boundary, not a direct solidity measurement. Interpret differences using the recorded control change.",
              "naturalBiomes": sorted({r["naturalBiome"] for r in a.values()}),
              "matchingTerrain": not differences}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))
    if differences:
        raise SystemExit("base terrain differs: do not attribute all water differences to Streams")


if __name__ == "__main__":
    main()
