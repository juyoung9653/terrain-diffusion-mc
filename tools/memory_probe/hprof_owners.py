#!/usr/bin/env python3
"""Find large primitive HPROF arrays and the references that point at them.

This deliberately reports incoming references, not shallow size or retained size.
It scans a raw Java HPROF 1.0.2 file through mmap and only keeps bounded metadata,
the selected arrays, and the selected reverse-reference frontier in memory.
"""
from __future__ import annotations

import argparse
import heapq
import json
import mmap
import os
import struct
import tempfile
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable

STRING, LOAD_CLASS, HEAP_DUMP, HEAP_DUMP_SEGMENT = 0x01, 0x02, 0x0C, 0x1C
ROOT_TAGS = {0x01: "jni_global", 0x02: "jni_local", 0x03: "java_frame", 0x04: "native_stack",
             0x05: "sticky_class", 0x06: "thread_block", 0x07: "monitor_used", 0x08: "thread_object",
             0xFF: "unknown"}
CLASS_DUMP, INSTANCE_DUMP, OBJECT_ARRAY, PRIMITIVE_ARRAY, PRIMITIVE_ARRAY_NODATA = 0x20, 0x21, 0x22, 0x23, 0xC3
HEAP_DUMP_INFO = 0xFE
TYPE_SIZES = {2: None, 4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}
TYPE_NAMES = {4: "boolean", 5: "char", 6: "float", 7: "double", 8: "byte", 9: "short", 10: "int", 11: "long"}


class HprofError(RuntimeError): pass


@dataclass
class ClassInfo:
    ident: int
    super_id: int
    name_id: int = 0
    fields: list[tuple[int, int]] = field(default_factory=list)
    static_fields: list[tuple[int, int, int | None]] = field(default_factory=list)


@dataclass
class Array:
    ident: int
    kind: int
    length: int
    bytes: int
    payload_offset: int = 0
    payload_bytes_in_file: int = 0


class Hprof:
    def __init__(self, path: Path, max_strings: int, max_string_bytes: int, max_classes: int):
        self.path, self.max_strings, self.max_string_bytes, self.max_classes = path, max_strings, max_string_bytes, max_classes
        self.file = path.open("rb")
        self.mm = mmap.mmap(self.file.fileno(), 0, access=mmap.ACCESS_READ)
        nul = self.mm.find(b"\0", 0, min(len(self.mm), 128))
        if nul < 0 or not self.mm[:nul].startswith(b"JAVA PROFILE 1.0."):
            raise HprofError("not a raw Java HPROF 1.0.x file")
        self.version = self.mm[:nul].decode("ascii", "replace")
        self.ids = self.u4(nul + 1)
        if self.ids not in (4, 8): raise HprofError(f"unsupported identifier size {self.ids}")
        self.records_at = nul + 13
        self.strings: dict[int, str] = {}
        self.classes: dict[int, ClassInfo] = {}
        self.load_names: dict[int, int] = {}
        self.warnings: list[str] = []
        self._layouts: dict[int, list[tuple[int, int, int]]] = {}
        self._tracked_limit_warned = False

    def close(self): self.mm.close(); self.file.close()
    def u1(self, at: int) -> int: return self.mm[at]
    def u2(self, at: int) -> int: return struct.unpack_from(">H", self.mm, at)[0]
    def u4(self, at: int) -> int: return struct.unpack_from(">I", self.mm, at)[0]
    def oid(self, at: int) -> int: return int.from_bytes(self.mm[at:at + self.ids], "big")
    def need(self, at: int, size: int, end: int):
        if at + size > end: raise HprofError(f"truncated heap subrecord at 0x{at:x}")

    def records(self):
        at, end = self.records_at, len(self.mm)
        while at < end:
            if at + 9 > end: raise HprofError("truncated top-level record")
            tag, size = self.u1(at), self.u4(at + 5)
            body, nxt = at + 9, at + 9 + size
            if nxt > end: raise HprofError(f"truncated record tag 0x{tag:02x}")
            yield tag, body, nxt
            at = nxt

    def text(self, ident: int) -> str:
        return self.strings.get(ident, f"<string#{ident:#x}>")
    def class_name(self, ident: int) -> str:
        info = self.classes.get(ident)
        return self.text(info.name_id) if info else f"<class#{ident:#x}>"
    def value_size(self, typ: int) -> int:
        if typ not in TYPE_SIZES: raise HprofError(f"unknown HPROF value type {typ}")
        size = TYPE_SIZES[typ]
        return self.ids if size is None else size

    def metadata_pass(self, arrays: int, min_bytes: int, min_array: int, max_tracked: int, root_file):
        selected: list[tuple[int, int, Array]] = []
        tracked: dict[int, Array] = {}
        aggregate: Counter[tuple[str, str]] = Counter()
        for tag, body, end in self.records():
            if tag == STRING:
                ident, raw = self.oid(body), bytes(self.mm[body + self.ids:end])
                if len(self.strings) < self.max_strings:
                    self.strings[ident] = raw[:self.max_string_bytes].decode("utf-8", "replace")
                elif len(self.strings) == self.max_strings:
                    self.warnings.append("metadata string limit reached; later field/class names are placeholders")
                    self.max_strings -= 1
            elif tag == LOAD_CLASS:
                if body + 4 + self.ids + 4 + self.ids > end: raise HprofError("truncated LOAD_CLASS")
                self.load_names[self.oid(body + 4)] = self.oid(body + 4 + self.ids + 4)
            elif tag in (HEAP_DUMP, HEAP_DUMP_SEGMENT):
                self._heap(body, end, lambda sub, at, nxt: self._first_sub(sub, at, nxt, selected, arrays, min_bytes, min_array, max_tracked, tracked, aggregate, root_file))
        for ident, name in self.load_names.items():
            if ident in self.classes: self.classes[ident].name_id = name
        out = [item[2] for item in sorted(selected, reverse=True)]
        summary: dict[str, dict[str, int]] = {}
        for key, total in aggregate.items():
            if len(key) != 2: continue
            typ, bucket = key
            count = aggregate[(typ, bucket, "count")]
            summary[f"{typ}:{bucket}"] = {"count": count, "payloadBytes": total}
        return out, tracked, summary

    def _first_sub(self, sub: int, at: int, nxt: int, selected, limit: int, minimum: int, min_array: int, max_tracked: int, tracked, aggregate, root_file):
        if sub in ROOT_TAGS:
            ident = self.oid(at)
            root_file.write(struct.pack(">QB", ident, sub))
        elif sub == CLASS_DUMP:
            self._class_dump(at, nxt, statics=False)
        elif sub in (PRIMITIVE_ARRAY, PRIMITIVE_ARRAY_NODATA):
            ident, typ, length, payload_at = self._primitive_header(sub, at, nxt, header=True)
            width = TYPE_SIZES.get(typ)
            if width is None: raise HprofError(f"unknown primitive-array type {typ}")
            payload = length * width
            name, bucket = TYPE_NAMES.get(typ, f"type{typ}"), self._bucket(length)
            aggregate[(name, bucket)] += payload; aggregate[(name, bucket, "count")] += 1
            if payload >= minimum:
                item = (payload, ident, Array(ident, typ, length, payload, payload_at,
                                               0 if sub == PRIMITIVE_ARRAY_NODATA else nxt - payload_at))
                if len(selected) < limit: heapq.heappush(selected, item)
                elif item > selected[0]: heapq.heapreplace(selected, item)
            if payload >= min_array:
                if len(tracked) < max_tracked:
                    tracked[ident] = Array(ident, typ, length, payload, payload_at,
                                           0 if sub == PRIMITIVE_ARRAY_NODATA else nxt - payload_at)
                elif not self._tracked_limit_warned:
                    self.warnings.append("tracked primitive-array limit reached; owner aggregation is partial")
                    self._tracked_limit_warned = True

    @staticmethod
    def _bucket(n: int) -> str:
        return "0" if n == 0 else f"2^{n.bit_length() - 1}..2^{n.bit_length()}-1"

    def _heap(self, at: int, end: int, visit: Callable[[int, int, int], None]):
        while at < end:
            sub = self.u1(at); start = at; at += 1
            if sub in ROOT_TAGS:
                extra = {0x01: self.ids, 0x02: 8, 0x03: 8, 0x04: 4, 0x05: 0, 0x06: 4, 0x07: 0, 0x08: 8, 0xFF: 0}[sub]
                nxt = at + self.ids + extra
            elif sub == HEAP_DUMP_INFO: nxt = at + 4 + self.ids
            elif sub == CLASS_DUMP: nxt = self._class_end(at, end)
            elif sub == INSTANCE_DUMP:
                base = at + self.ids + 4 + self.ids; self.need(base, 4, end); nxt = base + 4 + self.u4(base)
            elif sub == OBJECT_ARRAY:
                base = at + self.ids + 4; self.need(base, 4 + self.ids, end); nxt = base + 4 + self.ids + self.u4(base) * self.ids
            elif sub in (PRIMITIVE_ARRAY, PRIMITIVE_ARRAY_NODATA):
                ident, typ, length, head = self._primitive_header(sub, at, end, header=True)
                width = TYPE_SIZES.get(typ)
                if width is None: raise HprofError(f"unknown primitive-array type {typ}")
                nxt = head if sub == PRIMITIVE_ARRAY_NODATA else head + length * width
            else:
                raise HprofError(f"unknown heap subrecord 0x{sub:02x} at 0x{start:x}; refusing to desynchronize")
            self.need(at, nxt - at, end); visit(sub, at, nxt); at = nxt

    def _class_end(self, at: int, end: int) -> int:
        p = at + self.ids + 4 + self.ids * 6 + 4; self.need(p, 2, end)
        constants = self.u2(p); p += 2
        for _ in range(constants):
            self.need(p, 3, end); typ = self.u1(p + 2); p += 3 + self.value_size(typ)
        self.need(p, 2, end); statics = self.u2(p); p += 2
        for _ in range(statics): self.need(p, self.ids + 1, end); typ = self.u1(p + self.ids); p += self.ids + 1 + self.value_size(typ)
        self.need(p, 2, end); fields = self.u2(p); return p + 2 + fields * (self.ids + 1)

    def _class_dump(self, at: int, end: int, statics: bool) -> ClassInfo:
        ident, super_id = self.oid(at), self.oid(at + self.ids + 4)
        if ident not in self.classes:
            if len(self.classes) >= self.max_classes: raise HprofError("class metadata limit reached")
            self.classes[ident] = ClassInfo(ident, super_id, self.load_names.get(ident, 0))
        info = self.classes[ident]; p = at + self.ids + 4 + self.ids * 6 + 4
        constants = self.u2(p); p += 2
        for _ in range(constants): typ = self.u1(p + 2); p += 3 + self.value_size(typ)
        count = self.u2(p); p += 2; fields = []
        for _ in range(count):
            name, typ = self.oid(p), self.u1(p + self.ids); p += self.ids + 1
            value = self.oid(p) if typ == 2 else None; p += self.value_size(typ); fields.append((name, typ, value))
        field_count = self.u2(p); p += 2
        info.fields = [(self.oid(p + n * (self.ids + 1)), self.u1(p + n * (self.ids + 1) + self.ids)) for n in range(field_count)]
        info.static_fields = fields
        return info

    def _primitive_header(self, sub: int, at: int, end: int, header: bool = False):
        p = at + self.ids + 4; self.need(p, 5, end); length, typ = self.u4(p), self.u1(p + 4); head = p + 5
        result = (self.oid(at), typ, length, head)
        return result if header else result[:3]

    def layout(self, class_id: int) -> list[tuple[int, int, int]] | None:
        if class_id in self._layouts: return self._layouts[class_id]
        chain, seen, current = [], set(), class_id
        while current:
            if current in seen: self.warnings.append(f"class hierarchy cycle at {current:#x}"); return None
            seen.add(current); info = self.classes.get(current)
            if info is None: self.warnings.append(f"unknown class layout {current:#x}"); return None
            chain.append(info); current = info.super_id
        fields = [(info.ident, name, typ) for info in chain for name, typ in info.fields]
        self._layouts[class_id] = fields; return fields

    def reverse_pass(self, targets: set[int], max_edges: int) -> tuple[dict[int, list[dict]], set[int]]:
        incoming: dict[int, list[dict]] = defaultdict(list); owners: set[int] = set()
        edge_count = 0
        def add(target: int, edge: dict):
            nonlocal edge_count
            if target not in targets or edge_count >= max_edges: return
            incoming[target].append(edge)
            edge_count += 1
            if isinstance(edge.get("ownerId"), int): owners.add(edge["ownerId"])
        def visit(sub: int, at: int, nxt: int):
            if sub == CLASS_DUMP:
                info = self._class_dump(at, nxt, statics=True)
                for name, typ, value in info.static_fields:
                    if typ == 2: add(value, {"ownerKind": "classStatic", "ownerId": info.ident,
                                              "ownerClass": self.class_name(info.ident), "field": self.text(name)})
            elif sub == INSTANCE_DUMP:
                ident, class_id = self.oid(at), self.oid(at + self.ids + 4)
                data = at + self.ids + 4 + self.ids + 4; length = self.u4(data - 4); layout = self.layout(class_id)
                if layout is None: return
                offset = 0
                for defining_class, name, typ in layout:
                    size = self.value_size(typ)
                    if offset + size > length:
                        self.warnings.append(f"instance layout exceeds dump bytes for {class_id:#x}"); return
                    if typ == 2: add(self.oid(data + offset), {"ownerKind": "instance", "ownerId": ident,
                                                               "ownerClass": self.class_name(defining_class), "field": self.text(name)})
                    offset += size
            elif sub == OBJECT_ARRAY:
                ident, count = self.oid(at), self.u4(at + self.ids + 4)
                values = at + self.ids + 4 + 4 + self.ids
                for index in range(count): add(self.oid(values + index * self.ids), {"ownerKind": "objectArray", "ownerId": ident, "arrayIndex": index})
        for tag, body, end in self.records():
            if tag in (HEAP_DUMP, HEAP_DUMP_SEGMENT): self._heap(body, end, visit)
        return incoming, owners

    def aggregate_owners(self, tracked: dict[int, Array], max_pairs: int) -> dict:
        """Aggregate field/class owners for tracked arrays without retaining an object graph."""
        groups: dict[str, dict[str, int]] = defaultdict(lambda: {"references": 0, "uniqueArrays": 0, "uniquePayloadBytes": 0})
        pairs: set[tuple[str, int]] = set()
        first_group: dict[int, str] = {}
        shared: set[int] = set()
        limited = False
        def add(group: str, target: int):
            nonlocal limited
            array = tracked.get(target)
            if array is None: return
            groups[group]["references"] += 1
            previous = first_group.setdefault(target, group)
            if previous != group: shared.add(target)
            pair = (group, target)
            if pair in pairs: return
            if len(pairs) >= max_pairs:
                limited = True; return
            pairs.add(pair)
            groups[group]["uniqueArrays"] += 1
            groups[group]["uniquePayloadBytes"] += array.bytes
        def visit(sub: int, at: int, nxt: int):
            if sub == CLASS_DUMP:
                info = self._class_dump(at, nxt, statics=True)
                for name, typ, value in info.static_fields:
                    if typ == 2: add(f"static {self.class_name(info.ident)}.{self.text(name)}", value)
            elif sub == INSTANCE_DUMP:
                class_id = self.oid(at + self.ids + 4)
                data = at + self.ids + 4 + self.ids + 4; length = self.u4(data - 4); layout = self.layout(class_id)
                if layout is None: return
                offset = 0
                for defining_class, name, typ in layout:
                    size = self.value_size(typ)
                    if offset + size > length: return
                    if typ == 2: add(f"field {self.class_name(defining_class)}.{self.text(name)}", self.oid(data + offset))
                    offset += size
            elif sub == OBJECT_ARRAY:
                count, class_id = self.u4(at + self.ids + 4), self.oid(at + self.ids + 4 + 4)
                values = at + self.ids + 4 + 4 + self.ids; group = f"array {self.class_name(class_id)}[]"
                for index in range(count): add(group, self.oid(values + index * self.ids))
        for tag, body, end in self.records():
            if tag in (HEAP_DUMP, HEAP_DUMP_SEGMENT): self._heap(body, end, visit)
        rows = [{"owner": name, **values} for name, values in groups.items()]
        rows.sort(key=lambda row: row["uniquePayloadBytes"], reverse=True)
        return {"trackedArrays": len(tracked), "ownerGroups": rows, "sharedArrayCount": len(shared),
                "pairLimit": max_pairs, "pairLimitReached": limited,
                "note": "Group payload totals are unique within each owner group. Shared arrays can overlap across groups."}


def analyze(path: Path, *, top: int = 1000, min_bytes: int = 256 * 1024, depth: int = 1,
            max_owners: int = 50_000, max_edges: int = 100_000, max_strings: int = 1_000_000,
            max_string_bytes: int = 4096, max_classes: int = 250_000, min_array: int = 4096,
            max_tracked: int = 1_000_000, aggregate_owners: bool = False, max_owner_pairs: int = 2_000_000) -> dict:
    h = Hprof(path, max_strings, max_string_bytes, max_classes)
    try:
        with tempfile.TemporaryFile() as roots:
            arrays, tracked, aggregate = h.metadata_pass(top, min_bytes, min_array, max_tracked, roots)
            initial = {a.ident for a in arrays}; roots.seek(0); direct_roots = defaultdict(list)
            while raw := roots.read(9):
                ident, tag = struct.unpack(">QB", raw)
                if ident in initial: direct_roots[ident].append(ROOT_TAGS[tag])
            frontier, all_edges, remaining = set(initial), defaultdict(list), max_owners
            for _ in range(depth):
                if not frontier or remaining <= 0: break
                incoming, owners = h.reverse_pass(frontier, max_edges - sum(map(len, all_edges.values())))
                for target, edges in incoming.items(): all_edges[target].extend(edges)
                next_frontier = owners - frontier
                if len(next_frontier) > remaining: next_frontier = set(sorted(next_frontier)[:remaining]); h.warnings.append("reverse-owner frontier truncated")
                remaining -= len(next_frontier); frontier = next_frontier
            rows = [{"id": f"0x{a.ident:x}", "primitiveType": TYPE_NAMES.get(a.kind, str(a.kind)), "length": a.length,
                     "payloadBytes": a.bytes, "payloadOffset": a.payload_offset, "payloadBytesInFile": a.payload_bytes_in_file,
                     "directRoots": direct_roots.get(a.ident, []), "incoming": all_edges.get(a.ident, [])}
                    for a in arrays]
            report = {"format": h.version, "path": str(path), "arraySelection": {"minimumPayloadBytes": min_bytes, "top": top},
                    "reverseSearch": {"depth": depth, "maxOwners": max_owners, "maxEdges": max_edges},
                    "primitiveArrayAggregates": aggregate, "largePrimitiveArrays": rows,
                    "warnings": h.warnings + ["Incoming references are candidates for ownership; this tool does not calculate retained size."]}
            if aggregate_owners:
                report["ownerAggregate"] = h.aggregate_owners(tracked, max_owner_pairs)
                report["trackedArraySelection"] = {"minimumPayloadBytes": min_array, "maxTracked": max_tracked}
            return report
    finally: h.close()


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    p.add_argument("hprof", type=Path); p.add_argument("--json", type=Path, help="write JSON report here (stdout otherwise)")
    p.add_argument("--top", type=int, default=1000); p.add_argument("--min-bytes", type=int, default=256 * 1024)
    p.add_argument("--depth", type=int, default=1); p.add_argument("--max-owners", type=int, default=50_000); p.add_argument("--max-edges", type=int, default=100_000)
    p.add_argument("--aggregate-owners", action="store_true", help="aggregate all tracked primitive-array references by owner class and field")
    p.add_argument("--min-array", type=int, default=4096, help="minimum primitive payload included in owner aggregation")
    p.add_argument("--max-tracked", type=int, default=1_000_000, help="maximum primitive arrays tracked for aggregation")
    p.add_argument("--max-owner-pairs", type=int, default=2_000_000, help="maximum unique owner-group/array pairs")
    args = p.parse_args()
    if args.top < 1 or args.depth < 1 or args.min_bytes < 0: p.error("top/depth must be positive and min-bytes non-negative")
    report = analyze(args.hprof, top=args.top, min_bytes=args.min_bytes, depth=args.depth, max_owners=args.max_owners,
                     max_edges=args.max_edges, min_array=args.min_array, max_tracked=args.max_tracked,
                     aggregate_owners=args.aggregate_owners, max_owner_pairs=args.max_owner_pairs)
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(text + "\n", encoding="utf-8")
    else: print(text)
    return 0


if __name__ == "__main__": raise SystemExit(main())
