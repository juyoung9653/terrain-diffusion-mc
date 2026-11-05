"""Offline checks for the generated active-pack guard and 26-neighbor criterion."""
import hashlib, json, os, zipfile
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SOURCE = Path(os.environ["APPDATA"]) / "PrismLauncher/instances/1.21.1 neoforge rpg/minecraft"
MANIFEST = ROOT / "tools/leaf_occlusion/resources/assets/td_leaf_occlusion/leaf-assets.sha256"
OPTIONS = dict(line.split(':', 1) for line in (SOURCE / "options.txt").read_text(encoding="utf-8").splitlines() if ':' in line)
PACKS = [zipfile.ZipFile(SOURCE / "resourcepacks" / item[5:]) for item in json.loads(OPTIONS["resourcePacks"])
         if item.startswith("file/") and (SOURCE / "resourcepacks" / item[5:]).is_file()]

def active_bytes(path):
    for pack in reversed(PACKS):
        try: return pack.read(path)
        except KeyError: pass
    raise AssertionError("selected pack stack has no " + path)

def enclosed(states):
    return all(states.get((x, y, z), False) for x in range(-1, 2) for y in range(-1, 2) for z in range(-1, 2) if (x, y, z) != (0, 0, 0))

def main():
    entries = [line.split("\t", 1) for line in MANIFEST.read_text(encoding="utf-8").splitlines() if line]
    assert len(entries) > 21  # CTM selectors/tiles are also part of the guard.
    for asset, digest in entries:
        assert hashlib.sha256(active_bytes(asset)).hexdigest() == digest, asset
    expected_ctm = {asset.removeprefix("assets/") for asset, _ in entries if re.fullmatch(r"assets/[a-z0-9._-]+/optifine/ctm/.+\.properties", asset)}
    actual_ctm = {name.removeprefix("assets/") for pack in PACKS for name in pack.namelist() if re.fullmatch(r"assets/[a-z0-9._-]+/[a-z0-9/._-]+", name) and re.fullmatch(r"assets/[a-z0-9._-]+/optifine/ctm/.+\.properties", name)}
    # Resolve to one path per resource location, precisely mirroring resource manager replacement.
    assert expected_ctm == actual_ctm
    for leaf in ("oak", "birch", "spruce"):
        for suffix in ("", "_bright"):
            document = json.loads(active_bytes(f"assets/minecraft/models/block/{leaf}_leaves{suffix}.json"))
            for element in document["elements"]:
                assert all(-16 <= value <= 32 for value in [*element["from"], *element["to"]]), (leaf, suffix, element)
    full = {(x, y, z): True for x in range(-1, 2) for y in range(-1, 2) for z in range(-1, 2) if (x, y, z) != (0, 0, 0)}
    assert enclosed(full)
    full[(1, 1, 1)] = False
    assert not enclosed(full), "diagonal hole must prevent whole-model culling"
    print("leaf guard and 26-neighbor checks passed")

if __name__ == "__main__": main()
