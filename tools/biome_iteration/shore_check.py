"""Measure the November shore case in the same fixed windows on both native saves.

The native census describes the settled window scan; the coral volume and
three-level support check read the final saved FULL blocks. Neither assigns
authorship to the writer or substitutes for looking at the material contact.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from measure import SavedWorld, components, diagonal_pairs, straight_contact_runs

SILT = "immersive_weathering:silt"
MUD = "minecraft:mud"
SAND = "minecraft:sand"
GRASS = "minecraft:grass_block"
SOIL = {GRASS, "minecraft:dirt", "minecraft:rooted_dirt", "minecraft:coarse_dirt",
        "minecraft:podzol", MUD, "minecraft:clay", SAND, "minecraft:sandstone", "minecraft:stone"}
REEFS = {"shallow-coral-a": (-1892,1572,10), "shallow-coral-b": (-1891,1582,10),
         "inspect-coral-depth": (-1892,1572,10), "coral-support-detail": (-1892,1572,10),
         "deep-coral-control": (-960,1648,16), "inspect-deep-coral-control": (-960,1648,16)}
TIP = {"grassy-shore-tip", "mixed-shore-layout", "inspect-grassy-shore-tip", "inspect-mixed-shore"}
POND = {"riparian-sand-pond", "inspect-pond-edge"}


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def coral_volume(world, bounds):
    x1,z1,x2,z2 = bounds
    counts = Counter()
    for z in range(z1,z2+1):
        for x in range(x1,x2+1):
            for y in range(44,64):
                state = world.state(x,y,z)
                if state is None:
                    raise ValueError(f"coral volume unreadable: {x},{y},{z}")
                block = state[0]
                name = block.removeprefix("minecraft:")
                if block.startswith("minecraft:") and name.split("_")[0] in {"tube","brain","bubble","fire","horn"} and name.endswith(
                        ("_coral","_coral_fan","_coral_wall_fan","_coral_block")):
                    counts[block] += 1
    return sum(counts.values())


def run(fixture, baseline_bundle):
    receipt = read(fixture/"receipt.json")
    result = read(fixture/"launch-result.json")
    if not (receipt.get("biomeVisualReviewSaveFlushed") and result.get("passed") and result.get("exit_code")==0):
        raise ValueError("requires a successful, flushed native fixture")
    capture = receipt["biomeVisualReview"]
    plan = read(capture["planPath"])
    reference = read(baseline_bundle)
    reference_plan = read(reference["plan"]["path"])
    if sha(reference["plan"]["path"]) != reference["plan"]["sha256"]:
        raise ValueError("reference plan changed")
    if (plan["seed"],plan["scale"],plan["windows"]) != (reference_plan["seed"],reference_plan["scale"],reference_plan["windows"]):
        raise ValueError("seed, scale, or observed window mismatch")
    fixed = {s["id"]:(s["x"],s["z"]) for s in reference["coverage"]}
    # Inspections may resolve to different targets in different candidates.
    for s in reference_plan.get("inspectionRequests", []):
        fixed[s["id"]] = s["x"],s["z"]
    columns_path = Path(capture["columnsPath"])
    rows = {(c["x"],c["z"]):c for c in read(columns_path)["columns"]}
    world = SavedWorld(fixture/"saves"/receipt["world"])
    sites = []
    for site,(x,z) in fixed.items():
        w = next(w for w in plan["windows"] if w["minX"]<=x<=w["maxX"] and w["minZ"]<=z<=w["maxZ"])
        if site in REEFS:
            x1,z1,size = REEFS[site]
            bounds = [x1,z1,x1+size-1,z1+size-1]
        else:
            radius = 22 if site in POND else 12
            bounds = [max(w["minX"],x-radius),max(w["minZ"],z-radius),
                      min(w["maxX"],x+radius),min(w["maxZ"],z+radius)]
        x1,z1,x2,z2 = bounds
        patch = {(a,b):rows[a,b] for a in range(x1,x2+1) for b in range(z1,z2+1)}
        bed = {p:c["bedBlock"] for p,c in patch.items()}
        wet = {p:c["bedBlock"] for p,c in patch.items() if c["exposedWaterBed"]}
        m = {"columns":len(patch),"anchorGroundY":rows[x,z]["groundY"],"anchorWaterDepth":rows[x,z]["waterDepth"],
             "exposedWetColumns":len(wet),"exposedWetSand":sum(v==SAND for v in wet.values()),
             "exposedWetMudOrClay":sum(v in (MUD,"minecraft:clay") for v in wet.values())}
        silt = components(wet,SILT)
        m.update(exposedWetSilt=sum(v==SILT for v in wet.values()),largestWetSiltPatch=silt["largest"],
                 wetSiltSingletons=silt["singletons"],wetMudDiagonalSquares=diagonal_pairs(wet,MUD),
                 wetSiltStraightContact=max(straight_contact_runs(wet,SILT).values()))
        if site in REEFS:
            m["savedLivingVanillaCoralBlocksY44To63"] = coral_volume(world,bounds)
        if site in TIP:
            turf = {p:v for p,v in bed.items() if patch[p]["waterDepth"]==0 and v==GRASS}
            m["dryTurfColumns"] = len(turf)
            m["dryTurfSingletons"] = components(turf,GRASS)["singletons"]
            m["turfSupportedThreeBlocksBelow"] = sum(all(
                (world.state(a,patch[a,b]["groundY"]-i,b) or ("unknown",))[0] in SOIL for i in (1,2,3)) for a,b in turf)
            m["groundMaterialAtAnchor"] = bed[x,z]
        sites.append({"id":site,"x":x,"z":z,"bounds":bounds,"metrics":m})
    if world.missing_chunks or world.non_full_chunks or not world.stable():
        raise ValueError("incomplete or changing saved world")
    return {"schema":1,"kind":"scoped-biome-baseline-facts","seed":plan["seed"],"scale":plan["scale"],
            "artifactSha256":capture["artifactSha256"],"sourceFingerprint":capture["sourceFingerprint"],
            "source":{"columnsSha256":sha(columns_path),"regionSha256":dict(sorted(world.hashes.items()))},
            "sites":sites}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture",type=Path)
    parser.add_argument("baseline_bundle",type=Path)
    parser.add_argument("output",type=Path)
    args = parser.parse_args()
    data = run(args.fixture,args.baseline_bundle)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open("x",encoding="utf-8") as out:
        json.dump(data,out,indent=2)
    print(f"Measured {len(data['sites'])} windows: {args.output}")
