import gzip
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


def level_nbt(existing_showcase=None):
    source_entries = [string("type", "terrain-diffusion-mc:terrain_diffusion"), byte("terralith", 1), byte("natural_biomes", 1)]
    if existing_showcase is not None:
        source_entries.append(string("ecology_showcase", existing_showcase))
    biome_source = compound("biome_source", *source_entries)
    generator = compound("generator", biome_source)
    overworld = compound("minecraft:overworld", generator)
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

    def write_scale_state(self):
        data = self.template / "data"
        data.mkdir()
        payload = b"\x0a\x00\x00" + compound("data", integer("scale", 2), byte("explicit_scale", 1)) + b"\0"
        (data / "terrain_diffusion_world_settings.dat").write_bytes(gzip.compress(payload))

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


if __name__ == "__main__":
    unittest.main()
