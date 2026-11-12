"""Prepare a reusable, fresh-chunk structure-density fixture from a workspace fixture."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from probe_suite import exclusive_lock, is_link

DIRECTORIES = ("mods", "config", "defaultconfigs", "terrain-diffusion-models")
CONTROLS = ("server.properties", "eula.txt")
OPTIONAL_CONTROLS = ("village-census-only.txt", "require-ecology-scale1.txt",
                     "reverse-order.txt", "repeat-native.txt", "verify-support.txt",
                     "verify-well-support.txt", "verify-structure-sites.txt")
METADATA = ("level.dat", "data/terrain_diffusion_world_settings.dat",
            "data/terrain_diffusion_natural_rivers.dat",
            "data/terrain_diffusion_streams_exact_v1.txt",
            "data/terrain_diffusion_hybrid_aquatic.properties")
MANIFEST = "input-manifest.json"
NAME = re.compile(r"[A-Za-z0-9_-]+\Z")


def _real_components(path: Path, base: Path) -> None:
    """Check components before resolve() can conceal a junction or symlink."""
    if not path.is_relative_to(base):
        raise ValueError(f"path must be within {base}: {path}")
    cursor = base
    if is_link(cursor):
        raise ValueError(f"refusing linked path: {cursor}")
    for part in path.relative_to(base).parts:
        cursor = cursor / part
        if is_link(cursor):
            raise ValueError(f"refusing linked path: {cursor}")


def _walk_files(directory: Path) -> list[Path]:
    result: list[Path] = []
    for current, folders, files in os.walk(directory, followlinks=False):
        for name in folders + files:
            path = Path(current) / name
            if is_link(path):
                raise ValueError(f"refusing linked fixture path: {path}")
            if not path.is_dir() and not path.is_file():
                raise ValueError(f"unsupported fixture entry: {path}")
        result.extend(Path(current) / name for name in files)
    return sorted(result)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _atomic_copy(source: Path, target: Path, expected: str) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix=".prepare-fixture-", suffix=".tmp",
                                     dir=target.parent, delete=False) as stream:
        temporary = Path(stream.name)
    try:
        shutil.copy2(source, temporary)
        if _sha256(temporary) != expected:
            raise ValueError(f"source changed while copying: {source}")
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def _atomic_bytes(data: bytes, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix=".prepare-fixture-", suffix=".tmp",
                                     dir=target.parent, delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(data)
    try:
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def _source_files(source: Path) -> dict[str, Path]:
    files: dict[str, Path] = {}
    for folder in DIRECTORIES:
        directory = source / folder
        if is_link(directory) or not directory.is_dir():
            raise ValueError(f"missing or linked source directory: {directory}")
        for path in _walk_files(directory):
            files[path.relative_to(source).as_posix()] = path
    for name in CONTROLS:
        path = source / name
        if not path.is_file() or is_link(path):
            raise ValueError(f"missing or linked source control: {path}")
        files[name] = path
    # Only canonical server-generated properties are accepted: do not silently
    # follow a different save path through Java property escapes/continuations.
    level_names = []
    for line in (source / "server.properties").read_text(encoding="latin-1").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")):
            continue
        if (len(stripped) - len(stripped.rstrip("\\"))) % 2:
            raise ValueError("continued server.properties lines are unsupported")
        key = re.split(r"[=:\s]", stripped, maxsplit=1)[0]
        if "\\" in key:
            raise ValueError("escaped server.properties keys are unsupported")
        if key == "level-name":
            match = re.fullmatch(r"level-name\s*=\s*world\s*", stripped)
            level_names.append(bool(match))
    if level_names != [True]:
        raise ValueError("server.properties must contain exactly one level-name=world")
    for name in OPTIONAL_CONTROLS:
        path = source / name
        if is_link(path) or (path.exists() and not path.is_file()):
            raise ValueError(f"invalid source control: {path}")
        if path.is_file():
            files[name] = path
    world = source / "world"
    if not world.is_dir() or is_link(world):
        raise ValueError(f"missing or linked source world: {world}")
    if not (world / "level.dat").is_file():
        raise ValueError("source fixture requires world/level.dat")
    for name in METADATA:
        path = world / name
        _real_components(path, source)
        if path.exists():
            if not path.is_file():
                raise ValueError(f"source metadata is not a file: {path}")
            files[f"world/{name}"] = path
    river_cache = world / "streamsreflowing"
    _real_components(river_cache, source)
    if river_cache.exists():
        if is_link(river_cache) or not river_cache.is_dir():
            raise ValueError(f"invalid river cache: {river_cache}")
        for path in _walk_files(river_cache):
            files[path.relative_to(source).as_posix()] = path
    return files


def _check_target(target: Path, expected: set[str]) -> None:
    if not target.exists():
        return
    if not target.is_dir():
        raise ValueError(f"target is not a directory: {target}")
    allowed_dirs = {"", *DIRECTORIES}
    for name in expected:
        parent = Path(name).parent
        while str(parent) != ".":
            allowed_dirs.add(parent.as_posix())
            parent = parent.parent
    for current, folders, files in os.walk(target, followlinks=False):
        for name in folders + files:
            path = Path(current) / name
            if is_link(path):
                raise ValueError(f"refusing linked target entry: {path}")
            relative = path.relative_to(target).as_posix()
            if path.is_dir():
                if relative not in allowed_dirs:
                    raise ValueError(f"unexpected or generated target directory: {path}")
            else:
                if not path.is_file() or relative not in expected:
                    raise ValueError(f"unexpected or generated target file: {path}")
                if path.stat().st_nlink > 1:
                    raise ValueError(f"refusing hardlinked target file: {path}")


def prepare(source: Path, target: Path, *, center: str | None = None,
            radius: int | None = None, family_name: str = "structure-density", root: Path = ROOT) -> dict[str, object]:
    started = time.monotonic()
    root = Path(os.path.abspath(root))
    source = Path(os.path.abspath(source))
    target = Path(os.path.abspath(target))
    build = root / "build"
    if family_name not in ("structure-density", "streams-probe"):
        raise ValueError("unsupported fixture family")
    family = build / family_name
    if not source.is_relative_to(build) or source == build:
        raise ValueError(f"source must be a dedicated workspace fixture under {build}")
    if target.parent != family or not NAME.fullmatch(target.name):
        raise ValueError(f"target must be a direct build/{family_name}/<safe-name> child: {target}")
    if source.is_relative_to(target) or target.is_relative_to(source):
        raise ValueError("source and target overlap")
    _real_components(source, root)
    _real_components(target, root)
    if not source.is_dir():
        raise ValueError(f"source fixture does not exist: {source}")
    if center is not None and not re.fullmatch(r"-?\d+,-?\d+", center):
        raise ValueError("center must be X,Z integers")
    if radius is not None and radius < 1:
        raise ValueError("radius must be positive")

    with exclusive_lock(root):
        files = _source_files(source)
        # Match the probe's own defaults when no explicit controls are supplied.
        generated = {
            "density-center.txt": (center or "0,0").encode("ascii") + b"\n",
            "density-radius.txt": str(radius or 96).encode("ascii") + b"\n",
        }
        if family_name == "streams-probe": generated = {}
        for name in (() if family_name == "streams-probe" else ("density-center.txt", "density-radius.txt")):
            path = source / name
            _real_components(path, source)
            if path.exists() and not path.is_file():
                raise ValueError(f"source control is not a file: {path}")
        if family_name != "streams-probe" and center is None and (source / "density-center.txt").is_file():
            generated["density-center.txt"] = (source / "density-center.txt").read_bytes()
        if family_name != "streams-probe" and radius is None and (source / "density-radius.txt").is_file():
            generated["density-radius.txt"] = (source / "density-radius.txt").read_bytes()
        effective_center = generated.get("density-center.txt", "0,0".encode()).decode("ascii").strip()
        effective_radius = generated.get("density-radius.txt", "1".encode()).decode("ascii").strip()
        if not re.fullmatch(r"-?\d+,-?\d+", effective_center):
            raise ValueError("source density center must be X,Z integers")
        if not effective_radius.isdecimal() or int(effective_radius) < 1:
            raise ValueError("source density radius must be positive")
        expected = set(files) | set(generated) | {MANIFEST}
        _check_target(target, expected)

        source_hashes = {name: _sha256(path) for name, path in files.items()}
        sizes = {name: path.stat().st_size for name, path in files.items()}
        for name, data in generated.items():
            source_hashes[name] = hashlib.sha256(data).hexdigest()
            sizes[name] = len(data)
        copied_files = copied_bytes = reused_files = reused_bytes = 0
        target.mkdir(parents=True, exist_ok=True)
        for folder in DIRECTORIES:
            (target / folder).mkdir(exist_ok=True)
        for name in sorted(source_hashes):
            destination = target / name
            expected_hash = source_hashes[name]
            if destination.is_file() and _sha256(destination) == expected_hash:
                reused_files += 1
                reused_bytes += sizes[name]
                continue
            if name in generated:
                _atomic_bytes(generated[name], destination)
            else:
                _atomic_copy(files[name], destination, expected_hash)
            if _sha256(destination) != expected_hash:
                raise ValueError(f"destination verification failed: {destination}")
            copied_files += 1
            copied_bytes += sizes[name]
        copied_river_cache = any(name.startswith("world/streamsreflowing/") for name in files)
        cache_mode = ("identical copied river cache; fresh chunks" if copied_river_cache
                      else "fresh river cache; fresh chunks")
        manifest = {"version": 1, "cacheMode": cache_mode,
                    "source": source.relative_to(root).as_posix(),
                    "sha256": dict(sorted(source_hashes.items()))}
        encoded = (json.dumps(manifest, indent=2) + "\n").encode("utf-8")
        manifest_path = target / MANIFEST
        if not manifest_path.is_file() or manifest_path.read_bytes() != encoded:
            _atomic_bytes(encoded, manifest_path)
        return {"target": str(target), "scannedFiles": len(source_hashes),
                "scannedBytes": sum(sizes.values()), "copiedFiles": copied_files,
                "copiedBytes": copied_bytes, "reusedFiles": reused_files,
                "reusedBytes": reused_bytes, "elapsedSeconds": round(time.monotonic() - started, 3)}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--target", required=True, type=Path)
    parser.add_argument("--center", help="density center X,Z (default source control or 0,0)")
    parser.add_argument("--radius", type=int, help="density radius (default source control or 96)")
    parser.add_argument("--family", choices=("structure-density", "streams-probe"), default="structure-density")
    args = parser.parse_args(argv)
    try:
        print(json.dumps(prepare(args.source, args.target, center=args.center, radius=args.radius, family_name=args.family)))
        return 0
    except (ValueError, OSError, RuntimeError) as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    raise SystemExit(main())
