import importlib.util
import struct
import sys
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("hprof_owners", Path(__file__).with_name("hprof_owners.py"))
owners = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = owners
SPEC.loader.exec_module(owners)


def synthetic(path: Path, ids: int) -> None:
    def oid(value): return value.to_bytes(ids, "big")
    def record(tag, body): return bytes([tag]) + struct.pack(">II", 0, len(body)) + body
    def string(ident, text): return record(0x01, oid(ident) + text.encode())
    def class_dump(ident, parent, fields):
        body = oid(ident) + struct.pack(">I", 0) + oid(parent) + oid(0) * 5 + struct.pack(">IHHH", 0, 0, 0, len(fields))
        return bytes([0x20]) + body + b"".join(oid(name) + bytes([typ]) for name, typ in fields)
    heap = bytearray()
    heap += class_dump(0x100, 0, [(3, 2)])
    heap += class_dump(0x110, 0x100, [(4, 2)])
    # HPROF instance values are child-class fields first, then superclass fields.
    heap += bytes([0x21]) + oid(0x200) + struct.pack(">I", 0) + oid(0x110) + struct.pack(">I", ids * 2) + oid(0x300) + oid(0x301)
    heap += bytes([0x23]) + oid(0x300) + struct.pack(">IIB", 0, 65_536, 10) + b"\0" * (65_536 * 4)
    heap += bytes([0x23]) + oid(0x301) + struct.pack(">IIB", 0, 65_536, 10) + b"\0" * (65_536 * 4)
    heap += bytes([0x22]) + oid(0x400) + struct.pack(">II", 0, 1) + oid(0x500) + oid(0x301)
    heap += bytes([0xFF]) + oid(0x300)
    raw = b"JAVA PROFILE 1.0.2\0" + struct.pack(">IQ", ids, 0)
    raw += string(1, "example/Parent") + string(2, "example/Child") + string(3, "inherited") + string(4, "buffer")
    raw += record(0x02, struct.pack(">I", 1) + oid(0x100) + struct.pack(">I", 0) + oid(1))
    raw += record(0x02, struct.pack(">I", 2) + oid(0x110) + struct.pack(">I", 0) + oid(2))
    raw += record(0x1C, heap) + record(0x2C, b"")
    path.write_bytes(raw)


class HprofOwnersTest(unittest.TestCase):
    def test_instance_hierarchy_and_aggregate_for_id_sizes(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            for ids in (4, 8):
                path = Path(directory, f"sample-{ids}.hprof"); synthetic(path, ids)
                report = owners.analyze(path, top=10, min_bytes=1024, min_array=1024, aggregate_owners=True)
                arrays = {item["id"]: item for item in report["largePrimitiveArrays"]}
                array = arrays["0x300"]
                self.assertEqual(array["primitiveType"], "int")
                self.assertEqual(array["payloadBytes"], 262_144)
                self.assertEqual(array["directRoots"], ["unknown"])
                self.assertEqual(array["incoming"][0]["ownerClass"], "example/Child")
                groups = report["ownerAggregate"]["ownerGroups"]
                names = {group["owner"] for group in groups}
                self.assertIn("field example/Child.buffer", names)
                self.assertIn("field example/Parent.inherited", names)
                self.assertIn("array <class#0x500>[]", names)


if __name__ == "__main__": unittest.main()
