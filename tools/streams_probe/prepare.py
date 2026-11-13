"""Create fresh, workspace-contained probe worlds without editing the Prism instance."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[2]
RUNS = ROOT / "build" / "streams-probe"
NEOFORGE_VERSION = re.compile(r"\d+\.\d+\.\d+")
SCALE_RANGE = range(1, 7)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_fresh_scale_marker(path: Path, scale: int) -> None:
    """Persist a selected TD scale before the first world boot."""
    if scale not in SCALE_RANGE:
        raise ValueError(f"scale must be 1..6, got {scale}")
    def named(kind: int, name: str, payload: bytes) -> bytes:
        raw = name.encode("utf-8")
        return bytes((kind,)) + len(raw).to_bytes(2, "big") + raw + payload
    data = (named(3, "scale", scale.to_bytes(4, "big", signed=True))
            + named(1, "explicit_scale", b"\x01")
            + named(1, "fast_ground_sampling", b"\x01") + b"\x00")
    raw = b"\x0a\x00\x00" + named(10, "data", data) + named(3, "DataVersion",
            (3955).to_bytes(4, "big", signed=True)) + b"\x00"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(gzip.compress(raw, mtime=0))


def write_world_preset(pack: Path, *, scale: int, fixed_biome: str | None) -> Path:
    """Override only the fresh fixture's TD preset, never the source instance."""
    target = pack / "data/terrain-diffusion-mc/worldgen/world_preset/terrain_diffusion.json"
    target.parent.mkdir(parents=True)
    preset = json.loads((ROOT / "common/src/main/resources/data/terrain-diffusion-mc/"
                         "worldgen/world_preset/terrain_diffusion.json").read_text())
    preset["dimensions"]["minecraft:overworld"]["type"] = (
            f"terrain-diffusion-mc:terrain_diffusion_scale_{scale}")
    if fixed_biome:
        preset["dimensions"]["minecraft:overworld"]["generator"]["biome_source"] = {
            "type": "minecraft:fixed", "biome": fixed_biome}
    target.write_text(json.dumps(preset, indent=2) + "\n", encoding="utf-8")
    (pack / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": 48,
            "description": "TD Streams probe only: selected terrain scale"}}) + "\n",
            encoding="utf-8")
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Prism instance minecraft directory (read only)")
    parser.add_argument("--name", required=True, help="New run name; existing directories are refused")
    parser.add_argument("--terralith", action="store_true")
    parser.add_argument("--c2me", action="store_true", help="Also include the installed NeoForge C2ME jar")
    parser.add_argument("--seed", type=int, default=12345)
    parser.add_argument("--scale", type=int, choices=SCALE_RANGE, default=2,
                        help="Explicit TD world scale for this fresh fixture (default: 2)")
    parser.add_argument("--streams-version", default="2.13.1",
                        choices=["2.13.1", "2.13.5", "2.13.8", "2.15.2", "2.16.2", "2.17.1"])
    parser.add_argument("--neoforge-version", default="21.1.249",
                        help="NeoForge major.minor.patch version recorded in the fixture")
    parser.add_argument("--dry-floor", type=float)
    parser.add_argument("--disable-waterways", action="store_true", help="Same-terrain control: disable Streams rivers and lakes")
    parser.add_argument("--fixed-biome", choices=["minecraft:desert", "minecraft:savanna",
                        "terrain-diffusion-mc:forest_sparse", "terrain-diffusion-mc:taiga_sparse",
                        "terrain-diffusion-mc:snowy_taiga_sparse"],
                        help="Controlled experiment: keep TD terrain but use one biome everywhere")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", args.name):
        parser.error("name must contain only letters, digits, underscore or hyphen")
    if not NEOFORGE_VERSION.fullmatch(args.neoforge_version):
        parser.error("neoforge-version must use numeric major.minor.patch form")
    if args.dry_floor is not None and not 0 <= args.dry_floor <= 1:
        parser.error("dry-floor must be between 0 and 1")
    if args.fixed_biome and args.terralith:
        parser.error("fixed-biome and Terralith are separate experiments")
    source = args.source.resolve(strict=True)
    run = (RUNS / args.name).resolve()
    if not run.is_relative_to(RUNS.resolve()) or run.exists():
        parser.error("a fresh directory inside build/streams-probe is required")
    mods = list((source / "mods").glob(f"StreamsReflowing-1.21.1-neoforge-{args.streams_version}.jar"))
    if args.terralith:
        mods += list((source / "mods").glob("Terralith_1.21.1*_Neoforge.jar"))
        mods += list((source / "mods").glob("lithostitched-*.jar"))
    if args.c2me:
        mods += list((source / "mods").glob("c2me-neoforge-mc1.21.1-*.jar"))
    if len(mods) != 1 + 2 * int(args.terralith) + int(args.c2me):
        parser.error("expected one Streams jar and requested Terralith/Lithostitched/C2ME jars")
    assets = source / "terrain-diffusion-models"
    td_config = source / "config" / "terrain-diffusion-mc.properties"
    if not td_config.is_file():
        parser.error("missing config/terrain-diffusion-mc.properties")
    model_names = ["base_model.onnx", "coarse_model.onnx", "decoder_model.onnx",
                   "pipeline_data.json", "world_pipeline_config.json"]
    precision = re.search(r"(?mi)^\s*(?:inference\.)?decoder_precision\s*=\s*([^\s#;]+)",
                          td_config.read_text(encoding="utf-8"))
    if precision and precision.group(1).strip('"\\\'').lower() == "fp16":
        model_names.append("decoder_model_fp16.onnx")
    for name in model_names:
        if not (assets / name).is_file():
            parser.error(f"missing model asset: {name}")
    (run / "mods").mkdir(parents=True)
    (run / "config").mkdir()
    (run / "terrain-diffusion-models").mkdir()
    manifest = {"seed": args.seed, "streamsVersion": args.streams_version,
                "terralith": args.terralith, "c2me": args.c2me, "dryFloorOverride": args.dry_floor,
                "waterwaysDisabled": args.disable_waterways,
                "fixedBiome": args.fixed_biome, "scale": args.scale,
                "minecraft": "1.21.1", "neoforge": args.neoforge_version, "mods": [], "modelAssets": {}, "configs": {}}
    for mod in mods:
        target = run / "mods" / mod.name
        shutil.copy2(mod, target)
        manifest["mods"].append({"file": mod.name, "sha256": digest(target)})
    # Real copies: model validation/cache updates cannot touch user-owned files.
    for name in model_names:
        target = run / "terrain-diffusion-models" / name
        shutil.copy2(assets / name, target)
        manifest["modelAssets"][name] = {"bytes": target.stat().st_size, "sha256": digest(target)}
    streams_config = "streamsreflowing-common.toml"
    properties = source / "config/streamsreflowing.properties"
    if args.streams_version in {"2.16.2", "2.17.1"}:
        # Streams 2.16+ no longer consumes the legacy common TOML.  Do not make a
        # fixture which appears configured while the mod silently uses defaults.
        if not properties.is_file():
            parser.error(f"Streams {args.streams_version} requires config/streamsreflowing.properties; "
                         "copy or start the source instance once to migrate its configuration")
        streams_config = properties.name
    elif args.streams_version == "2.15.2" and properties.is_file():
        streams_config = properties.name
    for name in ["terrain-diffusion-mc.properties", streams_config]:
        target = run / "config" / name
        shutil.copy2(source / "config" / name, target)
        if name == streams_config and args.dry_floor is not None:
            content, count = re.subn(r"(?m)^(\s*wetnessDryFloor\s*=\s*)[^\r\n]+",
                                    lambda m: m[1] + str(args.dry_floor), target.read_text(encoding="utf-8"))
            if count != 1:
                raise ValueError("expected one wetnessDryFloor setting")
            target.write_text(content, encoding="utf-8")
        if name == streams_config and args.disable_waterways:
            content = target.read_text(encoding="utf-8")
            for setting in ["generateStreams", "generateLakes"]:
                content, count = re.subn(rf"(?m)^({setting}\s*=\s*)[^\r\n]+",
                                        lambda m: m[1] + "false", content)
                if count != 1:
                    raise ValueError(f"expected one {setting} setting")
            target.write_text(content, encoding="utf-8")
        manifest["configs"][name] = digest(target)
    (run / "eula.txt").write_text("eula=true\n", encoding="ascii")
    scale_marker = run / "world/data/terrain_diffusion_world_settings.dat"
    write_fresh_scale_marker(scale_marker, args.scale)
    manifest["freshScaleMarker"] = {"scale": args.scale, "sha256": digest(scale_marker)}
    (run / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=25585\nonline-mode=false\nenable-query=false\n"
        "enable-rcon=false\nlevel-name=world\nlevel-type=terrain-diffusion-mc:terrain_diffusion\n"
        f"level-seed={args.seed}\nview-distance=2\nsimulation-distance=2\nmax-tick-time=-1\n"
        "spawn-protection=0\nspawn-animals=false\nspawn-monsters=false\nspawn-npcs=false\n"
        "generate-structures=false\nallow-nether=false\nsync-chunk-writes=false\n",
        encoding="ascii")
    if args.terralith:
        pack = ROOT / "compat/terralith/terrain-diffusion-terralith-surface"
        if not (pack / "pack.mcmeta").is_file():
            raise ValueError("missing generated Terralith compatibility pack")
        target = run / "world/datapacks" / pack.name
        shutil.copytree(pack, target)
        manifest["compatPack"] = {str(p.relative_to(pack)): digest(p)
                                  for p in sorted(pack.rglob("*")) if p.is_file()}
    if args.scale != 2 or args.fixed_biome:
        target = write_world_preset(run / "world/datapacks/td-probe-world-preset",
                                    scale=args.scale, fixed_biome=args.fixed_biome)
        manifest["worldPresetSha256"] = digest(target)
        if args.fixed_biome:
            manifest["fixedBiomePresetSha256"] = manifest["worldPresetSha256"]
    (run / "input-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"run": str(run), "mods": manifest["mods"], "seed": args.seed}))


if __name__ == "__main__":
    main()
