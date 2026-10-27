"""Create immutable inputs and commands for a bounded RAM-optimization A/B run.

This script never launches Minecraft.  It snapshots the currently installed Terrain
Diffusion JAR before it is replaced, then emits the exact isolated-run commands.
"""
from __future__ import annotations
import argparse, hashlib, json, os, shutil, sys, zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INSTANCE = Path(os.environ["APPDATA"]) / "PrismLauncher" / "instances" / "1.21.1 neoforge rpg" / "minecraft"
OUT = ROOT / "build" / "memory-bench"
TARGET = "NeoForge-terrain-diffusion-mc-2.3.0-cuda+1.21.1.jar"

def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""): h.update(block)
    return h.hexdigest()

def snapshot(args: argparse.Namespace) -> None:
    target = OUT / "baseline"
    if target.exists(): raise SystemExit(f"Refusing existing immutable baseline: {target}")
    source = args.instance / "mods" / TARGET
    if not source.is_file(): raise SystemExit(f"Missing baseline target: {source}")
    target.mkdir(parents=True)
    copy = target / TARGET; shutil.copy2(source, copy)
    configs = {}
    for relative in ("config/terrain-diffusion-mc.properties", "config/modernfix-mixins.properties", "options.txt"):
        path = args.instance / relative
        if path.is_file(): configs[relative] = {"sha256": digest(path), "bytes": path.stat().st_size}
    (target / "manifest.json").write_text(json.dumps({
        "created_utc": datetime.now(timezone.utc).isoformat(), "source": str(source),
        "jar": {"filename": copy.name, "sha256": digest(copy), "bytes": copy.stat().st_size},
        "config_hashes": configs,
        "scope": "Baseline input only. No game files were modified or launched."
    }, indent=2) + "\n", encoding="utf-8")
    print(target / "manifest.json")

def snapshot_fixture(args: argparse.Namespace) -> None:
    """Copy the active baseline inputs, excluding saves and unselected resource packs."""
    target = OUT / "template-baseline"
    if target.exists(): raise SystemExit(f"Refusing existing immutable fixture: {target}")
    source = args.instance.resolve()
    if not (source / "options.txt").is_file() or not (source / "mods" / TARGET).is_file():
        raise SystemExit("Source instance lacks options or Terrain Diffusion baseline JAR")
    target.mkdir(parents=True)
    shutil.copy2(source / "options.txt", target / "options.txt")
    selected = set()
    try:
        lines = dict(line.split(":", 1) for line in (source / "options.txt").read_text(encoding="utf-8").splitlines() if ":" in line)
        selected = {entry[5:] for entry in json.loads(lines.get("resourcePacks", "[]")) if entry.startswith("file/")}
    except (ValueError, OSError):
        pass
    for folder in ("mods", "config", "defaultconfigs", "shaderpacks", "terrain-diffusion-models", "globalresources", "datapacks"):
        origin = source / folder
        if not origin.is_dir(): continue
        if folder == "resourcepacks": continue
        shutil.copytree(origin, target / folder)
    pack_source = source / "resourcepacks"
    if selected and pack_source.is_dir():
        pack_target = target / "resourcepacks"; pack_target.mkdir()
        for name in selected:
            origin = pack_source / name
            if origin.is_file(): shutil.copy2(origin, pack_target / name)
            elif origin.is_dir(): shutil.copytree(origin, pack_target / name)
    manifest = {"created_utc": datetime.now(timezone.utc).isoformat(), "source": str(source),
                "td_jar_sha256": digest(target / "mods" / TARGET), "selected_resourcepacks": sorted(selected),
                "excluded": ["saves", "logs", "screenshots", "crash-reports", "accounts", "usercache"]}
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(target / "manifest.json")

def plan(args: argparse.Namespace) -> None:
    base = OUT / "baseline" / TARGET
    if not base.is_file(): raise SystemExit("Snapshot the baseline first")
    optimized = args.optimized.resolve()
    if not optimized.is_file(): raise SystemExit(f"Missing optimized artifact: {optimized}")
    try:
        with zipfile.ZipFile(optimized) as archive:
            descriptor = archive.read("META-INF/neoforge.mods.toml").decode("utf-8", "replace")
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        raise SystemExit(f"Optimized artifact is not a readable NeoForge mod JAR: {error}")
    if "terrain_diffusion_mc" not in descriptor:
        raise SystemExit("Optimized artifact is not Terrain Diffusion; refusing to emit a misleading plan")
    launcher = ROOT / "tools" / "optimization_bench" / "launch.py"
    probe = ROOT / "build" / "optimization-bench" / "optimization-bench-1.0.0.jar"
    common = [sys.executable, str(launcher), "run", "NAME", "--probe-jar", str(probe),
              "--property", "optbench.terrainDiffusionWorld=true", "--property", "optbench.terrainRadius=4", "--property", "optbench.verifyOptimizations=true", "--property", "optbench.warmup=45", "--property", "optbench.seconds=60",
              "--heap-min-mib", "12000", "--heap-max-mib", "12000", "--collector", "zgc", "--timeout", "720"]
    optimized_common = [sys.executable, str(launcher), "run", "NAME", "--probe-jar", str(probe),
                        "--property", "optbench.terrainDiffusionWorld=true", "--property", "optbench.terrainRadius=4", "--property", "optbench.verifyOptimizations=true", "--property", "optbench.warmup=45", "--property", "optbench.seconds=60",
                        "--heap-min-mib", "4096", "--heap-max-mib", "12000", "--collector", "zgc", "--timeout", "720"]
    companion = args.companion.resolve() if args.companion else None
    if companion and (not companion.is_file() or companion.suffix.lower() != ".jar"):
        raise SystemExit("--companion must be a readable .jar")
    additional_extras = [Path(extra).resolve() for extra in args.extra_mod]
    if any(not extra.is_file() or extra.suffix.lower() != ".jar" for extra in additional_extras):
        raise SystemExit("--extra-mod must be a readable .jar")
    optimized_extra = (["--extra-mod", str(companion)] if companion else []) + [item for extra in additional_extras for item in ("--extra-mod", str(extra))]
    normalized_replacements = []
    for specification in args.replace_mod:
        name, separator, source = specification.partition("=")
        source_path = Path(source).resolve()
        if not separator or Path(name).name != name or not source_path.is_file():
            raise SystemExit("--replace-mod must be exact-target-filename=readable-source.jar")
        normalized_replacements.append(f"{name}={source_path}")
    optimized_replacements = [item for specification in normalized_replacements for item in ("--replace-mod", specification)]
    output = {
      "scope": "One serial baseline/optimized pair. Fresh isolated game directories and the identical static GPU scene; this validates client startup/render only, not a full terrain-generation memory census.",
      "preconditions": ["Source freeze and release JAR inspection complete", "No active Minecraft or Gradle/probe job", "Do not change shaders, resource packs or other mod settings between pair"],
      "baseline": common[:3] + ["memory-baseline"] + common[4:] + ["--template", str(OUT / "template-baseline"), "--replace-mod", f"{TARGET}={base}"],
      "optimized": optimized_common[:3] + ["memory-optimized-b"] + optimized_common[4:] + ["--template", str(OUT / "template-baseline"), "--replace-mod", f"{TARGET}={optimized}"] + optimized_replacements + optimized_extra,
      "conditional_g1": optimized_common[:3] + ["memory-optimized-g1"] + optimized_common[4:-4] + ["--collector", "g1", "--timeout", "720", "--template", str(OUT / "template-baseline"), "--replace-mod", f"{TARGET}={optimized}"] + optimized_replacements + optimized_extra,
      "conditional_dynamic_resources": optimized_common[:3] + ["memory-optimized-dynamic"] + optimized_common[4:] + ["--template", str(OUT / "template-baseline"), "--replace-mod", f"{TARGET}={optimized}"] + optimized_replacements + optimized_extra + ["--config-line", "config/modernfix-mixins.properties:mixin.perf.dynamic_resources=true"],
      "conditional_g1_dynamic_smoke": optimized_common[:3] + ["memory-optimized-g1-dynamic-smoke"] + optimized_common[4:-4] + ["--collector", "g1", "--timeout", "720", "--property", "optbench.modelSmoke=true", "--template", str(OUT / "template-baseline"), "--replace-mod", f"{TARGET}={optimized}"] + optimized_replacements + optimized_extra + ["--config-line", "config/modernfix-mixins.properties:mixin.perf.dynamic_resources=true"],
      "interpretation": "Run conditional variants only if the optimized ZGC run starts and its screenshots/model textures pass. G1 and dynamic resources are separate setting experiments, not part of the code-change saving claim."
    }
    destination = OUT / "plan.json"; destination.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(destination)
if __name__ == "__main__":
    p=argparse.ArgumentParser(description=__doc__); sub=p.add_subparsers(dest="action",required=True)
    a=sub.add_parser("snapshot-baseline"); a.add_argument("--instance",type=Path,default=INSTANCE); a.set_defaults(func=snapshot)
    a=sub.add_parser("snapshot-fixture"); a.add_argument("--instance",type=Path,default=INSTANCE); a.set_defaults(func=snapshot_fixture)
    a=sub.add_parser("plan"); a.add_argument("--optimized",type=Path,required=True); a.add_argument("--companion",type=Path); a.add_argument("--extra-mod",type=Path,action="append",default=[]); a.add_argument("--replace-mod",action="append",default=[]); a.set_defaults(func=plan)
    ns=p.parse_args(); ns.func(ns)
