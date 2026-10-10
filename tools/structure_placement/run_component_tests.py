"""Exercise production component collision/support decisions without a Minecraft process."""
from pathlib import Path
import os
import subprocess
import gzip
import runpy

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21")) / "bin"
OUT = ROOT / "build" / "component-geometry-tests"
OUT.mkdir(parents=True, exist_ok=True)
SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure"
# Read the checked-in original NBT rather than replacing sparse real foundations with synthetic boxes.
nbt_fields = runpy.run_path(str(ROOT / "tools/river_planner/prepare-playtest.py"))["nbt_fields"]
fixture = OUT / "original-contact-blocks.tsv"
rows = []
for relative in ("savanna/houses/savanna_cartographer_1.nbt", "taiga/taiga_decoration_4.nbt"):
    path = ROOT / "common/src/main/resources/data/terrain-diffusion-mc/structure/basic_village" / relative
    fields = nbt_fields(gzip.decompress(path.read_bytes()))
    for key, value in fields.items():
        if len(key) != 3 or key[0] != "blocks" or key[2] != "state":
            continue
        prefix = key[:2]
        coords = [str(fields[prefix + ("pos", str(i))][3]) for i in range(3)]
        name = fields[("palette", str(value[3]), "Name")][3]
        final_state = fields.get(prefix + ("nbt", "final_state"), (None, None, None, ""))[3]
        rows.append("\t".join([path.stem, *coords, name, final_state]))
fixture.write_text("\n".join(rows), encoding="utf-8")
subprocess.run([str(JAVA / "javac"), "-encoding", "UTF-8", "-d", str(OUT),
                str(SOURCE / "PlacementGeometry.java"), str(SOURCE / "ComponentGeometry.java"),
                str(Path(__file__).with_name("ComponentGeometryTest.java"))], check=True)
subprocess.run([str(JAVA / "java"), "-cp", str(OUT), "ComponentGeometryTest", str(fixture)], check=True)
