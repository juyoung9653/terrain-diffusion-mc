#!/usr/bin/env python3
"""Find finite directed source-to-marine-terminal routes in a native plan CSV.

Routes are shortest paths over native X/Z segment geometry.  The selected
length is the largest of those shortest paths among reachable graph sources;
it is neither a longest simple path nor a total basin length.
"""
from __future__ import annotations

import argparse
import heapq
import json
import math
from collections import Counter, defaultdict
from pathlib import Path

from river_hierarchy import Segment, node_key, read_marine_terminals, read_segments

EPSILON = 1e-9
Node = tuple[int, int]
Edge = tuple[Node, Node]


def _inside(node: Node, bounds: tuple[float, float, float, float]) -> bool:
    x, z = node[0] / 16.0, node[1] / 16.0
    return bounds[0] <= x <= bounds[2] and bounds[1] <= z <= bounds[3]


def _node_json(node: Node) -> dict[str, float]:
    return {"x": node[0] / 16.0, "z": node[1] / 16.0}


def _geometry_tuple(segment: Segment) -> tuple[float, ...]:
    return (segment.x1, segment.z1, segment.y1, segment.w1,
            segment.x2, segment.z2, segment.y2, segment.w2)


def _length(segment: Segment) -> float:
    return math.hypot(segment.x2 - segment.x1, segment.z2 - segment.z1)


def _choose_edges(segments: list[Segment]) -> tuple[dict[Edge, Segment], int, int]:
    """Keep one rounded directed edge: minimum length, then geometry tuple."""
    chosen: dict[Edge, Segment] = {}
    self_edges = duplicates = 0
    for segment in segments:
        edge = (node_key(segment.x1, segment.z1), node_key(segment.x2, segment.z2))
        if edge[0] == edge[1]:
            self_edges += 1
            continue
        old = chosen.get(edge)
        if old is not None:
            duplicates += 1
            if (_length(segment), _geometry_tuple(segment)) < (_length(old), _geometry_tuple(old)):
                chosen[edge] = segment
        else:
            chosen[edge] = segment
    return chosen, self_edges, duplicates


def _width_bin(full_width: float) -> str:
    if full_width < 2: return "lt_2"
    if full_width < 4: return "2_to_4"
    if full_width < 8: return "4_to_8"
    if full_width < 16: return "8_to_16"
    return "ge_16"


def _edge_json(segment: Segment) -> dict[str, float]:
    length = _length(segment)
    return {"x1": segment.x1, "z1": segment.z1, "gradeY1": segment.y1, "halfWidth1": segment.w1,
            "x2": segment.x2, "z2": segment.z2, "gradeY2": segment.y2, "halfWidth2": segment.w2,
            "arcLength": length, "meanFullPlanWidth": segment.w1 + segment.w2}


def _path_widths(path: list[Segment]) -> dict:
    bins = Counter()
    initial_lt4 = initial_lt8 = 0.0
    still_lt4 = still_lt8 = True
    for segment in path:
        length, width = _length(segment), segment.w1 + segment.w2
        bins[_width_bin(width)] += length
        if still_lt4 and width < 4:
            initial_lt4 += length
        else:
            still_lt4 = False
        if still_lt8 and width < 8:
            initial_lt8 += length
        else:
            still_lt8 = False
    return {"convention": "mean full plan width (w1 + w2), not proven wetted or bankfull width",
            "lengthByFullWidthBin": {name: bins.get(name, 0.0) for name in
                ("lt_2", "2_to_4", "4_to_8", "8_to_16", "ge_16")},
            "initialUninterruptedLength": {"meanFullWidthLt4": initial_lt4, "meanFullWidthLt8": initial_lt8}}


def _reverse_dijkstra(terminal: Node, edges: dict[Edge, Segment]) -> tuple[dict[Node, float], dict[Node, Edge]]:
    reverse: dict[Node, list[Edge]] = defaultdict(list)
    for edge in edges:
        reverse[edge[1]].append(edge)
    for predecessors in reverse.values():
        predecessors.sort(key=lambda edge: _geometry_tuple(edges[edge]))
    distance = {terminal: 0.0}
    next_edge: dict[Node, Edge] = {}
    heap: list[tuple[float, Node]] = [(0.0, terminal)]
    while heap:
        current_distance, current = heapq.heappop(heap)
        if current_distance != distance.get(current):
            continue
        for edge in reverse[current]:
            start, _ = edge
            candidate = current_distance + _length(edges[edge])
            old = distance.get(start)
            if old is None or candidate < old - EPSILON or (
                    abs(candidate - old) <= EPSILON and _geometry_tuple(edges[edge]) < _geometry_tuple(edges[next_edge[start]])):
                distance[start] = candidate
                next_edge[start] = edge
                heapq.heappush(heap, (candidate, start))
    return distance, next_edge


def _reconstruct(source: Node, terminal: Node, next_edge: dict[Node, Edge], edges: dict[Edge, Segment]) -> list[Segment] | None:
    path, current, seen = [], source, set()
    while current != terminal:
        if current in seen or current not in next_edge:
            return None
        seen.add(current)
        edge = next_edge[current]
        path.append(edges[edge])
        current = edge[1]
    return path


def measure(segments: list[Segment], bounds: tuple[float, float, float, float], marine: dict[Node, bool]) -> dict:
    if not all(math.isfinite(value) for value in bounds) or not bounds[0] < bounds[2] or not bounds[1] < bounds[3]:
        raise ValueError("bounds require finite minX < maxX and minZ < maxZ")
    edges, self_edges, duplicates = _choose_edges(segments)
    nodes = {node for edge in edges for node in edge}
    incoming: dict[Node, set[Node]] = defaultdict(set)
    outgoing: dict[Node, set[Node]] = defaultdict(set)
    for start, end in edges:
        outgoing[start].add(end)
        incoming[end].add(start)
    sources = sorted(node for node in nodes if not incoming[node])
    records = []
    for terminal in sorted(node for node, true in marine.items() if true and _inside(node, bounds)):
        graph_node = terminal in nodes
        is_terminal = graph_node and not outgoing[terminal] and bool(incoming[terminal])
        record = {"terminal": _node_json(terminal), "matchedGraphNode": graph_node,
                  "isDirectedGraphTerminal": is_terminal, "reachableSourceCount": 0,
                  "chosenSource": None, "chosenSourceToTerminalShortestPathLength": None,
                  "pathEdges": [], "pathWidth": None}
        if is_terminal:
            distance, next_edge = _reverse_dijkstra(terminal, edges)
            reachable = [source for source in sources if source in distance]
            record["reachableSourceCount"] = len(reachable)
            if reachable:
                longest = max(distance[source] for source in reachable)
                source = min(source for source in reachable if abs(distance[source] - longest) <= EPSILON)
                path = _reconstruct(source, terminal, next_edge, edges)
                if path is not None:
                    record["chosenSource"] = _node_json(source)
                    record["chosenSourceToTerminalShortestPathLength"] = distance[source]
                    record["pathEdges"] = [_edge_json(edge) for edge in path]
                    record["pathWidth"] = _path_widths(path)
        records.append(record)
    return {
        "bounds": {"minX": bounds[0], "minZ": bounds[1], "maxX": bounds[2], "maxZ": bounds[3]},
        "segments": {"input": len(segments), "uniqueDirectedEdges": len(edges),
                     "parallelEdgesCollapsed": duplicates, "roundedSelfEdgesIgnored": self_edges},
        "sources": {"count": len(sources), "scope": "zero unique incoming edges in the complete exported directed graph before bounds clipping"},
        "marineTerminalsInsideBounds": records,
        "scope": "Each record starts from a true marine CSV flag inside bounds and verifies it is a directed graph terminal before routing. Reverse Dijkstra is cycle-safe. The selected value is the largest source-to-terminal shortest-path length among reachable sources, not a longest simple path or total basin length; no lake/basin claim is made."
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate", type=Path, help="native segment CSV with x1,z1,y1,w1,x2,z2,y2,w2")
    parser.add_argument("--marine-terminals", type=Path, required=True,
                        help="NaturalRiverAudit CSV with x,z,...,marineWithin32")
    parser.add_argument("--bounds", nargs=4, type=float, required=True, metavar=("MIN_X", "MIN_Z", "MAX_X", "MAX_Z"))
    parser.add_argument("--output", type=Path, help="write JSON here instead of stdout")
    args = parser.parse_args()
    result = measure(read_segments(args.candidate), tuple(args.bounds), read_marine_terminals(args.marine_terminals))
    encoded = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    else:
        print(encoded, end="")


if __name__ == "__main__":
    main()
