"""Strict, bounded restart evidence for Streams probe worlds.

The surface CSV includes both generated blocks and diagnostic values recomputed
when a probe runs.  Compare the former exactly and report the latter separately.
"""
from __future__ import annotations

import csv
import hashlib
import math
import re
from pathlib import Path

_CHUNK = re.compile(r"^td-streams-probe-surface-audit-(-?\d+)-(-?\d+)\.csv$")
_COORDINATE = re.compile(r"^-?\d+,-?\d+$")
_RECOMPUTED = frozenset({"ecoTemperature", "ecoMoisture", "ecoSlope", "originalGroundY"})
_REQUIRED_MARKERS = frozenset({"terrain_diffusion_world_settings.dat",
                               "terrain_diffusion_natural_rivers.dat"})
_REQUIRED_SURFACE = frozenset({"x", "z", "actualTopmostY", "actualTopmostBlock",
                               "terrainTopSolidY", "terrainTopSolidBlock",
                               "actualSavedTerrainBiome", "actualTopWaterY"})
_PHYSICAL_Y = frozenset({"actualTopmostY", "terrainTopSolidY", "actualTopWaterY"})


def _sha(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _files(path: Path) -> dict[str, str]:
    if not path.is_dir():
        raise ValueError(f"missing required directory: {path}")
    files = {file.relative_to(path).as_posix(): _sha(file)
             for file in sorted(path.rglob("*")) if file.is_file()}
    if not files:
        raise ValueError(f"required directory is empty: {path}")
    return files


def _surface(world: Path) -> tuple[list[str], list[str], dict[str, dict[str, str]]]:
    chunks: set[tuple[int, int]] = set()
    rows: dict[str, dict[str, str]] = {}
    schema: list[str] | None = None
    for path in sorted(world.glob("td-streams-probe-surface-audit-*.csv")):
        match = _CHUNK.fullmatch(path.name)
        if match is None:
            raise ValueError(f"invalid surface audit filename: {path.name}")
        chunk = int(match.group(1)), int(match.group(2))
        if chunk in chunks:
            raise ValueError(f"duplicate surface audit chunk: {chunk}")
        chunks.add(chunk)
        with path.open(encoding="utf-8", newline="") as stream:
            reader = csv.DictReader(stream)
            if not reader.fieldnames or len(reader.fieldnames) != len(set(reader.fieldnames)):
                raise ValueError(f"invalid surface audit header: {path.name}")
            current = list(reader.fieldnames)
            if not _REQUIRED_SURFACE <= set(current):
                raise ValueError(f"surface audit lacks generated-block fields: {path.name}")
            if schema is None:
                schema = current
            elif schema != current:
                raise ValueError(f"surface audit schema differs: {path.name}")
            count = 0
            for row in reader:
                if None in row or any(value is None for value in row.values()):
                    raise ValueError(f"malformed surface audit row: {path.name}")
                try:
                    x, z = int(row["x"]), int(row["z"])
                except ValueError as exc:
                    raise ValueError(f"non-integer surface coordinate: {path.name}") from exc
                if any(not row[field] for field in _REQUIRED_SURFACE):
                    raise ValueError(f"missing generated-block value: {path.name}")
                try:
                    for field in _PHYSICAL_Y:
                        int(row[field])
                except ValueError as exc:
                    raise ValueError(f"non-integer generated height: {path.name}") from exc
                if x // 16 != chunk[0] or z // 16 != chunk[1]:
                    raise ValueError(f"surface row outside named chunk: {path.name}")
                coordinate = f"{x},{z}"
                if coordinate in rows:
                    raise ValueError(f"duplicate surface coordinate: {coordinate}")
                rows[coordinate] = {key: row[key] for key in current}
                count += 1
            if count != 256:
                raise ValueError(f"surface audit must contain 256 columns: {path.name}")
    if schema is None or not rows:
        raise ValueError("surface audit CSV missing")
    return schema, [f"{x},{z}" for x, z in sorted(chunks)], rows


def capture_snapshot(world: Path) -> dict:
    """Capture saved graph/material state; reject incomplete generated evidence."""
    world = Path(world)
    data = world / "data"
    markers = {file.name: _sha(file) for file in sorted(data.glob("terrain_diffusion*.dat"))
               if file.is_file()}
    if not _REQUIRED_MARKERS <= markers.keys():
        raise ValueError("required TD world markers missing")
    network = world / "td-natural-river-network.csv"
    if not network.is_file():
        raise ValueError("natural river network export missing")
    with network.open(encoding="utf-8", newline="") as stream:
        rows = csv.reader(stream)
        if next(rows, None) != ["x1", "z1", "y1", "w1", "x2", "z2", "y2", "w2"]:
            raise ValueError("natural river network export is empty or malformed")
        count = 0
        for row in rows:
            try:
                if len(row) != 8 or not all(math.isfinite(float(value)) for value in row):
                    raise ValueError
            except ValueError as exc:
                raise ValueError("natural river network export is empty or malformed") from exc
            count += 1
        if not count:
            raise ValueError("natural river network export is empty or malformed")
    schema, chunks, surface = _surface(world)
    return {"markers": markers, "streamsCache": _files(world / "streamsreflowing"),
            "network": _sha(network), "surfaceSchema": schema, "chunks": chunks,
            "surface": surface}


def compare_snapshots(before: dict, after: dict) -> dict:
    """Return bounded differences; only known recomputed diagnostics may differ."""
    result: dict[str, object] = {"passed": False, "failures": [], "recomputed": {}}
    failures: list[str] = result["failures"]  # type: ignore[assignment]
    for label, snapshot in (("before", before), ("after", after)):
        if not isinstance(snapshot, dict):
            failures.append(f"{label}_snapshot_invalid")
            continue
        markers, cache, network, schema, chunks, surface = (snapshot.get(key) for key in
            ("markers", "streamsCache", "network", "surfaceSchema", "chunks", "surface"))
        if (not isinstance(markers, dict) or not _REQUIRED_MARKERS <= markers.keys()
                or not isinstance(cache, dict) or not cache
                or not isinstance(network, str) or len(network) != 64
                or not isinstance(schema, list) or not all(isinstance(field, str) for field in schema)
                or not _REQUIRED_SURFACE <= set(schema)
                or not isinstance(chunks, list) or not chunks or any(not isinstance(chunk, str) for chunk in chunks)
                or not isinstance(surface, dict) or not surface
                or any(not isinstance(key, str) or not _COORDINATE.fullmatch(key)
                       or not isinstance(value, dict) or set(value) != set(schema)
                       for key, value in surface.items())):
            failures.append(f"{label}_snapshot_invalid")
    if failures:
        return result
    for key in ("markers", "streamsCache", "network", "surfaceSchema", "chunks"):
        if before.get(key) != after.get(key):
            failures.append(f"{key}_changed")
    old, new = before.get("surface"), after.get("surface")
    if not isinstance(old, dict) or not isinstance(new, dict):
        failures.append("surface_missing")
        return result
    if old.keys() != new.keys():
        failures.append("surface_coordinates_changed")
        return result
    differences: dict[str, int] = {}
    examples: dict[str, str] = {}
    for coordinate in sorted(old):
        left, right = old[coordinate], new[coordinate]
        if left.keys() != right.keys():
            failures.append("surface_row_schema_changed")
            break
        for field in left:
            if left[field] != right[field]:
                if field in _RECOMPUTED:
                    differences[field] = differences.get(field, 0) + 1
                else:
                    examples.setdefault(field, coordinate)
    if examples:
        failures.extend(f"surface_{field}_changed@{coordinate}"
                        for field, coordinate in examples.items())
    result["recomputed"] = differences
    result["passed"] = not failures
    return result
