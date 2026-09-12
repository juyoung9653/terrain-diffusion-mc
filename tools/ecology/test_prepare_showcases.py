import gzip
import hashlib
import json
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "ecology" / "prepare_showcases.py"
spec = importlib.util.spec_from_file_location("prepare_showcases", SCRIPT)
showcases = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(showcases)


def tag(kind, name, payload):
    return bytes((kind,)) + showcases.nbt_string(name) + payload


def byte(name, value): return tag(1, name, struct.pack(">b", value))
def integer(name, value): return tag(3, name, struct.pack(">i", value))
def long(name, value): return tag(4, name, struct.pack(">q", value))
def string(name, value): return tag(8, name, showcases.nbt_string(value))
def compound(name, *entries): return tag(10, name, b"".join(entries) + b"\0")


def level_nbt(existing_showcase=None, dimension_type="terrain-diffusion-mc:terrain_diffusion_scale_2"):
    source_entries = [string("type", "terrain-diffusion-mc:terrain_diffusion"), byte("terralith", 1), byte("natural_biomes", 1)]
    if existing_showcase is not None:
        source_entries.append(string("ecology_showcase", existing_showcase))
    biome_source = compound("biome_source", *source_entries)
    generator = compound("generator", biome_source)
    overworld = compound("minecraft:overworld", string("type", dimension_type), generator)
    dimensions = compound("dimensions", overworld)
    worldgen = compound("WorldGenSettings", long("seed", 123456789), dimensions)
    rules = compound("GameRules", string("spawnRadius", "9"), string("spawnChunkRadius", "9"),
                     string("keepInventory", "false"), string("doDaylightCycle", "true"), string("doWeatherCycle", "true"))
    data = compound("Data", string("LevelName", "template"), integer("GameType", 0), byte("allowCommands", 0),
                    integer("SpawnX", 0), integer("SpawnY", 64), integer("SpawnZ", 0), long("DayTime", 18000),
                    byte("raining", 1), byte("thundering", 1), rules, worldgen)
    return b"\x0a\x00\x00" + data + b"\0"


class PrepareShowcasesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.template = self.root / "template"
        self.template.mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def write_level(self, existing_showcase=None):
        level = self.template / "level.dat"
        level.write_bytes(gzip.compress(level_nbt(existing_showcase)))
        return level

    def write_scale_state(self, scale=2, explicit=True, omit_scale=False):
        data = self.template / "data"
        data.mkdir()
        entries = [] if omit_scale else [integer("scale", scale)]
        if explicit:
            entries.append(byte("explicit_scale", 1))
        payload = b"\x0a\x00\x00" + compound("data", *entries) + b"\0"
        (data / "terrain_diffusion_world_settings.dat").write_bytes(gzip.compress(payload))

    def fields(self, path):
        return showcases.nbt_fields(gzip.decompress(path.read_bytes()))

    def test_inserts_showcase_string_and_edits_playable_fields(self):
        seed = showcases.patch_level(self.write_level(), "temperate_forest", (9472, 110, 896))
        fields = showcases.nbt_fields(gzip.decompress((self.template / "level.dat").read_bytes()))
        source = showcases.OVERWORLD_BIOME_SOURCE
        self.assertEqual(fields[source + ("ecology_showcase",)][3], "temperate_forest")
        self.assertEqual(fields[source + ("terralith",)][3], 1)
        self.assertEqual(fields[source + ("natural_biomes",)][3], 1)
        self.assertEqual(fields[("Data", "DayTime")][3], 6000)
        self.assertEqual(fields[("Data", "raining")][3], 0)
        self.assertEqual(fields[("Data", "thundering")][3], 0)
        self.assertEqual((fields[("Data", "SpawnX")][3], fields[("Data", "SpawnY")][3], fields[("Data", "SpawnZ")][3]), (9472, 110, 896))
        self.assertEqual(seed, 123456789)

    def test_replaces_existing_showcase_string(self):
        showcases.patch_level(self.write_level("old_profile"), "desert", (1, 2, 3))
        fields = showcases.nbt_fields(gzip.decompress((self.template / "level.dat").read_bytes()))
        self.assertEqual(fields[showcases.OVERWORLD_BIOME_SOURCE + ("ecology_showcase",)][3], "desert")

    def test_rejects_unknown_profile_and_existing_destination(self):
        with self.assertRaises(ValueError):
            showcases.patch_level(self.write_level(), "not_a_profile", (1, 2, 3))
        output = self.root / "already-exists"
        output.mkdir()
        with self.assertRaises(FileExistsError):
            showcases.stage(self.template, output, (9472, 110, 896))

    def test_copies_only_td_scale_data_not_probe_ticket_state(self):
        self.write_level()
        self.write_scale_state()
        for name in ("chunks.dat", "raids.dat", "random_sequences.dat", "scoreboard.dat",
                     "neoforge_data_attachments.dat", "terrain_diffusion_river_preview.dat"):
            (self.template / "data" / name).write_bytes(b"old-probe-state")
        target = self.root / "copied"
        target.mkdir()
        showcases.copy_template(self.template, target)
        self.assertEqual({path.name for path in (target / "data").iterdir()}, {"terrain_diffusion_world_settings.dat"})

    def test_stage_scale_five_patches_legacy_default_state_and_dimension_type(self):
        self.write_level()
        self.write_scale_state(omit_scale=True, explicit=False)
        source_level_hash = hashlib.sha256((self.template / "level.dat").read_bytes()).hexdigest()
        source_scale_hash = hashlib.sha256((self.template / "data" / "terrain_diffusion_world_settings.dat").read_bytes()).hexdigest()
        output = self.root / "showcases"

        manifests = showcases.stage(self.template, output, (1, 110, 3), scale=5)

        self.assertEqual(len(manifests), len(showcases.ECOLOGY_BIOMES))
        world = output / "temperate_forest"
        level_fields = self.fields(world / "level.dat")
        saved_fields = self.fields(world / "data" / "terrain_diffusion_world_settings.dat")
        self.assertEqual(level_fields[showcases.OVERWORLD_DIMENSION_TYPE][3],
                         "terrain-diffusion-mc:terrain_diffusion_scale_5")
        self.assertEqual(saved_fields[("data", "scale")][3], 5)
        self.assertEqual(saved_fields[("data", "explicit_scale")][3], 1)
        manifest = json.loads((world / "showcase-manifest.json").read_text())
        self.assertEqual(manifest["scale"], 5)
        self.assertEqual(manifest["levelDatSha256"], hashlib.sha256((world / "level.dat").read_bytes()).hexdigest())
        self.assertEqual(hashlib.sha256((self.template / "level.dat").read_bytes()).hexdigest(), source_level_hash)
        self.assertEqual(hashlib.sha256((self.template / "data" / "terrain_diffusion_world_settings.dat").read_bytes()).hexdigest(), source_scale_hash)

    def test_default_scale_preserves_legacy_omitted_codec_state(self):
        self.write_level()
        self.write_scale_state(omit_scale=True, explicit=False)
        source_state = (self.template / "data" / "terrain_diffusion_world_settings.dat").read_bytes()

        showcases.stage(self.template, self.root / "default-showcases", (1, 2, 3))

        world = self.root / "default-showcases" / "temperate_forest"
        self.assertEqual((world / "data" / "terrain_diffusion_world_settings.dat").read_bytes(), source_state)
        self.assertEqual(self.fields(world / "level.dat")[showcases.OVERWORLD_DIMENSION_TYPE][3],
                         "terrain-diffusion-mc:terrain_diffusion_scale_2")

    def test_rejects_invalid_requested_scale(self):
        self.write_level()
        self.write_scale_state()
        for invalid in (0, 7):
            with self.assertRaises(ValueError):
                showcases.stage(self.template, self.root / f"scale-{invalid}", (1, 2, 3), scale=invalid)


if __name__ == "__main__":
    unittest.main()
