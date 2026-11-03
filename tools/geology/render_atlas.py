"""Render GeologyAtlas binary model samples into labelled PNG contact sheets."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

PALETTE = {
    1: (128, 128, 128), 2: (205, 184, 127), 3: (220, 219, 205), 4: (105, 112, 112),
    5: (151, 113, 95), 6: (190, 187, 186), 7: (74, 77, 86),
    16: (68, 68, 68), 17: (188, 113, 60), 18: (202, 190, 153), 19: (235, 181, 60),
    20: (210, 40, 36), 21: (55, 100, 205), 22: (90, 220, 230),
    30: (24, 24, 28), 31: (46, 115, 190),
}
LABELS = {1: "stone", 2: "sandstone", 3: "calcite", 4: "tuff", 5: "granite", 6: "diorite",
          7: "deepslate", 16: "coal", 17: "copper", 18: "iron", 19: "gold", 20: "redstone",
          21: "lapis", 22: "diamond", 30: "air cave", 31: "water cave"}


def colored(data: bytes, width: int, height: int) -> Image.Image:
    image = Image.new("RGB", (width, height))
    image.putdata([PALETTE.get(value, (255, 0, 255)) for value in data])
    return image


def title(draw: ImageDraw.ImageDraw, text: str, x: int, y: int) -> None:
    draw.rectangle((x, y, x + 470, y + 16), fill=(255, 255, 255))
    draw.text((x + 2, y + 2), text, fill=(0, 0, 0))


def cross_sheet(root: Path, entries: list[dict]) -> None:
    for scale in (1, 2, 6):
        subset = [entry for entry in entries if entry["scale"] == scale]
        # A scaled-down contact sheet; source cross pixels retain true 1:1 block aspect.
        thumbs: list[tuple[dict, Image.Image]] = []
        for entry in subset:
            cross = entry["cross"]
            source = colored((root / cross["file"]).read_bytes(), cross["width"], cross["height"])
            ratio = min(480 / source.width, 300 / source.height)
            thumbs.append((entry, source.resize((round(source.width * ratio), round(source.height * ratio)), Image.Resampling.NEAREST)))
        canvas = Image.new("RGB", (1500, 410), "white")
        draw = ImageDraw.Draw(canvas)
        draw.text((12, 8), f"GeologyModel vertical slices, scale={scale}; z=0; source aspect is 1 pixel = {scale} blocks", fill="black")
        for index, (entry, image) in enumerate(thumbs):
            x, y = (index % 3) * 500 + 10, (index // 3) * 470 + 30
            canvas.paste(image, (x, y + 20))
            title(draw, f"seed={entry['seed']} minY={entry['minY']} x=-{600 * scale}..{600 * scale}; y={entry['surfaceY']}..{entry['minY']}", x, y)
        legend(draw, 10, 385)
        canvas.save(root / f"cross-sections-scale-{scale}.png")


def plan_sheet(root: Path, entries: list[dict]) -> None:
    # One seed/minY representative per scale; metadata retains all planes/configurations.
    canvas = Image.new("RGB", (1530, 1030), "white")
    draw = ImageDraw.Draw(canvas)
    draw.text((12, 8), "GeologyModel horizontal cave masks (plan sampling step = 3*scale blocks/pixel)", fill="black")
    matching_floor = {1: -400, 2: -736, 6: -1968}
    chosen = [next(e for e in entries if e["seed"] == 91357 and e["scale"] == scale
                   and e["minY"] == matching_floor[scale]) for scale in (1, 2, 6)]
    for row, entry in enumerate(chosen):
        for column, plane in enumerate(entry["planes"]):
            source = colored((root / plane["file"]).read_bytes(), 401, 401)
            image = source.resize((160, 160), Image.Resampling.NEAREST)
            x, y = 20 + column * 250, 35 + row * 315
            canvas.paste(image, (x, y + 18))
            title(draw, f"s={entry['scale']} y={plane['y']}", x, y)
            draw.text((x, y + 182), f"air={plane['airPixels']} water={plane['waterPixels']}\nmax component={plane['largestCaveComponentPixels']} px",
                      fill="black")
    legend(draw, 10, 995)
    canvas.save(root / "cave-plans.png")


def legend(draw: ImageDraw.ImageDraw, x: int, y: int) -> None:
    for index, code in enumerate(LABELS):
        px = x + index * 90
        draw.rectangle((px, y, px + 10, y + 10), fill=PALETTE[code], outline="black")
        draw.text((px + 13, y), LABELS[code], fill="black")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", nargs="?", default="build/geology-atlas-v2", type=Path)
    args = parser.parse_args()
    manifest = json.loads((args.output / "atlas.json").read_text(encoding="utf-8"))
    for entry in manifest["entries"]:
        cross = entry["cross"]
        colored((args.output / cross["file"]).read_bytes(), cross["width"], cross["height"]).save(
            args.output / f"{entry['id']}_cross.png")
        for plane in entry["planes"]:
            colored((args.output / plane["file"]).read_bytes(), 401, 401).save(
                args.output / f"{entry['id']}_plan_{plane['y']}.png")
        projection = entry.get("carbonateCaveProjection")
        if projection:
            # This is intentionally mask-only: it represents any cave across a
            # depth interval, never a blended rock cross-section.
            image = Image.new("RGB", (401, 425), "white")
            mask = Image.new("RGB", (401, 401))
            mask.putdata([(20, 20, 24) if value == 30 else (245, 245, 245)
                          for value in (args.output / projection["file"]).read_bytes()])
            image.paste(mask, (0, 24)); draw = ImageDraw.Draw(image)
            draw.text((2, 4), f"cave union only; carbonate band y={projection['minY']}..{projection['maxY']}; 3*scale blocks/pixel", fill="black")
            image.save(args.output / f"{entry['id']}_carbonate-cave-projection.png")
    cross_sheet(args.output, manifest["entries"])
    plan_sheet(args.output, manifest["entries"])
    print(f"Rendered {args.output / 'cross-sections-scale-1.png'} and cave-plans.png")


if __name__ == "__main__":
    main()
