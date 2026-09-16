"""Stage chunk-empty same-seed controls; never copy native networks or edit the template."""
import argparse
import gzip
import importlib.util
import json
from pathlib import Path


def stage(template: Path, output: Path):
    template = template.resolve(strict=True)
    output = output.resolve()
    if output.exists():
        raise FileExistsError(f"Output must be new: {output}")
    spec = importlib.util.spec_from_file_location("showcase_helpers", Path(__file__).parents[1] / "ecology/prepare_showcases.py")
    helpers = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helpers)
    raw = gzip.decompress((template / "level.dat").read_bytes())
    fields = helpers.nbt_fields(raw)
    if ("Data", "Player") in fields:
        raise ValueError("Use a dedicated probe template, not a player world")
    if fields[helpers.OVERWORLD_BIOME_SOURCE + ("type",)][3] != "terrain-diffusion-mc:terrain_diffusion":
        raise ValueError("Template must use Terrain Diffusion")
    kind, start, end, _ = fields[("Data", "initialized")]
    if kind != 1:
        raise ValueError("Invalid initialized flag")
    # This is a new chunk-empty world, so run first-world bootstrap rather than
    # changing the profile marker of an existing save.
    raw = raw[:start] + b"\x00" + raw[end:]
    output.mkdir(parents=True)
    for name in ("baseline", "natural"):
        world = output / name
        world.mkdir()
        helpers.copy_template(template, world)
        (world / "level.dat").write_bytes(gzip.compress(raw))
    (output / "comparison.json").write_text(json.dumps({
        "template": str(template), "worlds": ["baseline", "natural"],
        "baselineJvm": "-Dtd.rivers.natural=false", "naturalJvm": "-Dtd.rivers.natural=true",
        "note": "Run each setting only on first load; saved profile wins on restart."
    }, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--template-world", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    stage(args.template_world, args.output)
    print(args.output)
