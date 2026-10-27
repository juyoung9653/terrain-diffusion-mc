#!/usr/bin/env python3
"""Isolated, offline NeoForge 21.1.264 client launcher for optimization tests."""
from __future__ import annotations

import argparse, hashlib, io, json, os, re, shutil, subprocess, sys, time, zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INSTANCE = (Path(os.environ.get("APPDATA", "~")).expanduser() / "PrismLauncher" / "instances" / "1.21.1 neoforge rpg" / "minecraft")
PRISM = DEFAULT_INSTANCE.parents[2]
DEFAULT_LIBRARIES, DEFAULT_ASSETS = PRISM / "libraries", PRISM / "assets"
DEFAULT_JAVA = Path(r"C:\Program Files\Java\jdk-21\bin\javaw.exe")
WORK = ROOT / "build" / "optimization-bench"; TEMPLATE = WORK / "template"; RUNS = WORK / "runs"
MC, NEO, FML, NEOFORM, ASSET_INDEX = "1.21.1", "21.1.264", "4.0.45", "20240808.144430", "17"
OFFLINE_NAME, OFFLINE_UUID = "OptBench", "00000000-0000-0000-0000-000000000001"
DEFAULT_JVM = ("-Xms12000M", "-Xmx12000M", "-XX:+UseZGC", "-XX:+ZGenerational")

def fail(message: str) -> None: raise RuntimeError(message)
def require_file(path: Path, label: str) -> Path:
    if not path.is_file(): fail(f"Missing {label}: {path}")
    return path
def memory_mib(value: int, label: str) -> int:
    if not 512 <= value <= 24576:
        fail(f"{label} must be between 512 and 24576 MiB")
    return value

def jvm_args(args: argparse.Namespace) -> tuple[str, ...]:
    """Known-only collector variants; arbitrary VM switches are intentionally unsupported."""
    minimum = memory_mib(args.heap_min_mib, "--heap-min-mib")
    maximum = memory_mib(args.heap_max_mib, "--heap-max-mib")
    if minimum > maximum:
        fail("--heap-min-mib cannot exceed --heap-max-mib")
    collector = ("-XX:+UseG1GC",) if args.collector == "g1" else ("-XX:+UseZGC", "-XX:+ZGenerational")
    return (f"-Xms{minimum}M", f"-Xmx{maximum}M", *collector)
def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""): digest.update(chunk)
    return digest.hexdigest()
def safe_name(name: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", name): fail("Run name must be one safe filename (letters, numbers, ., _, -).")
    return name
def fresh_run(name: str) -> Path:
    target, root = (RUNS / safe_name(name)).resolve(), RUNS.resolve()
    if target.parent != root: fail("Run name resolves outside the runs directory.")
    if target.exists(): fail(f"Run directory already exists; choose a new name: {target}")
    return target
def mod_jars(directory: Path, excluded: Iterable[str]) -> list[Path]:
    prefixes = tuple(item.lower() for item in excluded)
    return [item for item in sorted(directory.glob("*.jar")) if not item.name.lower().startswith(prefixes)]
def json_file(path: Path) -> dict: return json.loads(require_file(path, "metadata").read_text(encoding="utf-8"))
def neoforge_version(libraries: Path) -> dict:
    installer = require_file(libraries / f"net/neoforged/neoforge/{NEO}/neoforge-{NEO}-installer.jar", "NeoForge installer")
    with zipfile.ZipFile(installer) as archive: return json.loads(archive.read("version.json"))
def applies(item: dict) -> bool:
    if not item.get("rules"): return True
    allowed = False
    for rule in item["rules"]:
        rule_os = rule.get("os", {})
        if rule_os.get("name", "windows") == "windows" and rule_os.get("arch", "x86_64") in ("x86_64", "amd64"): allowed = rule.get("action") == "allow"
    return allowed
def artifact_path(item: dict, libraries: Path) -> list[Path]:
    if not applies(item): return []
    downloads, output = item.get("downloads", {}), []
    if (artifact := downloads.get("artifact")) and artifact.get("path"): output.append(libraries / artifact["path"])
    # Mojang's component metadata often supplies only Maven coordinates.  Prism
    # has already materialized those coordinates below libraries, so do not drop
    # them merely because that metadata has no downloads.artifact.path.
    if not output and item.get("name"):
        coordinate = item["name"].split(":")
        if len(coordinate) >= 3:
            group, artifact_name, version = coordinate[:3]
            classifier = f"-{coordinate[3]}" if len(coordinate) >= 4 else ""
            output.append(libraries / group.replace(".", "/") / artifact_name / version / f"{artifact_name}-{version}{classifier}.jar")
    for key, artifact in downloads.get("classifiers", {}).items():
        if key.startswith("natives-windows") and artifact.get("path"): output.append(libraries / artifact["path"])
    return output
def production_libraries(libraries: Path) -> tuple[list[Path], list[Path], dict]:
    """Resolve the direct BootstrapLauncher classpath from installed metadata."""
    mc = json_file(PRISM / "meta" / "net.minecraft" / f"{MC}.json")
    lwjgl = json_file(PRISM / "meta" / "org.lwjgl3" / "3.3.3.json")
    neo = neoforge_version(libraries); classpath: list[Path] = []; natives: list[Path] = []
    for entry in mc["libraries"] + lwjgl["libraries"] + neo["libraries"]:
        for path in artifact_path(entry, libraries): (natives if "natives-windows" in path.name else classpath).append(path)
    # The NeoForge *client* artifact is the patched game module and also carries
    # the neoforge mod descriptor.  Adding vanilla client or universal here
    # creates duplicate minecraft modules and leaves neoforge as MISSING.
    classpath.append(libraries / f"net/neoforged/neoforge/{NEO}/neoforge-{NEO}-client.jar")
    classpath, natives = list(dict.fromkeys(classpath)), list(dict.fromkeys(natives))
    for path in classpath + natives: require_file(path, "production library")
    return classpath, natives, neo
def compile_classpath(libraries: Path) -> str:
    # Compile against the named SRG Minecraft jar, with every production transitive.
    production, _, _ = production_libraries(libraries)
    runtime_client = libraries / f"net/neoforged/neoforge/{NEO}/neoforge-{NEO}-client.jar"
    srg_client = libraries / f"net/minecraft/client/{MC}-{NEOFORM}/client-{MC}-{NEOFORM}-srg.jar"
    paths = [srg_client if path == runtime_client else path for path in production]
    paths.append(libraries / f"net/neoforged/neoforge/{NEO}/neoforge-{NEO}-universal.jar")
    for path in paths: require_file(path, "direct javac library")
    return os.pathsep.join(map(str, paths))

def prepare(args: argparse.Namespace) -> None:
    if TEMPLATE.exists(): fail(f"Template already exists and is immutable: {TEMPLATE}")
    stage = WORK / "template-staging"
    if stage.exists(): fail(f"Staging directory already exists; inspect it instead of deleting it: {stage}")
    source = args.instance.resolve(); require_file(source / "options.txt", "source options.txt")
    selected = mod_jars(source / "mods", args.exclude_prefix)
    if not selected: fail("No active .jar mods remain after exclusions.")
    production_libraries(args.libraries); require_file(args.java, "Java 21 javaw.exe"); require_file(args.assets / "indexes" / f"{ASSET_INDEX}.json", "asset index")
    stage.mkdir(parents=True); (stage / "mods").mkdir(); shutil.copy2(source / "options.txt", stage / "options.txt")
    for folder in ("config", "defaultconfigs", "resourcepacks", "shaderpacks", "terrain-diffusion-models"):
        if (source / folder).is_dir(): shutil.copytree(source / folder, stage / folder)
    for jar in selected: shutil.copy2(jar, stage / "mods" / jar.name)
    manifest = {"created_utc": datetime.now(timezone.utc).isoformat(), "source_instance": str(source), "minecraft": MC, "neoforge": NEO, "excluded_prefixes": args.exclude_prefix, "mods": [{"filename": item.name, "sha256": sha256(item)} for item in selected], "not_copied": ["saves", "logs", "crash-reports", "accounts", "usercache", "screenshots"]}
    (stage / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8"); stage.replace(TEMPLATE)
    cp, _, _ = production_libraries(args.libraries)
    (WORK / "launcher-metadata.json").write_text(json.dumps({"minecraft": MC, "neoforge": NEO, "javaw": str(args.java), "direct_javac_classpath": compile_classpath(args.libraries), "production_classpath_entries": len(cp), "jvm": list(DEFAULT_JVM)}, indent=2) + "\n", encoding="utf-8")
    print(TEMPLATE / "manifest.json")

def set_options(path: Path, width: int, height: int) -> None:
    updates = {"enableVsync": "false", "fullscreen": "false", "maxFps": "260", "overrideWidth": str(width), "overrideHeight": str(height), "pauseOnLostFocus": "false", "skipMultiplayerWarning": "true"}
    output, seen = [], set()
    for line in path.read_text(encoding="utf-8").splitlines():
        key, separator, _ = line.partition(":")
        if separator and key in updates: output.append(f"{key}:{updates[key]}"); seen.add(key)
        else: output.append(line)
    output.extend(f"{key}:{value}" for key, value in updates.items() if key not in seen)
    path.write_text("\n".join(output) + "\n", encoding="utf-8")
def extract_natives(jars: list[Path], destination: Path) -> None:
    destination.mkdir()
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            for member in archive.infolist():
                if not member.is_dir() and member.filename.lower().endswith(".dll"):
                    with archive.open(member) as inp, (destination / Path(member.filename).name).open("wb") as out: shutil.copyfileobj(inp, out)
def replace_mod(target: Path, specification: str) -> str:
    """Replace one exact mod filename in an isolated run, preserving the baseline input."""
    name, separator, source_text = specification.partition("=")
    if not separator or Path(name).name != name or not name.lower().endswith(".jar"):
        fail("--replace-mod must be exact-target-filename=source.jar")
    source = require_file(Path(source_text).resolve(), "replacement mod")
    destination = target / "mods" / name
    if not destination.is_file():
        fail(f"Replacement target is absent from isolated template: {name}")
    shutil.copy2(source, destination)
    source_hash, destination_hash = sha256(source), sha256(destination)
    if destination_hash != source_hash:
        fail(f"Replacement hash mismatch after staging: {name}")
    return f"replace:{name}:source={source_hash}:staged={destination_hash}"

def set_config_line(target: Path, specification: str) -> str:
    """Set an existing/added key=value line in a cloned config, never touching the source instance."""
    relative, separator, assignment = specification.partition(":")
    key, equals, value = assignment.partition("=")
    path = (target / relative).resolve()
    config_root = (target / "config").resolve()
    if not separator or not equals or path.parent != config_root or path.suffix not in (".properties", ".txt"):
        fail("--config-line must be config/file.properties:key=value")
    if "\n" in value or "\r" in value or not key or "=" in key:
        fail("Invalid --config-line assignment")
    lines = path.read_text(encoding="utf-8").splitlines() if path.is_file() else []
    output, found = [], False
    for line in lines:
        prefix, mark, _ = line.partition("=")
        if mark and prefix == key:
            output.append(f"{key}={value}"); found = True
        else:
            output.append(line)
    if not found: output.append(f"{key}={value}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(output) + "\n", encoding="utf-8")
    return f"config:{relative}:{key}={value}"

def jar_contains_onnx(jar: Path) -> bool:
    """Find direct or JarJar-nested ONNX classes without extracting them."""
    try:
        with zipfile.ZipFile(jar) as archive:
            names = archive.namelist()
            if any(name.startswith("ai/onnxruntime/") for name in names): return True
            for name in names:
                if not name.startswith("META-INF/jarjar/") or not name.lower().endswith(".jar"): continue
                with zipfile.ZipFile(io.BytesIO(archive.read(name))) as nested:
                    if any(member.startswith("ai/onnxruntime/") for member in nested.namelist()): return True
    except zipfile.BadZipFile:
        fail(f"Invalid mod/library JAR while checking ONNX isolation: {jar.name}")
    return False

def reject_duplicate_onnx(target: Path, extras: list[Path]) -> None:
    """A disk ONNX companion must replace, never accompany, a JarJar ONNX copy."""
    if not any(jar_contains_onnx(extra) for extra in extras): return
    retained = [jar.name for jar in sorted((target / "mods").glob("*.jar")) if jar_contains_onnx(jar)]
    if retained:
        fail("Disk ONNX companion conflicts with retained ONNX content in staged mods: " + ", ".join(retained))

def add_runtime_inputs(args: argparse.Namespace, target: Path) -> list[str]:
    source = args.instance.resolve(); copied: list[str] = []
    for folder in ("terrain-diffusion-models",):
        if (origin := source / folder).is_dir() and not (target / folder).exists(): shutil.copytree(origin, target / folder); copied.append(folder)
    for folder in ("globalresources", "datapacks"):
        if (origin := source / folder).is_dir() and not (target / folder).exists() and sum(p.stat().st_size for p in origin.rglob("*") if p.is_file()) <= 128 * 1024 * 1024: shutil.copytree(origin, target / folder); copied.append(folder)
    replacements = [replace_mod(target, specification) for specification in args.replace_mod]
    config_updates = [set_config_line(target, specification) for specification in args.config_line]
    # Exact replacements must run before this guard, otherwise a same-named old
    # TD JAR can load alongside its external ONNX companion.
    reject_duplicate_onnx(target, [Path(extra).resolve() for extra in args.extra_mod])
    if args.probe_jar:
        probe = require_file(args.probe_jar.resolve(), "probe jar"); shutil.copy2(probe, target / "mods" / probe.name); copied.append(f"probe:{probe.name}")
    for extra in args.extra_mod:
        library = require_file(extra.resolve(), "extra mod/library")
        if library.suffix.lower() != ".jar": fail("--extra-mod must name a .jar")
        destination = target / "mods" / library.name
        if destination.exists(): fail(f"Extra mod filename already exists in isolated run: {library.name}")
        shutil.copy2(library, destination); copied.append(f"extra:{library.name}:{sha256(library)}")
    kept = mod_jars(target / "mods", args.exclude_prefix); excluded = sorted(p.name for p in (target / "mods").glob("*.jar") if p not in kept)
    if excluded:
        disabled = target / "disabled-mods"; disabled.mkdir()
        for name in excluded: (target / "mods" / name).replace(disabled / name)
    return replacements + config_updates + copied + [f"excluded:{name}" for name in excluded]
def properties(values: list[str]) -> list[str]:
    output = []
    for value in values:
        key, separator, setting = value.partition("=")
        if not separator or not re.fullmatch(r"[A-Za-z0-9_.-]+", key) or "\x00" in setting or "\n" in setting or "\r" in setting: fail(f"Invalid --property: {value!r}; expected safe key=value")
        output.append(f"-D{key}={setting}")
    return output
def make_command(args: argparse.Namespace, target: Path, natives: Path) -> list[str]:
    if args.width < 1 or args.height < 1 or args.timeout < 1: fail("width, height, and timeout must be positive")
    cp, _, neo = production_libraries(args.libraries); substitutions = {"${library_directory}": str(args.libraries), "${classpath_separator}": os.pathsep, "${version_name}": MC}
    neo_jvm = []
    for item in neo["arguments"]["jvm"]:
        for old, new in substitutions.items(): item = item.replace(old, new)
        if item.startswith("-DignoreList="):
            # Bootstrap sees the patched client on -cp, while FML's production
            # client provider owns it as the NeoForge game module.  Ignore it in
            # the bootstrap module scan to avoid loading NeoForge twice.
            item += f",neoforge-{NEO}-client.jar"
        neo_jvm.append(item)
    mc_args = json_file(PRISM / "meta" / "net.minecraft" / f"{MC}.json")["minecraftArguments"].split()
    values = {"${auth_player_name}": OFFLINE_NAME, "${version_name}": MC, "${game_directory}": str(target), "${assets_root}": str(args.assets), "${assets_index_name}": ASSET_INDEX, "${auth_uuid}": OFFLINE_UUID, "${auth_access_token}": "0", "${user_type}": "legacy", "${version_type}": "optimization-bench"}
    return [str(args.java), *jvm_args(args), f"-Djava.library.path={natives}", *properties(args.property), *neo_jvm, "-cp", os.pathsep.join(map(str, cp)), neo["mainClass"], *[values.get(item, item) for item in mc_args], '--width', str(args.width), '--height', str(args.height), *neo["arguments"]["game"]]
def command(args: argparse.Namespace) -> None:
    target = fresh_run(args.name); print(json.dumps(make_command(args, target, target / "natives"), indent=2))
def clone_template(template: Path, target: Path) -> None:
    # Only enabled file packs participate in this test. Unselected unpacked
    # resource archives contain tens of thousands of files and add minutes of
    # unrelated preparation I/O to every fresh launch.
    options = dict(line.split(':', 1) for line in (template / 'options.txt').read_text(encoding='utf-8').splitlines() if ':' in line)
    selected = {entry[5:] for entry in json.loads(options.get('resourcePacks', '[]')) if entry.startswith('file/')}
    def ignored(directory, names):
        if Path(directory) == template / 'resourcepacks':
            return [name for name in names if name not in selected]
        return []
    shutil.copytree(template, target, ignore=ignored)
def run(args: argparse.Namespace) -> None:
    if (WORK / 'hold-new-launches').exists():
        fail('New launches held for benchmark instrumentation review; active run is unaffected.')
    template = args.template.resolve()
    if not (template / "manifest.json").is_file(): fail("Template is absent or has no manifest")
    target = fresh_run(args.name); clone_template(template, target); set_options(target / "options.txt", args.width, args.height); additions = add_runtime_inputs(args, target)
    # This immutable pre-Popen inventory makes a same-filename replacement
    # auditable and proves no old nested ONNX was retained beside a companion.
    staged_mod_hashes = {jar.name: sha256(jar) for jar in sorted((target / "mods").glob("*.jar"))}
    retained_onnx = [jar.name for jar in sorted((target / "mods").glob("*.jar")) if jar_contains_onnx(jar)]
    _, native_jars, _ = production_libraries(args.libraries); natives = target / "natives"; extract_natives(native_jars, natives); cmd = make_command(args, target, natives)
    receipt = {"command": cmd, "started_utc": datetime.now(timezone.utc).isoformat(), "runtime_inputs": additions, "staged_mod_sha256": staged_mod_hashes, "staged_onnx_content_mods": retained_onnx, "width": args.width, "height": args.height, "timeout_seconds": args.timeout}; (target / "run-manifest.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    logs = target / "logs"; logs.mkdir(); flags = getattr(subprocess, "CREATE_NO_WINDOW", 0); timed_out = False
    with (logs / "launcher-stdout-stderr.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(cmd, cwd=target, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
        (target / 'process.json').write_text(json.dumps({'pid': process.pid}), encoding='utf-8')
        started = time.monotonic()
        try:
            import psutil
            watched = psutil.Process(process.pid)
            with (target / "resources.csv").open("w", encoding="utf-8", newline="") as samples:
                samples.write("elapsed_seconds,rss_bytes,cpu_seconds\n")
                while process.poll() is None and time.monotonic() - started < args.timeout:
                    try:
                        family = [watched, *watched.children(recursive=True)]
                        java = [item for item in family if item.name().lower() in ("java.exe", "javaw.exe")]
                        samples.write(f"{time.monotonic()-started:.3f},{sum(item.memory_info().rss for item in java)},{sum(sum(item.cpu_times()[:2]) for item in java):.3f}\n"); samples.flush()
                    except psutil.Error: pass
                    time.sleep(1)
                timed_out = process.poll() is None
        except ImportError:
            try: process.wait(timeout=args.timeout)
            except subprocess.TimeoutExpired: timed_out = True
        if timed_out: process.kill()
        exit_code = process.wait()
    receipt.update({"finished_utc": datetime.now(timezone.utc).isoformat(), "exit_code": exit_code, "timed_out": timed_out}); (target / "launch-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8"); raise SystemExit(exit_code if not timed_out else 124)
def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__); result.add_argument("--instance", type=Path, default=DEFAULT_INSTANCE); result.add_argument("--libraries", type=Path, default=DEFAULT_LIBRARIES); result.add_argument("--assets", type=Path, default=DEFAULT_ASSETS); result.add_argument("--java", type=Path, default=DEFAULT_JAVA); sub = result.add_subparsers(dest="action", required=True)
    prep = sub.add_parser("prepare"); prep.add_argument("--exclude-prefix", action="append", default=[]); prep.set_defaults(func=prepare)
    for action, func in (("command", command), ("run", run)):
        item = sub.add_parser(action); item.add_argument("name"); item.add_argument("--exclude-prefix", action="append", default=[]); item.add_argument("--template", type=Path, default=TEMPLATE); item.add_argument("--probe-jar", type=Path); item.add_argument("--extra-mod", type=Path, action="append", default=[]); item.add_argument("--property", action="append", default=[]); item.add_argument("--replace-mod", action="append", default=[]); item.add_argument("--config-line", action="append", default=[]); item.add_argument("--heap-min-mib", type=int, default=12000); item.add_argument("--heap-max-mib", type=int, default=12000); item.add_argument("--collector", choices=("zgc", "g1"), default="zgc"); item.add_argument("--width", type=int, default=1920); item.add_argument("--height", type=int, default=1080); item.add_argument("--timeout", type=int, default=600); item.set_defaults(func=func)
    cp = sub.add_parser("compile-classpath"); cp.set_defaults(func=lambda args: print(compile_classpath(args.libraries))); return result
def main() -> None:
    args = parser().parse_args()
    try: args.func(args)
    except RuntimeError as error: print(f"error: {error}", file=sys.stderr); raise SystemExit(2)
if __name__ == "__main__": main()
