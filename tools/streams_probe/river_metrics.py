#!/usr/bin/env python3
"""Measure a directed river-segment CSV without starting Minecraft.

Input rows require x1,z1,y1,w1,x2,z2,y2,w2.  Width is a native half-width.
Bounds are required so density is always comparable: --bounds minX minZ maxX maxZ.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

EPSILON = 1e-9


@dataclass(frozen=True)
class Segment:
    x1: float
    z1: float
    y1: float
    w1: float
    x2: float
    z2: float
    y2: float
    w2: float


def _number(row: dict[str, str], name: str, row_number: int) -> float:
    try:
        value = float(row[name])
    except (KeyError, ValueError) as error:
        raise ValueError(f"row {row_number}: invalid or missing {name}") from error
    if not math.isfinite(value):
        raise ValueError(f"row {row_number}: {name} must be finite")
    return value


def read_segments(path: Path) -> list[Segment]:
    with path.open(encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source)
        required = ("x1", "z1", "y1", "w1", "x2", "z2", "y2", "w2")
        if reader.fieldnames is None or any(name not in reader.fieldnames for name in required):
            raise ValueError("CSV header must contain " + ",".join(required))
        result = []
        for row_number, row in enumerate(reader, start=2):
            values = [_number(row, name, row_number) for name in required]
            if values[3] < 0 or values[7] < 0:
                raise ValueError(f"row {row_number}: widths must be nonnegative")
            result.append(Segment(*values))
    return result


def _clip_parameters(segment: Segment, bounds: tuple[float, float, float, float]) -> tuple[float, float] | None:
    """Liang-Barsky clip parameters for a closed X/Z rectangle."""
    min_x, min_z, max_x, max_z = bounds
    if not min_x < max_x or not min_z < max_z:
        raise ValueError("bounds require minX < maxX and minZ < maxZ")
    dx, dz = segment.x2 - segment.x1, segment.z2 - segment.z1
    start, end = 0.0, 1.0
    for p, q in ((-dx, segment.x1 - min_x), (dx, max_x - segment.x1),
                 (-dz, segment.z1 - min_z), (dz, max_z - segment.z1)):
        if abs(p) <= EPSILON:
            if q < 0:
                return None
            continue
        value = q / p
        if p < 0:
            if value > end:
                return None
            start = max(start, value)
        else:
            if value < start:
                return None
            end = min(end, value)
    return (start, end) if end >= start else None


def _at(segment: Segment, t: float) -> tuple[float, float, float, float]:
    return (segment.x1 + (segment.x2 - segment.x1) * t,
            segment.z1 + (segment.z2 - segment.z1) * t,
            segment.y1 + (segment.y2 - segment.y1) * t,
            segment.w1 + (segment.w2 - segment.w1) * t)


def clip_segment(segment: Segment, bounds: tuple[float, float, float, float]) -> Segment | None:
    parameters = _clip_parameters(segment, bounds)
    if parameters is None:
        return None
    start, end = parameters
    x1, z1, y1, w1 = _at(segment, start)
    x2, z2, y2, w2 = _at(segment, end)
    return Segment(x1, z1, y1, w1, x2, z2, y2, w2)


def _node(x: float, z: float) -> tuple[float, float]:
    # Exact coordinates are the graph contract. Normalize only signed zero for stable JSON.
    return (0.0 if x == 0 else x, 0.0 if z == 0 else z)


def _line_intersection(a: Segment, x1: float, z1: float, x2: float, z2: float) -> tuple[float, float] | None:
    """Return one point for finite non-collinear segment intersections."""
    rx, rz = a.x2 - a.x1, a.z2 - a.z1
    sx, sz = x2 - x1, z2 - z1
    cross = rx * sz - rz * sx
    if abs(cross) <= EPSILON:
        return None
    qx, qz = x1 - a.x1, z1 - a.z1
    t = (qx * sz - qz * sx) / cross
    u = (qx * rz - qz * rx) / cross
    if -EPSILON <= t <= 1 + EPSILON and -EPSILON <= u <= 1 + EPSILON:
        return (a.x1 + min(1.0, max(0.0, t)) * rx,
                a.z1 + min(1.0, max(0.0, t)) * rz)
    return None


def _slope_bin(drop_per_block: float) -> str:
    if drop_per_block < -EPSILON:
        return "uphill"
    if drop_per_block <= EPSILON:
        return "level"
    if drop_per_block < .01:
        return "down_lt_0_01"
    if drop_per_block < .05:
        return "down_0_01_to_0_05"
    return "down_ge_0_05"


def measure(segments: Iterable[Segment], bounds: tuple[float, float, float, float],
            transects: Iterable[tuple[float, float, float, float]] = ()) -> dict:
    if not all(math.isfinite(value) for value in bounds) or not bounds[0] < bounds[2] or not bounds[1] < bounds[3]:
        raise ValueError("bounds require finite minX < maxX and minZ < maxZ")
    original = list(segments)
    clipped = [part for segment in original if (part := clip_segment(segment, bounds)) is not None]
    area = (bounds[2] - bounds[0]) * (bounds[3] - bounds[1])
    lengths = [math.hypot(item.x2 - item.x1, item.z2 - item.z1) for item in clipped]
    total_length = sum(lengths)
    downward = upward = 0.0
    weighted_width_sum = 0.0
    bins: Counter[str] = Counter()
    for item, length in zip(clipped, lengths):
        signed_drop = item.y1 - item.y2
        downward += max(0.0, signed_drop)
        upward += max(0.0, -signed_drop)
        weighted_width_sum += ((item.w1 + item.w2) / 2.0) * length
        bins[_slope_bin(signed_drop / length if length > EPSILON else 0.0)] += 1

    # Determine degrees using the complete exported graph; count real nodes inside
    # the rectangle. Neither clipped ends nor missing outside neighbours become sources.
    outgoing: dict[tuple[float, float], int] = Counter()
    incoming: dict[tuple[float, float], int] = Counter()
    adjacent: dict[tuple[float, float], set[tuple[float, float]]] = defaultdict(set)
    touched = set()
    for item in original:
        start, end = _node(item.x1, item.z1), _node(item.x2, item.z2)
        if clip_segment(item, bounds) is not None:
            touched.update((start, end))
        outgoing[start] += 1
        incoming[end] += 1
        adjacent[start].add(end)
        adjacent[end].add(start)
    unseen, components = set(adjacent), 0
    while unseen:
        todo = [unseen.pop()]
        intersects = False
        while todo:
            current = todo.pop()
            intersects |= current in touched
            for neighbor in adjacent[current]:
                if neighbor in unseen:
                    unseen.remove(neighbor)
                    todo.append(neighbor)
        components += int(intersects)
    counted_nodes = {node for node in adjacent if bounds[0] <= node[0] <= bounds[2] and bounds[1] <= node[1] <= bounds[3]}

    transect_data = []
    for tx1, tz1, tx2, tz2 in transects:
        points = set()
        for item in clipped:
            point = _line_intersection(item, tx1, tz1, tx2, tz2)
            if point is not None:
                points.add((round(point[0], 9), round(point[1], 9)))
        transect_data.append({"x1": tx1, "z1": tz1, "x2": tx2, "z2": tz2,
                              "crossings": len(points)})

    return {
        "bounds": {"minX": bounds[0], "minZ": bounds[1], "maxX": bounds[2], "maxZ": bounds[3], "area": area},
        "segments": {"input": len(original), "intersectingBounds": len(clipped)},
        "network": {"components": components, "nodes": len(counted_nodes),
                    "sources": sum(incoming[node] == 0 and outgoing[node] > 0 for node in counted_nodes),
                    "outlets": sum(outgoing[node] == 0 and incoming[node] > 0 for node in counted_nodes),
                    "junctions": sum(incoming[node] >= 2 for node in counted_nodes)},
        "geometry": {"length": total_length, "lengthPerArea": total_length / area,
                     "lengthWeightedHalfWidth": weighted_width_sum / total_length if total_length else 0.0,
                     "totalDownwardChange": downward, "totalUpwardChange": upward,
                     "slopeBins": {name: bins.get(name, 0) for name in
                                   ("uphill", "level", "down_lt_0_01", "down_0_01_to_0_05", "down_ge_0_05")}},
        "transects": transect_data,
        "scope": "Geometric native-plan metrics only; does not prove generated water, fluid ticks, or physical continuity."
    }


def _delta(candidate: dict, baseline: dict) -> dict:
    keys = ("length", "lengthPerArea", "lengthWeightedHalfWidth", "totalDownwardChange", "totalUpwardChange")
    return {key: candidate["geometry"][key] - baseline["geometry"][key] for key in keys} | {
        key: candidate["network"][key] - baseline["network"][key]
        for key in ("components", "nodes", "sources", "outlets", "junctions")
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate", type=Path, help="CSV to measure")
    parser.add_argument("--bounds", nargs=4, type=float, required=True, metavar=("MIN_X", "MIN_Z", "MAX_X", "MAX_Z"))
    parser.add_argument("--transect", nargs=4, type=float, action="append", default=[], metavar=("X1", "Z1", "X2", "Z2"),
                        help="repeatable crossing line; shared-node crossings are deduplicated")
    parser.add_argument("--baseline", type=Path, help="optional CSV measured under the same bounds/transects")
    parser.add_argument("--output", type=Path, help="write JSON here instead of stdout")
    args = parser.parse_args()
    candidate = measure(read_segments(args.candidate), tuple(args.bounds), args.transect)
    result = {"candidate": candidate}
    if args.baseline:
        baseline = measure(read_segments(args.baseline), tuple(args.bounds), args.transect)
        result["baseline"] = baseline
        result["deltaCandidateMinusBaseline"] = _delta(candidate, baseline)
    encoded = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    else:
        print(encoded, end="")


if __name__ == "__main__":
    main()
