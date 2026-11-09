"""Cheap, read-only checks before a prepared Streams fixture launches Java."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re


def coordinate_pairs(raw: str) -> list[tuple[int, int]]:
    if not re.fullmatch(r"-?\d+,-?\d+(;-?\d+,-?\d+)*", raw):
        raise ValueError("coordinates must be semicolon-separated integer X,Z pairs")
    return [tuple(map(int, pair.split(","))) for pair in raw.split(";")]


def select_chunks(chunks: str | None, block_sites: str | None, maximum: int) -> str | None:
    """Floor negative block coordinates, deduplicate, and retain requested order."""
    selected = coordinate_pairs(chunks) if chunks is not None else []
    if len(selected) != len(set(selected)):
        raise ValueError("duplicate requested chunks")
    sites = coordinate_pairs(block_sites) if block_sites is not None else []
    required = list(dict.fromkeys((x // 16, z // 16) for x, z in sites))
    if chunks is not None and not set(required) <= set(selected):
        missing = sorted(set(required) - set(selected))
        raise ValueError(f"requested chunks omit block sites: {missing}")
    if chunks is None:
        selected = required
    if len(selected) > maximum:
        raise ValueError("max-chunks must include all requested chunks")
    return ";".join(f"{x},{z}" for x, z in selected) if selected else None


def _file(base: Path, relative: str) -> Path:
    # Manifest paths are data, never authority to read beyond the fixture.
    path = Path(relative)
    if path.is_absolute() or path.drive or ".." in path.parts:
        raise ValueError(f"invalid manifest path: {relative}")
    target = base / path
    if not target.resolve().is_relative_to(base.resolve()) or not target.is_file():
        raise ValueError(f"missing or escaped fixture input: {relative}")
    return target


def _hash_matches(base: Path, relative: str, expected: str) -> None:
    path = _file(base, relative)
    with path.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != expected:
        raise ValueError(f"fixture input hash mismatch: {path.relative_to(base)}")


def validate_fixture(run: Path, expected_cache: str | None = None) -> dict:
    """Validate copied inputs; generated worlds may legitimately update config/state."""
    for name in ("data", "streamsreflowing"):
        if (run / name).exists():
            raise ValueError(f"misplaced {name}: it belongs under world/{name}")
    properties = (run / "server.properties").read_text(encoding="utf-8")
    level_names = re.findall(r"(?m)^\s*level-name\s*=\s*([^\r\n]*)", properties)
    if level_names != ["world"]:
        raise ValueError("Streams fixture requires exactly one level-name=world")
    manifest = json.loads((run / "input-manifest.json").read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("input manifest must be an object")
    world = run / "world"
    initialized = (world / "level.dat").is_file()
    cache = world / "streamsreflowing"
    cache_files = [p for p in cache.rglob("*") if p.is_file()]
    if cache.exists() and (not cache.is_dir() or not cache_files):
        raise ValueError("Streams cache exists but contains no graph files")
    if expected_cache == "fresh" and (initialized or cache.exists()):
        raise ValueError("fresh routing requires an unrun world without a Streams cache")
    if expected_cache == "present" and not cache_files:
        raise ValueError("expected a saved Streams cache under world/streamsreflowing")
    if not initialized and any((world / name).exists() for name in ("region", "entities", "poi")):
        raise ValueError("unrun fixture contains generated chunk directories")
    # Mods do not migrate at server startup. Configs and copied markers can do so.
    groups = [manifest, manifest.get("surfaceMods", {})]
    for group in groups:
        for mod in group.get("mods", []):
            _hash_matches(run / "mods", mod["file"], mod["sha256"])
        if not initialized:
            for name, sha in group.get("configs", {}).items():
                _hash_matches(run / "config", name, sha)
    if not initialized:
        # Accept the provenance written by the existing isolated reproduction helper.
        copied = manifest.get("playerCacheDiagnostic", {}).get("copiedFiles", {})
        for name, value in copied.items():
            if name == "streamsreflowing":
                actual = {p.relative_to(cache).as_posix() for p in cache_files}
                if not value or actual != {Path(k).as_posix() for k in value}:
                    raise ValueError("copied Streams cache inventory mismatch")
                for relative, sha in value.items():
                    _hash_matches(cache, relative, sha)
            else:
                _hash_matches(world, name, value)
        scale = manifest.get("freshScaleMarker")
        if scale:
            _hash_matches(world, "data/terrain_diffusion_world_settings.dat", scale["sha256"])
    return {"initialized": initialized, "cacheFiles": len(cache_files),
            "routingEvidence": "saved" if cache_files else "fresh"}
