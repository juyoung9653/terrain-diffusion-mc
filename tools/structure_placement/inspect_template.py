"""Read-only NBT template footprint inspection: python inspect_template.py mod.jar entry.nbt."""
import collections
import gzip
import io
import struct
import sys
import zipfile


class NbtReader:
    def __init__(self, data):
        self.stream = io.BytesIO(data)

    def number(self, fmt):
        return struct.unpack(">" + fmt, self.stream.read(struct.calcsize(fmt)))[0]

    def string(self):
        return self.stream.read(self.number("H")).decode("utf-8")

    def payload(self, kind):
        if kind in (1, 2, 3, 4, 5, 6):
            return self.number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[kind])
        if kind == 8:
            return self.string()
        if kind == 9:
            child, size = self.number("B"), self.number("i")
            return [self.payload(child) for _ in range(size)]
        if kind == 10:
            result = {}
            while (child := self.number("B")):
                name = self.string()
                result[name] = self.payload(child)
            return result
        if kind in (7, 11, 12):
            return [self.number({7: "b", 11: "i", 12: "q"}[kind]) for _ in range(self.number("i"))]
        raise ValueError(f"Unknown NBT tag {kind}")

    def root(self):
        kind = self.number("B")
        self.string()
        return self.payload(kind)


def main():
    with zipfile.ZipFile(sys.argv[1]) as archive:
        template = NbtReader(gzip.decompress(archive.read(sys.argv[2]))).root()
    palettes = template.get("palettes", [template.get("palette", [])])
    ignored = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:jigsaw",
               "minecraft:structure_block", "minecraft:structure_void"}
    occupied = collections.defaultdict(set)
    for palette in palettes:
        for block in template["blocks"]:
            if palette[block["state"]]["Name"] not in ignored:
                x, y, z = block["pos"]
                occupied[y].add((x, z))
    print("size", template["size"], "entities", len(template.get("entities", [])))
    for y, columns in sorted(occupied.items())[:10]:
        xs, zs = zip(*columns)
        print("y", y, "occupied columns", len(columns), "rectangle", (min(xs), min(zs), max(xs), max(zs)))


if __name__ == "__main__":
    main()
