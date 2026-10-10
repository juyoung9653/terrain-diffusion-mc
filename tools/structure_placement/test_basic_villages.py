"""Offline graph, NBT integrity and source-provenance checks for original villages."""
import gzip
import hashlib
import json
import os
import struct
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest import mock
from tools.structure_placement import generate_basic_villages as generator

from tools.structure_placement.generate_basic_villages import (
    KINDS, RESOURCES, ROOT, SOURCE, TARGET, TAG_PATH, SET_PATH, build_assets,
    generate, resource_path, rewrite_json, template_locations, transform_nbt,
)
from tools.structure_placement.inspect_template import NbtReader


def unalias(value):
    return SOURCE + value[len(TARGET):] if value.startswith(TARGET) else value


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from strings(item)


class BasicVillageAssetsTest(unittest.TestCase):
    def test_numeric_lists_preserve_bits_and_reject_bad_lengths(self):
        def numeric_list(kind, count, payload):
            return b"\x0a\x00\x00\x09\x00\x04pool" + bytes([kind]) + struct.pack(">i", count) + payload + b"\x00"
        for kind, width in ((1, 1), (2, 2), (3, 4), (4, 8), (5, 4), (6, 8)):
            with self.subTest(kind=kind):
                # Includes arbitrary floating-point bit patterns; no decoding/re-encoding.
                raw = numeric_list(kind, 3, bytes(range(width * 3)))
                self.assertEqual(transform_nbt(raw), (raw, set()))
                empty = numeric_list(kind, 0, b"")
                self.assertEqual(transform_nbt(empty), (empty, set()))
                with self.assertRaisesRegex(ValueError, "Truncated"):
                    transform_nbt(numeric_list(kind, 3, bytes(width * 3 - 2)))
                with self.assertRaisesRegex(ValueError, "Invalid NBT list"):
                    transform_nbt(numeric_list(kind, -1, b""))

    def test_generation_preserves_unchanged_files_and_repairs_changed_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar, output = root / "fixture.jar", root / "out"
            with zipfile.ZipFile(jar, "w"):
                pass
            pinned = hashlib.sha1(jar.read_bytes()).hexdigest()
            with mock.patch.object(generator, "CLIENT_SHA1", pinned), mock.patch.object(
                    generator, "build_assets", return_value={"nested/asset.nbt": b"expected"}):
                generate(jar, output)
                asset = output / "nested/asset.nbt"
                stamp = 1_600_000_000_000_000_000
                os.utime(asset, ns=(stamp, stamp))
                generate(jar, output)
                self.assertEqual(asset.stat().st_mtime_ns, stamp)
                asset.write_bytes(b"changed")
                with self.assertRaisesRegex(ValueError, "Generated assets differ"):
                    generate(jar, output, check=True)
                self.assertEqual(asset.read_bytes(), b"changed")
                generate(jar, output)
                self.assertEqual(asset.read_bytes(), b"expected")

    def test_all_five_graphs_resolve_without_original_pool_backlinks(self):
        all_pools, all_templates = set(), set()
        for kind in KINDS:
            structure = json.loads((RESOURCES / f"data/terrain-diffusion-mc/worldgen/structure/basic_village_{kind}.json").read_text())
            self.assertEqual(structure["biomes"], f"#terrain-diffusion-mc:has_structure/basic_village_{kind}")
            biome_tag = json.loads((RESOURCES / resource_path("tags/worldgen/biome", structure["biomes"][1:])).read_text())
            self.assertIn(f"#minecraft:has_structure/village_{kind}", biome_tag["values"])
            self.assertEqual(structure["start_pool"], TARGET + kind + "/town_centers")
            self.assertEqual(structure["type"], "minecraft:jigsaw")
            pending, seen = [structure["start_pool"]], set()
            templates = set()
            while pending:
                identifier = pending.pop()
                if identifier == "minecraft:empty" or identifier in seen:
                    continue
                self.assertTrue(identifier.startswith(TARGET), identifier)
                seen.add(identifier)
                pool = json.loads((RESOURCES / resource_path("worldgen/template_pool", identifier)).read_text())
                self.assertFalse(any(s.startswith(SOURCE) for s in strings(pool)))
                pending.append(pool["fallback"])
                for location in template_locations(pool):
                    self.assertTrue(location.startswith(TARGET), location)
                    if location in templates:
                        continue
                    templates.add(location)
                    raw = gzip.decompress((RESOURCES / resource_path("structure", location, "nbt")).read_bytes())
                    # An independent parser checks the emitted tag encoding and
                    # palette/block/entity structure accepted by the game.
                    nbt = NbtReader(raw).root()
                    self.assertEqual(len(nbt["size"]), 3)
                    self.assertIn("blocks", nbt)
                    unchanged, pools = transform_nbt(raw)
                    self.assertEqual(unchanged, raw)
                    self.assertFalse(any(s.startswith(SOURCE) for s in strings(nbt)))
                    pending.extend(pools)
            self.assertGreater(len(templates), 50)
            all_pools.update(seen)
            all_templates.update(templates)
        self.assertEqual(len(all_pools), 61)
        self.assertEqual(len(all_templates), 478)
        actual_pools = set((RESOURCES / "data/terrain-diffusion-mc/worldgen/template_pool/basic_village").rglob("*.json"))
        actual_templates = set((RESOURCES / "data/terrain-diffusion-mc/structure/basic_village").rglob("*.nbt"))
        self.assertEqual(actual_pools, {RESOURCES / resource_path("worldgen/template_pool", p) for p in all_pools})
        self.assertEqual(actual_templates, {RESOURCES / resource_path("structure", p, "nbt") for p in all_templates})

    def test_village_tag_appends_all_aliases(self):
        tag = json.loads((RESOURCES / TAG_PATH).read_text())
        self.assertFalse(tag["replace"])
        self.assertEqual(set(tag["values"]), {f"terrain-diffusion-mc:basic_village_{kind}" for kind in KINDS})

    def test_structure_set_exposes_aliases_with_native_placement_defaults(self):
        structure_set = json.loads((RESOURCES / SET_PATH).read_text())
        self.assertEqual(structure_set["placement"], {
            "type": "minecraft:random_spread", "salt": 10387312,
            "separation": 8, "spacing": 34,
        })
        self.assertEqual(structure_set["structures"], [
            {"structure": f"terrain-diffusion-mc:basic_village_{kind}", "weight": 1}
            for kind in KINDS
        ])

    def test_truncated_or_invalid_nbt_fails_explicitly(self):
        for raw in (b"", b"\x0a\x00\x00", b"\x08\x00\x00", b"\x0a\x00\x00\x00extra"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                transform_nbt(raw)

    def test_modified_source_jar_is_rejected_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / "modified.jar"
            jar.write_bytes(b"replacement resources must not become originals")
            with self.assertRaisesRegex(ValueError, "unmodified official"):
                generate(jar, root / "output")
            self.assertFalse((root / "output").exists())


class VanillaSourceIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.jar = Path(os.environ.get("MINECRAFT_1_21_1_CLIENT_JAR", ROOT / ".gradle-user-home/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar"))
        if not cls.jar.is_file():
            raise unittest.SkipTest("Set MINECRAFT_1_21_1_CLIENT_JAR for official-source integration checks")

    def test_checked_in_assets_are_reproducible_from_official_source(self):
        assets = generate(self.jar, RESOURCES, check=True)
        self.assertEqual(len(assets), 551)
        with zipfile.ZipFile(self.jar) as archive:
            self.assertEqual(assets, build_assets(archive))

    def test_templates_preserve_every_source_byte_except_pool_links(self):
        with zipfile.ZipFile(self.jar) as archive:
            for path in (RESOURCES / "data/terrain-diffusion-mc/structure/basic_village").rglob("*.nbt"):
                identifier = TARGET + path.relative_to(RESOURCES / "data/terrain-diffusion-mc/structure/basic_village").with_suffix("").as_posix()
                original = gzip.decompress(archive.read(resource_path("structure", unalias(identifier), "nbt")))
                recovered, _ = transform_nbt(gzip.decompress(path.read_bytes()), unalias)
                self.assertEqual(recovered, original, identifier)
            for kind in KINDS:
                original = json.loads(archive.read(f"data/minecraft/worldgen/structure/village_{kind}.json"))
                actual = json.loads((RESOURCES / f"data/terrain-diffusion-mc/worldgen/structure/basic_village_{kind}.json").read_text())
                original["biomes"] = f"#terrain-diffusion-mc:has_structure/basic_village_{kind}"
                self.assertEqual(actual, rewrite_json(original))

    def test_reachable_epic_overrides_differ_from_original_templates(self):
        epic_path = os.environ.get("EPIC_VILLAGES_JAR")
        if not epic_path:
            self.skipTest("Set EPIC_VILLAGES_JAR to compare installed Epic replacements")
        compared = 0
        with zipfile.ZipFile(epic_path) as epic:
            for entry in epic.namelist():
                if not entry.startswith("data/minecraft/structure/village/") or not entry.endswith(".nbt"):
                    continue
                destination = RESOURCES / entry.replace("data/minecraft/structure/village/", "data/terrain-diffusion-mc/structure/basic_village/", 1)
                if not destination.is_file():
                    continue
                original, _ = transform_nbt(gzip.decompress(destination.read_bytes()), unalias)
                self.assertNotEqual(original, gzip.decompress(epic.read(entry)), entry)
                compared += 1
        # Epic also ships extra templates absent from the vanilla graph.
        self.assertGreater(compared, 200)


if __name__ == "__main__":
    unittest.main()
