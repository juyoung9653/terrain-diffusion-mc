#!/usr/bin/env python3
"""Report directed river hierarchy from a native segment CSV.

Widths in this report are full *plan* widths (twice the CSV half-width), not
observed wetted or bankfull widths.  Topology is assembled from the complete
export before geometry is clipped to --bounds, so a window cannot create a
source or outlet.  An export may itself still be only a partial graph.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
from collections import Counter, defaultdict, deque
from dataclasses import dataclass
from pathlib import Path

EPSILON = 1e-9
Node = tuple[int, int]  # X/Z in sixteenths; avoids float-key noise.


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
    required = ("x1", "z1", "y1", "w1", "x2", "z2", "y2", "w2")
    with path.open(encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames is None or any(name not in reader.fieldnames for name in required):
            raise ValueError("CSV header must contain " + ",".join(required))
        result = []
        for row_number, row in enumerate(reader, start=2):
            values = [_number(row, name, row_number) for name in required]
            if values[3] < 0 or values[7] < 0:
                raise ValueError(f"row {row_number}: widths must be nonnegative")
            result.append(Segment(*values))
    return result


def _sixteenths(value: float) -> int:
    """Match Java Math.round: floor(value * 16 + 0.5), including negatives."""
    return math.floor(value * 16.0 + .5)


def node_key(x: float, z: float) -> Node:
    return (_sixteenths(x), _sixteenths(z))


def _display_node(node: Node) -> dict[str, float]:
    return {"x": node[0] / 16.0, "z": node[1] / 16.0}


def _inside(node: Node, bounds: tuple[float, float, float, float]) -> bool:
    x, z = node[0] / 16.0, node[1] / 16.0
    return bounds[0] <= x <= bounds[2] and bounds[1] <= z <= bounds[3]


def _clip_parameters(segment: Segment, bounds: tuple[float, float, float, float]) -> tuple[float, float] | None:
    min_x, min_z, max_x, max_z = bounds
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


def _clipped_length_width(segment: Segment, bounds: tuple[float, float, float, float]) -> tuple[float, float] | None:
    parameters = _clip_parameters(segment, bounds)
    if parameters is None:
        return None
    start, end = parameters
    x1, z1, _, w1 = _at(segment, start)
    x2, z2, _, w2 = _at(segment, end)
    return math.hypot(x2 - x1, z2 - z1), w1 + w2  # average full width


def _width_bin(width: float) -> str:
    if width < 2: return "lt_2"
    if width < 4: return "2_to_4"
    if width < 8: return "4_to_8"
    if width < 16: return "8_to_16"
    return "ge_16"


def _weighted_quantile(values: list[tuple[float, float]], fraction: float) -> float:
    if not values:
        return 0.0
    total = sum(weight for _, weight in values)
    if total <= EPSILON:
        return 0.0
    target = total * fraction
    running = 0.0
    for value, weight in sorted(values):
        running += weight
        if running + EPSILON >= target:
            return value
    return values[-1][0]


def _components(nodes: set[Node], outgoing: dict[Node, set[Node]]) -> list[set[Node]]:
    adjacent: dict[Node, set[Node]] = {node: set() for node in nodes}
    for start, ends in outgoing.items():
        for end in ends:
            adjacent[start].add(end)
            adjacent[end].add(start)
    unseen, result = set(nodes), []
    while unseen:
        todo, component = [min(unseen)], set()
        while todo:
            node = todo.pop()
            if node not in unseen:
                continue
            unseen.remove(node)
            component.add(node)
            todo.extend(adjacent[node] & unseen)
        result.append(component)
    return sorted(result, key=lambda group: min(group))


def _is_true(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


def read_marine_terminals(path: Path) -> dict[Node, bool]:
    """Read NaturalRiverAudit x,z,...,marineWithin32 rows (or legacy x,z,true)."""
    with path.open(encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source)
        required = ("x", "z")
        if reader.fieldnames is None or any(name not in reader.fieldnames for name in required):
            raise ValueError("marine-terminal CSV header must contain x,z and marineWithin32 (or legacy true)")
        flag = "marineWithin32" if "marineWithin32" in reader.fieldnames else "true" if "true" in reader.fieldnames else None
        if flag is None:
            raise ValueError("marine-terminal CSV header must contain marineWithin32 (or legacy true)")
        result: dict[Node, bool] = {}
        for row_number, row in enumerate(reader, start=2):
            key = node_key(_number(row, "x", row_number), _number(row, "z", row_number))
            result[key] = result.get(key, False) or _is_true(row[flag])
    return result


def measure(segments: list[Segment], bounds: tuple[float, float, float, float],
            marine_terminals: dict[Node, bool] | None = None) -> dict:
    if not all(math.isfinite(value) for value in bounds) or not bounds[0] < bounds[2] or not bounds[1] < bounds[3]:
        raise ValueError("bounds require finite minX < maxX and minZ < maxZ")
    marine_terminals = marine_terminals or {}
    width_lengths: list[tuple[float, float]] = []
    histogram = Counter()
    for segment in segments:
        clipped = _clipped_length_width(segment, bounds)
        if clipped is not None and clipped[0] > EPSILON:
            length, width = clipped
            width_lengths.append((width, length))
            histogram[_width_bin(width)] += length

    nodes: set[Node] = set()
    outgoing: dict[Node, set[Node]] = defaultdict(set)
    incoming: dict[Node, set[Node]] = defaultdict(set)
    edge_length: dict[tuple[Node, Node], float] = {}
    self_edges = parallel_edges = 0
    for segment in segments:
        start, end = node_key(segment.x1, segment.z1), node_key(segment.x2, segment.z2)
        if start == end:
            self_edges += 1
            continue
        nodes.update((start, end))
        edge = (start, end)
        length = math.hypot(segment.x2 - segment.x1, segment.z2 - segment.z1)
        if edge in edge_length:
            parallel_edges += 1
            edge_length[edge] = max(edge_length[edge], length)
            continue
        outgoing[start].add(end)
        incoming[end].add(start)
        edge_length[edge] = length

    component_rows = []
    terminal_rows = []
    first_join_lengths: list[float] = []
    invalid_components = 0
    for index, component in enumerate(_components(nodes, outgoing)):
        component_id = f"component-{index:03d}"
        local_out = {node: outgoing[node] & component for node in component}
        local_in = {node: incoming[node] & component for node in component}
        sources = sorted(node for node in component if not local_in[node] and local_out[node])
        terminals = sorted(node for node in component if not local_out[node] and local_in[node])
        indegree = {node: len(local_in[node]) for node in component}
        queue = deque(sorted(node for node in component if indegree[node] == 0))
        order = []
        while queue:
            node = queue.popleft()
            order.append(node)
            for end in sorted(local_out[node]):
                indegree[end] -= 1
                if indegree[end] == 0:
                    queue.append(end)
        cyclic = len(order) != len(component)
        ambiguous = len(terminals) != 1
        valid = not cyclic and not ambiguous
        component_rows.append({"id": component_id, "nodes": len(component), "sources": len(sources),
                               "confluences": sum(len(local_in[node]) >= 2 for node in component),
                               "terminals": len(terminals), "cycle": cyclic,
                               "multiTerminalAmbiguity": ambiguous, "dagMetricsAvailable": valid})
        if not valid:
            invalid_components += 1
            continue
        distances = {node: 0.0 for node in sources}
        for node in order:
            if node not in distances:
                continue
            for end in local_out[node]:
                distances[end] = max(distances.get(end, -math.inf), distances[node] + edge_length[(node, end)])
        for source in sources:
            if not _inside(source, bounds):
                continue
            node, length = source, 0.0
            while len(local_out[node]) == 1:
                end = next(iter(local_out[node]))
                length += edge_length[(node, end)]
                node = end
                if len(local_in[node]) >= 2:
                    first_join_lengths.append(length)
                    break
        terminal = terminals[0]
        if _inside(terminal, bounds):
            terminal_rows.append({"componentId": component_id, "terminal": _display_node(terminal),
                                  "longestUpstreamArcLength": distances.get(terminal, 0.0),
                                  "upstreamSources": len(sources),
                                  "upstreamConfluences": sum(len(local_in[node]) >= 2 for node in component),
                                  "marineTerminalMatched": terminal in marine_terminals,
                                  "marineTerminalTrue": marine_terminals.get(terminal, False)})

    first_join_lengths.sort()
    return {
        "bounds": {"minX": bounds[0], "minZ": bounds[1], "maxX": bounds[2], "maxZ": bounds[3]},
        "segments": {"input": len(segments), "uniqueDirectedEdges": len(edge_length),
                     "parallelEdgesIgnored": parallel_edges, "selfEdgesIgnored": self_edges},
        "width": {"convention": "full plan width (2 * native CSV half-width); not proven wetted or bankfull width",
                  "lengthByFullWidthBin": {name: histogram.get(name, 0.0) for name in
                     ("lt_2", "2_to_4", "4_to_8", "8_to_16", "ge_16")},
                  "lengthWeightedQuantiles": {"p10": _weighted_quantile(width_lengths, .10),
                     "p25": _weighted_quantile(width_lengths, .25), "p50": _weighted_quantile(width_lengths, .50),
                     "p75": _weighted_quantile(width_lengths, .75), "p90": _weighted_quantile(width_lengths, .90)}},
        "firstOrderToFirstConfluence": {"scope": "Sources whose quantized node is inside bounds; each path uses the complete exported graph and may extend outside bounds.",
           "sourcesInsideBounds": sum(_inside(node, bounds) for node in nodes if not incoming[node] and outgoing[node]),
           "count": len(first_join_lengths), "lengths": first_join_lengths,
           "countGe128": sum(value >= 128 for value in first_join_lengths),
           "countGe256": sum(value >= 256 for value in first_join_lengths),
           "countGe512": sum(value >= 512 for value in first_join_lengths)},
        "components": component_rows, "terminalsInsideBounds": terminal_rows,
        "scope": "Topology and path lengths use the complete exported graph before bounds clipping. An export may itself be partial; cyclic or multi-terminal components have no DAG hierarchy metrics. Only terminals inside bounds are reported."
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate", type=Path, help="CSV with x1,z1,y1,w1,x2,z2,y2,w2")
    parser.add_argument("--bounds", nargs=4, type=float, required=True, metavar=("MIN_X", "MIN_Z", "MAX_X", "MAX_Z"))
    parser.add_argument("--marine-terminals", type=Path,
                        help="optional NaturalRiverAudit CSV with x,z,gradeY,fullWidth,marineWithin32 (legacy x,z,true allowed)")
    parser.add_argument("--output", type=Path, help="write JSON here instead of stdout")
    args = parser.parse_args()
    result = measure(read_segments(args.candidate), tuple(args.bounds),
                     read_marine_terminals(args.marine_terminals) if args.marine_terminals else None)
    encoded = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    else:
        print(encoded, end="")


if __name__ == "__main__":
    main()
