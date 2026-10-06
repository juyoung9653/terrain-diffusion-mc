#!/usr/bin/env python3
"""Build, sample, audit, and render the synthetic offline ecology atlas.

The output exercises production Java APIs without launching Minecraft. It is a
diagnostic input sweep, not an ONNX-generated world, saved-world observation, or
record of successful tree placements.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / "build" / "ecology-audit"
PROFILES = (
    "temperate_forest", "temperate_woodland", "temperate_grassland",
    "boreal_forest", "boreal_woodland", "cold_grassland",
    "dry_woodland", "dry_grassland", "tropical_forest", "tropical_woodland", "desert",
    "snowy_boreal_forest", "temperate_rainforest", "montane_forest", "steppe",
)
SEEDS = (91357, 0, -71421)
SCALES = (2, 5)
SURFACE_COLORS = {
    "GRASS": (105, 155, 72), "COARSE_DIRT": (121, 85, 58),
    "SAND": (221, 207, 135), "PODZOL": (91, 65, 43),
}
BIOME_COLORS = {
    name: color for name, color in zip(PROFILES, (
        (38, 112, 58), (83, 137, 73), (140, 172, 83),
        (42, 90, 79), (69, 119, 102), (153, 177, 127),
        (155, 132, 70), (194, 166, 87), (25, 127, 64),
        (71, 151, 72), (225, 201, 118),
        (178, 207, 205), (30, 100, 64), (78, 113, 91), (182, 181, 102),
    ))
}
JAVA_SOURCES = (
    "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/FastNoiseLite.java",
    "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/TerralithBiomeClassifier.java",
    "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/ShoreClassifier.java",
    "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/EcologyClassifier.java",
    "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/EcologyShowcaseProfile.java",
    "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/ecology/FeatureEcologyMath.java",
    "tools/ecology/EcologyOfflineAtlas.java",
)


def java_tools(java_home: Path | None = None) -> tuple[Path, Path]:
    suffix = ".exe" if os.name == "nt" else ""
    candidates: list[Path] = []
    if java_home:
        candidates.append(java_home)
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    if os.name == "nt":
        candidates.extend((
            Path(r"C:\Program Files\Java\jdk-21"),
            Path(r"C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"),
            Path(r"C:\Program Files\Android\Android Studio\jbr"),
        ))
    for candidate in candidates:
        javac, java = candidate / "bin" / ("javac" + suffix), candidate / "bin" / ("java" + suffix)
        if javac.is_file() and java.is_file():
            return javac, java
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        raise RuntimeError("JDK with javac and java is required; pass --java-home")
    return Path(javac), Path(java)


def compile_sampler(classes: Path, javac: Path) -> None:
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [str(javac), "--release", "21", "-encoding", "UTF-8", "-d", str(classes),
         *(str(ROOT / source) for source in JAVA_SOURCES)],
        cwd=ROOT, check=True,
    )


def run_sampler(output: Path, classes: Path, java: Path, size: int, model_step: int) -> None:
    classpath = os.pathsep.join((str(classes), str(ROOT / "common/src/main/resources")))
    subprocess.run(
        [str(java), "-cp", classpath, "EcologyOfflineAtlas", str(output), str(size), str(model_step)],
        cwd=ROOT, check=True,
    )


def tree_color(value: float) -> tuple[int, int, int]:
    amount = max(0.0, min(1.0, value / 0.5))
    return (int(244 - 211 * amount), int(241 - 94 * amount), int(226 - 174 * amount))


def load_cells(path: Path, size: int):
    grids: dict[tuple[int, int], dict[str, dict[str, list[list[object]]]]] = {}
    with path.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            combo = int(row["seed"]), int(row["scale"])
            profile = row["profile"]
            profiles = grids.setdefault(combo, {})
            if profile not in profiles:
                profiles[profile] = {
                    "current": [[None] * size for _ in range(size)],
                    "legacy": [[None] * size for _ in range(size)],
                    "tree": [[None] * size for _ in range(size)],
                }
            data = profiles[profile]
            x, y = int(row["col"]), int(row["row"])
            data["current"][y][x] = row["current_surface"]
            data["legacy"][y][x] = row["legacy_td_surface"]
            data["tree"][y][x] = float(row["tree_probability"])
    return grids


def render_atlas(output: Path, grids, size: int) -> None:
    columns, gap, magnify = 4, 12, 2
    panel_rows = (len(PROFILES) + columns - 1) // columns
    for combo, profiles in sorted(grids.items()):
        seed, scale = combo
        for field, palette in (("current", SURFACE_COLORS), ("legacy", SURFACE_COLORS), ("tree", None)):
            panel_px = size * magnify
            header, label_height, footer = 42, 18, 46
            width = columns * panel_px + (columns - 1) * gap
            height = header + panel_rows * (panel_px + label_height) + (panel_rows - 1) * gap + footer
            image = Image.new("RGB", (width, height), (238, 238, 238))
            draw = ImageDraw.Draw(image)
            font = ImageFont.load_default()
            kind = {"current": "CURRENT PRODUCTION SURFACE", "legacy": "LEGACY TD-ONLY BASELINE SURFACE",
                    "tree": "TREE PER-ATTEMPT PROBABILITY"}[field]
            draw.text((4, 4), f"SYNTHETIC DIAGNOSTIC - {kind}", fill=(15, 15, 15), font=font)
            draw.text((4, 20), f"seed={seed}  terrain scale={scale}  not ONNX terrain / not observed placements",
                      fill=(45, 45, 45), font=font)
            for index, profile in enumerate(PROFILES):
                left = (index % columns) * (panel_px + gap)
                top = header + (index // columns) * (panel_px + label_height + gap)
                panel = Image.new("RGB", (size, size))
                panel_pixels = panel.load()
                for y, row in enumerate(profiles[profile][field]):
                    for x, value in enumerate(row):
                        panel_pixels[x, y] = tree_color(value) if palette is None else palette[value]
                image.paste(panel.resize((panel_px, panel_px), Image.Resampling.NEAREST), (left, top))
                draw.text((left + 2, top + panel_px + 2), profile, fill=(20, 20, 20), font=font)
            legend_y = height - footer + 6
            if palette:
                x = 4
                for label, color in palette.items():
                    draw.rectangle((x, legend_y, x + 13, legend_y + 13), fill=color)
                    draw.text((x + 18, legend_y + 1), label, fill=(20, 20, 20), font=font)
                    x += 112
                if field == "legacy":
                    draw.text((4, legend_y + 20), "Historical surfaceMoisture thresholds; excludes IW/mod interactions.",
                              fill=(45, 45, 45), font=font)
            else:
                for x in range(200):
                    draw.line((4 + x, legend_y, 4 + x, legend_y + 12), fill=tree_color(0.5 * x / 199))
                draw.text((4, legend_y + 16), "0.0", fill=(20, 20, 20), font=font)
                draw.text((185, legend_y + 16), "0.5", fill=(20, 20, 20), font=font)
            image.save(output / f"{field}-atlas-seed-{seed}-scale-{scale}.png")
    with (output / "atlas-layout.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(("panel", "profile", "column", "row"))
        for index, profile in enumerate(PROFILES):
            writer.writerow((index, profile, index % columns, index // columns))


def render_climate(output: Path) -> None:
    with (output / "climate-grid.csv").open(newline="", encoding="utf-8") as stream:
        records = list(csv.DictReader(stream))
    temperatures = sorted({float(row["temperature"]) for row in records})
    moistures = sorted({float(row["moisture"]) for row in records}, reverse=True)
    lookup = {(float(row["temperature"]), float(row["moisture"])): row["biome_label"] for row in records}
    cell, left, top, legend_height = 20, 44, 42, 84
    image = Image.new("RGB", (max(600, left + len(temperatures) * cell), top + len(moistures) * cell + legend_height),
                      (240, 240, 240))
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default()
    draw.text((4, 4), "SYNTHETIC DIAGNOSTIC - ECOLOGY CLASSIFIER CLIMATE LABELS", fill=(15, 15, 15), font=font)
    draw.text((4, 20), "temperature left-to-right; moisture bottom-to-top; slope=0.1", fill=(45, 45, 45), font=font)
    for y, moisture in enumerate(moistures):
        for x, temperature in enumerate(temperatures):
            color = BIOME_COLORS[lookup[(temperature, moisture)]]
            draw.rectangle((left + x * cell, top + y * cell, left + (x + 1) * cell - 1,
                            top + (y + 1) * cell - 1), fill=color)
    draw.text((2, top), f"{moistures[0]:.1f}", fill=(20, 20, 20), font=font)
    draw.text((2, top + (len(moistures) - 1) * cell), f"{moistures[-1]:.1f}", fill=(20, 20, 20), font=font)
    draw.text((left, top + len(moistures) * cell + 2), f"{temperatures[0]:.0f} C", fill=(20, 20, 20), font=font)
    draw.text((left + (len(temperatures) - 2) * cell, top + len(moistures) * cell + 2),
              f"{temperatures[-1]:.0f} C", fill=(20, 20, 20), font=font)
    legend_y = top + len(moistures) * cell + 24
    for index, profile in enumerate(PROFILES):
        x = 4 + (index % 4) * 142
        y = legend_y + (index // 4) * 18
        draw.rectangle((x, y, x + 11, y + 11), fill=BIOME_COLORS[profile])
        draw.text((x + 15, y), profile, fill=(20, 20, 20), font=font)
    image.save(output / "climate-label-grid.png")


def audit(output: Path) -> dict:
    with (output / "summary.csv").open(newline="", encoding="utf-8") as stream:
        summaries = list(csv.DictReader(stream))
    checks: dict[str, bool] = {}
    keys = {(row["profile"], int(row["seed"]), int(row["scale"])) for row in summaries}
    checks["all_profiles_all_seeds_and_scales"] = keys == {
        (profile, seed, scale) for profile in PROFILES for seed in SEEDS for scale in SCALES
    }
    checks["tree_probabilities_bounded_0_to_0_5"] = all(
        0 <= float(row["min_tree_probability"]) <= float(row["max_tree_probability"]) <= 0.5
        for row in summaries
    )
    checks["current_material_fractions_sum_to_one"] = all(
        abs(sum(float(row[f"current_{material}_fraction"]) for material in
                ("grass", "coarse_dirt", "sand", "podzol")) - 1) < 1e-8 for row in summaries
    )
    checks["legacy_material_fractions_sum_to_one"] = all(
        abs(sum(float(row[f"legacy_{material}_fraction"]) for material in
                ("grass", "coarse_dirt", "sand")) - 1) < 1e-8 for row in summaries
    )
    checks["expected_profile_biome_ids"] = all(int(row["biome_id"]) == PROFILES.index(row["profile"]) + 1
                                                 for row in summaries)
    dry = [row for row in summaries if row["profile"] == "dry_grassland"]
    checks["dry_grassland_current_is_grass_dominated_with_limited_bare_patches"] = all(
        float(row["current_grass_fraction"]) > 0.80
        and 0.01 < float(row["current_coarse_dirt_fraction"]) < 0.20 for row in dry
    )
    checks["desert_current_is_sand"] = all(float(row["current_sand_fraction"]) == 1
                                             for row in summaries if row["profile"] == "desert")
    checks["cold_and_tropical_grasslands_avoid_arid_surface"] = all(
        float(row["current_coarse_dirt_fraction"]) == 0 and float(row["current_sand_fraction"]) == 0
        for row in summaries if row["profile"] in ("cold_grassland", "tropical_forest"))

    # Broad climate/groves scale with terrain. Fine soil islands deliberately cap their block size.
    scale_hashes: dict[tuple[str, int, int], object] = {}
    with (output / "cells.csv").open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            key = row["profile"], int(row["seed"]), int(row["scale"])
            digest = scale_hashes.get(key)
            if digest is None:
                digest = hashlib.sha256()
                scale_hashes[key] = digest
            digest.update(("|".join((row["model_x"], row["model_z"],
                                     row["legacy_td_surface"], row["tree_probability"], row["tree_cover"])) + "\n").encode())
    checks["broad_climate_and_groves_are_scale_invariant"] = all(
        scale_hashes[(profile, seed, 2)].digest() == scale_hashes[(profile, seed, 5)].digest()
        for profile in PROFILES for seed in SEEDS
    )
    result = {
        "title": "Synthetic offline ecology audit",
        "passed": all(checks.values()),
        "checks": checks,
        "summary_rows": len(summaries),
        "cells_sha256": hashlib.sha256((output / "cells.csv").read_bytes()).hexdigest(),
        "source_sha256": {
            source: hashlib.sha256((ROOT / source).read_bytes()).hexdigest() for source in JAVA_SOURCES
        },
        "limitations": [
            "Not ONNX-generated terrain or a saved-world sample.",
            "Tree maps show per-attempt production probabilities, not actual placed or surviving trees.",
            "Legacy TD-only surfaces exclude Immersive Weathering and other mod interactions.",
        ],
    }
    (output / "audit.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    if not result["passed"]:
        failed = ", ".join(name for name, passed in checks.items() if not passed)
        raise RuntimeError(f"ecology audit failed: {failed}")
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--size", type=int, default=128)
    parser.add_argument("--model-step", type=int, default=16)
    args = parser.parse_args(argv)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    classes = output / "classes"
    javac, java = java_tools(args.java_home)
    compile_sampler(classes, javac)
    run_sampler(output, classes, java, args.size, args.model_step)
    grids = load_cells(output / "cells.csv", args.size)
    render_atlas(output, grids, args.size)
    render_climate(output)
    result = audit(output)
    print(json.dumps({"output": str(output), "passed": result["passed"], "checks": result["checks"]}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
