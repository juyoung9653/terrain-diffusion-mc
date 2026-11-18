"""Package native biome capture, measured maps and source diff for independent review."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import subprocess
import shutil
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from biome_review.contract import (digest, file_ref, load, review_tool_fingerprint,
                                   source_fingerprint, validate_bundle)


def save(path: Path, row: dict) -> None:
    path.write_text(json.dumps(row, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def physical_summary(columns: list[dict]) -> dict:
    by_biome = defaultdict(list)
    grid = {(col["x"], col["z"]): col for col in columns}
    for col in columns:
        by_biome[col["biome"]].append(col)
    result = {}
    for biome, rows in sorted(by_biome.items()):
        water = [r for r in rows if r.get("waterDepth", 0) > 0]
        exposed = [r for r in water if r.get("exposedWaterBed", False)]
        covered = [r for r in water if not r.get("exposedWaterBed", False)]
        grounded_logs = []
        for row in rows:
            # Exclude overhead branches and mangrove arches: this describes a
            # log immediately above measured soil, not every log in a column.
            if not row.get("lowestLogBlock") or row.get("lowestLogY") != row["groundY"] + 1:
                continue
            neighbors = [grid.get((row["x"] + dx, row["z"] + dz))
                         for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1))]
            if any(neighbor is None for neighbor in neighbors):
                continue
            grounded_logs.append({
                "x": row["x"], "z": row["z"], "groundY": row["groundY"],
                "logBlock": row["lowestLogBlock"], "supportBlock": row.get("supportBlock"),
                "maximumAdjacentGroundDrop": max(0, *(row["groundY"] - n["groundY"] for n in neighbors)),
                "neighbors": [{"x": n["x"], "z": n["z"], "groundY": n["groundY"],
                               "bedBlock": n["bedBlock"], "waterDepth": n["waterDepth"]}
                              for n in neighbors],
            })
        result[biome] = {
            "columns": len(rows), "waterColumns": len(water),
            "exposedSubmergedBeds": dict(Counter(r["bedBlock"] for r in exposed)),
            "waterloggedCoveredSubstrates": dict(Counter(
                f"{r['bedBlock']} below {r.get('coveringBlock', 'unknown')}" for r in covered)),
            "surfaceMaterials": dict(Counter(r["bedBlock"] for r in rows)),
            "waterDepths": dict(Counter(r.get("waterDepth", 0) for r in water)),
            "cardinalGroundSteps": dict(Counter(r.get("cardinalHeightDelta", 0) for r in rows)),
            "underwaterGrassExamples": [r for r in exposed if r["bedBlock"] == "minecraft:grass_block"][:20],
            "underwaterDirtExamples": [r for r in exposed if r["bedBlock"] == "minecraft:dirt"][:20],
            "observedLogSupports": dict(Counter(
                f"{r['lowestLogBlock']} on {r.get('supportBlock', 'unknown')}"
                for r in rows if r.get("lowestLogBlock"))),
            "groundedLogAdjacentDrops": dict(Counter(r["maximumAdjacentGroundDrop"] for r in grounded_logs)),
            "largestGroundedLogDropExamples": sorted(
                grounded_logs, key=lambda r: -r["maximumAdjacentGroundDrop"])[:12],
            "largestGroundStepExamples": sorted(rows, key=lambda r: -r.get("cardinalHeightDelta", 0))[:12],
        }
    return {"scope": "Measured columns; descriptions are not realism approval or feature attribution.",
            "byBiome": result}


MATERIALS = {"minecraft:mud": (82, 78, 69), "minecraft:dirt": (150, 105, 68),
             "minecraft:grass_block": (74, 145, 60), "minecraft:sand": (231, 216, 155),
             "minecraft:clay": (146, 157, 173), "minecraft:gravel": (128, 126, 122),
             "minecraft:stone": (105, 105, 108), "minecraft:coarse_dirt": (130, 90, 56)}


def make_map(columns: list[dict], plan: dict, sites: list[dict], output: Path) -> Path:
    from PIL import Image, ImageDraw
    windows = plan["windows"]
    scale = 2
    panel_width = max(w["maxX"] - w["minX"] + 1 for w in windows) * scale
    legend_height = 18 * len(sites) + 20
    total_height = 100 + legend_height + sum((w["maxZ"] - w["minZ"] + 1) * scale + 55 for w in windows)
    canvas = Image.new("RGB", (panel_width * 3 + 40, total_height), "white")
    draw = ImageDraw.Draw(canvas)
    draw.text((12, 8), "ACTUAL GENERATED COLUMNS: elevation | ground material | water depth (blue) / biome (dry)", fill="black")
    draw.text((12, 25), "Material: mud=dark grey; dirt=brown; grass=green; sand=tan; clay=blue-grey; unknown=magenta", fill="black")
    draw.text((12, 42), "Black crosses = photographed sites. X increases right, Z increases downward. Maps are not screenshots.", fill="black")
    grid = {(c["x"], c["z"]): c for c in columns}
    top = 80
    for window in windows:
        x0, x1, z0, z1 = (window[k] for k in ("minX", "maxX", "minZ", "maxZ"))
        width, height = x1 - x0 + 1, z1 - z0 + 1
        rows = [c for c in columns if x0 <= c["x"] <= x1 and z0 <= c["z"] <= z1]
        low = min(c["groundY"] for c in rows)
        high = max(c["groundY"] for c in rows)
        draw.text((12, top), f"{window['id']}: X {x0}..{x1}, Z {z0}..{z1}; ground Y {low}..{high}", fill="black")
        for panel in range(3):
            tile = Image.new("RGB", (width, height), (255, 0, 255))
            for z in range(z0, z1 + 1):
                for x in range(x0, x1 + 1):
                    col = grid.get((x, z))
                    if col is None:
                        continue
                    if panel == 0:
                        grey = int(40 + 200 * (col["groundY"] - low) / max(1, high - low))
                        color = (grey, grey, grey)
                    elif panel == 1:
                        color = MATERIALS.get(col["bedBlock"], (210, 70, 190))
                    elif col.get("waterDepth", 0):
                        color = (30, max(30, 180 - col["waterDepth"] * 12), 225)
                    else:
                        import hashlib
                        h = hashlib.sha256(col["biome"].encode()).digest()
                        color = tuple(80 + b % 100 for b in h[:3])
                    tile.putpixel((x - x0, z - z0), color)
            left = 12 + panel * panel_width
            canvas.paste(tile.resize((width * scale, height * scale), Image.Resampling.NEAREST), (left, top + 20))
            for number, site in enumerate(sites, 1):
                if x0 <= site["x"] <= x1 and z0 <= site["z"] <= z1:
                    sx, sz = left + (site["x"] - x0) * scale, top + 20 + (site["z"] - z0) * scale
                    draw.line((sx - 4, sz, sx + 4, sz), fill="black", width=2)
                    draw.line((sx, sz - 4, sx, sz + 4), fill="black", width=2)
                    draw.text((sx + 5, sz - 9), str(number), fill="black", stroke_width=1, stroke_fill="white")
        top += height * scale + 55
    for number, site in enumerate(sites, 1):
        draw.text((12, top), f"{number}: {site['id']}  ({site['x']}, {site['z']})", fill="black")
        top += 18
    path = output / "overview.png"
    canvas.save(path)
    return path


def build(fixture: Path, output: Path, *, restart: bool = False) -> Path:
    fixture, output = fixture.resolve(), output.resolve()
    if not fixture.is_relative_to(ROOT) or not output.is_relative_to(ROOT):
        raise ValueError("Use workspace fixture and output paths")
    if output.exists():
        raise ValueError("Retain existing bundle; use a new output directory")
    prefix = "restart-" if restart else ""
    receipt_path = fixture / (prefix + "receipt.json")
    launch_path = fixture / (prefix + "launch-result.json")
    receipt = load(receipt_path)
    capture = receipt.get("biomeVisualReview", {})
    if capture.get("status") != "captured":
        raise ValueError("Native biome capture is incomplete")
    plan_path = Path(capture["planPath"])
    plan = load(plan_path)
    if capture.get("sourceFingerprint") != source_fingerprint(ROOT):
        raise ValueError("Native biome capture belongs to earlier sources")
    columns_path = Path(capture["columnsPath"])
    columns = load(columns_path)["columns"]
    manifest = load(fixture / "input-manifest.json")
    candidate_jar = Path(manifest["terrain_diffusion_artifact"]["path"])
    candidate_sidecar = candidate_jar.with_name(candidate_jar.name + ".validation.json")
    candidate_build = load(candidate_sidecar)
    if (candidate_build.get("modSha256") != capture["artifactSha256"]
            or candidate_build.get("sourceFingerprint") != capture["sourceFingerprint"]):
        raise ValueError("Candidate build receipt does not match native capture")
    output.mkdir(parents=True)
    # Preserve development provenance when a later release build updates its sidecar.
    build_path = output / "candidate-build.json"
    shutil.copy2(candidate_sidecar, build_path)
    summary_path = output / "physical-summary.json"
    save(summary_path, physical_summary(columns))
    overview = make_map(columns, plan, capture["sites"], output)
    # Includes uncommitted changes. The reviewer must evaluate scope, not accept
    # the implementer's statement that only a particular biome is affected.
    diff = subprocess.run(["git", "diff", "--", "common/src", "neoforge/src"], cwd=ROOT,
                          capture_output=True, check=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    diff_path = output / "production.diff"
    diff_path.write_bytes(diff.stdout)
    untracked = subprocess.run(["git", "ls-files", "--others", "--exclude-standard", "--", "common/src", "neoforge/src"],
                              cwd=ROOT, capture_output=True, check=True,
                              creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    untracked_path = output / "untracked-production.json"
    save(untracked_path, {"files": [file_ref(ROOT / name) for name in untracked.stdout.decode("utf-8").splitlines()]})
    images = [dict(file_ref(image["path"]), id=site["id"] + ":" + image["view"],
                   siteId=site["id"], biome=site["biome"], view=image["view"],
                   x=site["x"], z=site["z"], camera=image.get("camera"))
              for site in capture["sites"] for image in site["images"]]
    bundle = {"schema": 1, "kind": "biome-review-bundle",
              "goal": "Realistic coherent terrain, hydrology, substrate and vegetation; unexpected defects also block acceptance.",
              "sourceFingerprint": capture["sourceFingerprint"], "artifactSha256": capture["artifactSha256"],
              "toolFingerprint": review_tool_fingerprint(ROOT),
              "requiredBiomes": capture["requiredBiomes"], "coverage": capture["sites"],
              "fixture": file_ref(fixture / "input-manifest.json"), "launch": file_ref(launch_path),
              "capture": file_ref(receipt_path), "plan": file_ref(plan_path), "images": images,
              "candidateBuild": file_ref(build_path),
              "overviewImages": [dict(file_ref(overview), id="measured-overview")],
              "evidence": [file_ref(p) for p in [columns_path, Path(capture["selectionPath"]), summary_path,
                                                 diff_path, untracked_path]]}
    path = output / "bundle.json"
    save(path, bundle)
    validate_bundle(path, ROOT)
    return path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--restart", action="store_true")
    args = parser.parse_args()
    print(build(args.fixture, args.output, restart=args.restart))
