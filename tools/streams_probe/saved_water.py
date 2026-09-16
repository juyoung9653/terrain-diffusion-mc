"""Read actual saved Minecraft water blocks; never generate or modify chunks."""
from __future__ import annotations
import functools
import gzip
import math
from pathlib import Path
import runpy
import struct
import zlib

_nbt = runpy.run_path(str(Path(__file__).parents[1] / "river_planner/prepare-playtest.py"))["nbt_fields"]


class SavedWater:
    def __init__(self, world: Path):
        self.world = world
        self.missing: set[tuple[int, int]] = set()
        self.statuses: dict[tuple[int, int], str] = {}

    @functools.lru_cache(maxsize=64)
    def region(self, rx: int, rz: int) -> bytes:
        path = self.world / "region" / f"r.{rx}.{rz}.mca"
        return path.read_bytes() if path.exists() else b""

    @functools.lru_cache(maxsize=4096)
    def chunk(self, cx: int, cz: int) -> dict:
        data = self.region(cx >> 5, cz >> 5)
        i = ((cz & 31) * 32 + (cx & 31)) * 4
        offset = int.from_bytes(data[i:i+3], "big") * 4096
        if not offset:
            self.missing.add((cx, cz))
            return {}
        size = int.from_bytes(data[offset:offset+4], "big")
        compression = data[offset+4]
        payload = data[offset+5:offset+4+size]
        if compression == 2: raw = zlib.decompress(payload)
        elif compression == 1: raw = gzip.decompress(payload)
        elif compression == 3: raw = payload
        else: raise ValueError(f"Unsupported Anvil compression {compression}; {cx},{cz}")
        fields = _nbt(raw)
        status = fields.get(("Status",), (0,0,0,""))[3]
        self.statuses[(cx, cz)] = status
        if status != "minecraft:full":
            self.missing.add((cx, cz))
            return {}
        sections = {}
        for key, value in fields.items():
            if len(key) != 3 or key[0] != "sections" or key[2] != "Y": continue
            prefix = key[:2] + ("block_states",)
            names = {int(k[-2]): v[3] for k,v in fields.items()
                     if k[:3] == prefix and len(k) == 6 and k[-1] == "Name"}
            if not names: continue
            wet = {i for i,name in names.items() if name in {
                "minecraft:water", "minecraft:seagrass", "minecraft:tall_seagrass",
                "minecraft:kelp", "minecraft:kelp_plant"}}
            for k,v in fields.items():
                if k[:3] == prefix and len(k) == 7 and k[-2:] == ("Properties","waterlogged") and v[3] == "true":
                    wet.add(int(k[4]))
            bits = max(4, (len(names)-1).bit_length())
            packed = fields.get(prefix + ("data",))
            words = struct.unpack(">" + str((packed[2]-packed[1]-4)//8) + "Q", raw[packed[1]+4:packed[2]]) if packed else ()
            sections[value[3]] = (wet, bits, words)
        return sections

    def water(self, position: tuple[int, int, int]) -> bool:
        x,y,z = position
        section = self.chunk(x >> 4, z >> 4).get(y >> 4)
        if section is None: return False
        wet,bits,words = section
        if not words: return 0 in wet
        per = 64 // bits
        i = (y & 15)*256 + (z & 15)*16 + (x & 15)
        return ((words[i // per] >> ((i % per)*bits)) & ((1 << bits)-1)) in wet

    def near_surface(self, x: float, grade: float, z: float, radius: int = 2):
        """Nearest actual water column near a plan; absence is not invented water."""
        candidates = []
        for dz in range(-radius, radius+1):
            for dx in range(-radius, radius+1):
                bx,bz = math.floor(x)+dx, math.floor(z)+dz
                for y in range(math.ceil(grade)+2, math.floor(grade)-5, -1):
                    if self.water((bx,y,bz)):
                        candidates.append(((bx+.5-x)**2+(bz+.5-z)**2, -y, (bx,y,bz)))
                        break
        return min(candidates)[2] if candidates else None

    def cross_section(self, center: tuple[int,int,int], tangent_x: float, tangent_z: float, limit: int = 80):
        """Half-block sampled contiguous surface-water span perpendicular to a reach.

        This is a raster transect, not bankfull width. Capped spans are reported.
        """
        norm = math.hypot(tangent_x, tangent_z)
        if norm == 0 or not self.water(center): return None
        nx,nz = -tangent_z/norm, tangent_x/norm
        x,y,z = center
        spans = []
        for direction in (-1,1):
            distance = .5
            while distance <= limit and self.water((math.floor(x+.5+nx*distance*direction),y,math.floor(z+.5+nz*distance*direction))):
                distance += .5
            spans.append(distance-.25)
        return {"surfaceWidth": sum(spans), "capped": any(s >= limit for s in spans)}
