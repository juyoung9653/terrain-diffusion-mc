"""Compile/export the pure underground-river model and render compact PNG evidence."""
from __future__ import annotations
import csv
import os
import subprocess
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "underground-river-model"
MODEL = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/geology/UndergroundRiverModel.java"
GEOLOGY = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/geology/GeologyModel.java"
TEST = ROOT / "tools/geology/UndergroundRiverModelTest.java"

def main() -> None:
    jdk = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr")) / "bin"
    OUT.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(jdk / "javac.exe"), "--release", "21", "-d", str(OUT), str(GEOLOGY), str(MODEL), str(TEST)], check=True, cwd=ROOT)
    subprocess.run([str(jdk / "java.exe"), "-ea", "-cp", str(OUT), "UndergroundRiverModelTest", "--export", str(OUT)], check=True, cwd=ROOT)
    for scale in (2, 6): render(scale)
    render_catchment()

def render_catchment() -> None:
    with (OUT / "river-catchment-td.csv").open() as stream:
        reaches = list(csv.DictReader(stream))
    width, height, margin = 720, 720, 45
    image = Image.new("RGB", (width, height), "white")
    draw = ImageDraw.Draw(image)
    min_x, max_x, min_z, max_z = -640, 320, -320, 640
    def point(x: float, z: float) -> tuple[int, int]:
        return (margin + round((x - min_x) / (max_x - min_x) * (width - margin * 2)),
                height - margin - round((z - min_z) / (max_z - min_z) * (height - margin * 2)))
    for reach in reaches:
        start = point(float(reach["from_x"]), float(reach["from_z"]))
        end = point(float(reach["to_x"]), float(reach["to_z"]))
        if reach["lake"] == "true":
            radius = max(1, round(float(reach["width_from"]) * (width - margin * 2) / (max_x - min_x)))
            draw.ellipse((start[0] - radius, start[1] - radius, start[0] + radius, start[1] + radius), fill=(70, 135, 205))
        else:
            draw.line((start, end), fill=(48, 116, 198), width=max(1, round(2 * float(reach["width_from"]) * (width - margin * 2) / (max_x - min_x))))
    draw.text((4, 4), "PURE MODEL overview: UndergroundRiverModel reaches, not saved terrain", fill="black")
    draw.text((4, 18), "TD axes: x=-640..320, z=-320..640; blue = channels / terminal ponds", fill="black")
    draw.line((margin, height - margin, width - margin, height - margin), fill="black")
    draw.line((margin, margin, margin, height - margin), fill="black")
    image.save(OUT / "river-catchment-overview-td.png")

def render(scale: int) -> None:
    rows = (OUT / f"river-plan-s{scale}.txt").read_text().splitlines()
    colors = {"0": (245, 245, 245), "1": (169, 143, 110), "2": (48, 116, 198)}
    plan = Image.new("RGB", (128, 128))
    plan.putdata([colors[cell] for row in rows for cell in row])
    plan = plan.resize((512, 512), Image.Resampling.NEAREST)
    ImageDraw.Draw(plan).text((4, 4), f"pure river plan scale={scale}; display: 4 px/block", fill="black")
    plan.save(OUT / f"river-plan-s{scale}.png")
    values = list(csv.DictReader((OUT / f"river-profile-s{scale}.csv").open()))
    elevations = [float(value["y"]) for value in values]
    low, high = min(elevations), max(elevations)
    span = max(1, high - low)
    profile = Image.new("RGB", (520, 180), "white")
    points = [(50 + index * 420 / max(1, len(values) - 1), 150 - (y - low) / span * 100) for index, y in enumerate(elevations)]
    draw = ImageDraw.Draw(profile)
    draw.line(points, fill=(48, 116, 198), width=4)
    draw.text((4, 4), f"pure longitudinal reach scale={scale}; y={low:.0f}..{high:.0f} world Y", fill="black")
    profile.save(OUT / f"river-profile-s{scale}.png")

if __name__ == "__main__": main()
