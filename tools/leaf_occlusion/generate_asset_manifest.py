"""Generate an exact effective-resource manifest for the currently selected packs."""
import hashlib, io, json, os, re, zipfile
from pathlib import Path
from PIL import Image

SOURCE = Path(os.environ["APPDATA"]) / "PrismLauncher/instances/1.21.1 neoforge rpg/minecraft"
OPTIONS = dict(line.split(":", 1) for line in (SOURCE / "options.txt").read_text(encoding="utf-8").splitlines() if ":" in line)
PACKS = [zipfile.ZipFile(SOURCE / "resourcepacks" / name[5:]) for name in json.loads(OPTIONS["resourcePacks"])
         if name.startswith("file/") and (SOURCE / "resourcepacks" / name[5:]).is_file()]
TARGETS = {leaf + suffix for leaf in ("oak_leaves", "birch_leaves", "spruce_leaves") for suffix in ("", "_bright", "_bushy", "_bushy_bright")}
BASE = {leaf + suffix for leaf in ("oak_leaves", "birch_leaves", "spruce_leaves") for suffix in ("", "_bright")}

def resource(path):
    for pack in reversed(PACKS):
        try: return pack.read(path)
        except KeyError: pass
    raise KeyError("selected active pack stack has no " + path)

def property_tiles(path, raw):
    fields = {key.lower(): value for key, value in (line.split("=", 1) for line in raw.decode("utf-8").splitlines() if "=" in line and not line.lstrip().startswith("#"))}
    matched = {tile.removeprefix("minecraft:block/") for tile in fields.get("matchtiles", "").split()}
    if not matched & TARGETS: return (), False
    files = []
    for token in fields.get("tiles", "").split():
        match = re.fullmatch(r"(\d+)-(\d+)", token)
        names = range(int(match.group(1)), int(match.group(2)) + 1) if match else (token,)
        files.extend(path.rsplit("/", 1)[0] + "/" + str(name) + ".png" for name in names)
    # Overlay CTM adds a separate exterior layer; its alpha does not replace the
    # opaque base cube which is the culling precondition.
    return files, bool(matched & BASE) and not fields.get("method", "").startswith("overlay")

assets = {}
for leaf in ("oak", "birch", "spruce"):
    blockstate = f"assets/minecraft/blockstates/{leaf}_leaves.json"
    assets[blockstate] = resource(blockstate)
    models = {entry["model"].removeprefix("block/") for entry in json.loads(assets[blockstate])["variants"][""]}
    for model in models:
        model_path = f"assets/minecraft/models/block/{model}.json"; assets[model_path] = resource(model_path)
        for texture in json.loads(assets[model_path]).get("textures", {}).values():
            if texture.startswith("block/"): assets[f"assets/minecraft/textures/{texture}.png"] = resource(f"assets/minecraft/textures/{texture}.png")

for path in sorted({name for pack in PACKS for name in pack.namelist()}):
    if not re.fullmatch(r"assets/[a-z0-9._-]+/optifine/ctm/.+\.properties", path): continue
    # ResourceManager discards malformed ZIP paths; retain only names accepted by
    # ResourceLocation so generation and runtime enumerate the same namespace.
    if not re.fullmatch(r"assets/[a-z0-9._-]+/[a-z0-9/._-]+", path): continue
    raw = resource(path); tiles, must_be_opaque = property_tiles(path, raw)
    # Hash every active CTM selector. The runtime guard compares its exact
    # resource-manager keyset, catching a later pack which introduces a new
    # selector for a targeted leaf tile.
    assets[path] = raw
    for tile in tiles:
        assets[tile] = resource(tile)
        if must_be_opaque and Image.open(io.BytesIO(assets[tile])).convert("RGBA").getchannel("A").getextrema() != (255, 255):
            raise ValueError("transparent CTM base tile disables leaf occlusion: " + tile)

output = Path(__file__).resolve().parent / "resources/assets/td_leaf_occlusion/leaf-assets.sha256"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text("".join(f"{name}\t{hashlib.sha256(raw).hexdigest()}\n" for name, raw in sorted(assets.items())), encoding="utf-8")
print(f"wrote {len(assets)} active resource hashes to {output}")
