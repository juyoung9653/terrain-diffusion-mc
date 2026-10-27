"""Pure fixture preparation checks; no Minecraft or Gradle process is started."""

from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

MODULE = Path(__file__).with_name("prepare_fixture.py")
SPEC = importlib.util.spec_from_file_location("prepare_fixture", MODULE)
assert SPEC and SPEC.loader
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)


class PrepareFixtureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "build" / "dedicated-source"
        self.target = self.root / "build" / "structure-density" / "trial"
        for name in fixture.DIRECTORIES:
            (self.source / name).mkdir(parents=True)
        self.write("server.properties", b"level-name=world\n")
        self.write("eula.txt", b"eula=true\n")
        self.write("village-census-only.txt", b"")
        self.write("require-ecology-scale1.txt", b"")
        self.write("mods/example.jar", b"jar-A")
        self.write("config/example.toml", b"config-A")
        self.write("terrain-diffusion-models/model.onnx", b"model-A")
        self.write("world/level.dat", b"level-A")
        self.write("world/streamsreflowing/cache.bin", b"river-A")

    def write(self, name: str, data: bytes) -> None:
        path = self.source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def prepare(self, **kwargs: object) -> dict[str, object]:
        return fixture.prepare(self.source, self.target, root=self.root, **kwargs)

    def test_copy_manifest_and_noop_preserves_mtimes(self) -> None:
        first = self.prepare()
        self.assertEqual(first["copiedFiles"], first["scannedFiles"])
        manifest = json.loads((self.target / fixture.MANIFEST).read_text())
        self.assertEqual(set(manifest["sha256"]),
                         {"server.properties", "eula.txt", "village-census-only.txt",
                          "require-ecology-scale1.txt", "mods/example.jar",
                          "config/example.toml", "terrain-diffusion-models/model.onnx",
                          "world/level.dat", "world/streamsreflowing/cache.bin",
                          "density-center.txt", "density-radius.txt"})
        self.assertEqual((self.target / "density-center.txt").read_text(), "0,0\n")
        self.assertEqual((self.target / "density-radius.txt").read_text(), "96\n")
        self.assertEqual((self.target / "world/streamsreflowing/cache.bin").read_bytes(), b"river-A")
        self.assertFalse((self.target / "world/region").exists())
        self.assertTrue((self.target / "defaultconfigs").is_dir())
        self.assertEqual(set(fixture._source_files(self.target)),
                         set(manifest["sha256"]) - {"density-center.txt", "density-radius.txt"})
        timestamps = {name: (self.target / name).stat().st_mtime_ns
                      for name in [*manifest["sha256"], fixture.MANIFEST]}
        second = self.prepare()
        self.assertEqual(second["copiedFiles"], 0)
        self.assertEqual(second["reusedFiles"], second["scannedFiles"])
        self.assertEqual(timestamps, {name: (self.target / name).stat().st_mtime_ns
                                      for name in timestamps})

    def test_same_size_corruption_is_repaired(self) -> None:
        self.prepare()
        target = self.target / "mods/example.jar"
        target.write_bytes(b"jar-B")
        result = self.prepare()
        self.assertEqual(result["copiedFiles"], 1)
        self.assertEqual(target.read_bytes(), b"jar-A")

    def test_streams_family_preserves_marine_admission(self) -> None:
        marker = "world/data/terrain_diffusion_hybrid_aquatic.properties"
        self.write(marker, b"enabled=true\nprofileVersion=1\nseed=12345\n")
        self.target = self.root / "build" / "streams-probe" / "trial"
        self.prepare(family_name="streams-probe")
        self.assertEqual((self.target / marker).read_bytes(), (self.source / marker).read_bytes())
        manifest = json.loads((self.target / fixture.MANIFEST).read_text())
        self.assertIn(marker, manifest["sha256"])
        self.assertFalse((self.target / "density-center.txt").exists())
        self.assertFalse((self.target / "world/region").exists())

    def test_world_identity_is_required(self) -> None:
        (self.source / "world/level.dat").unlink()
        with self.assertRaisesRegex(ValueError, "level.dat"):
            self.prepare()
        self.assertFalse(self.target.exists())
        self.write("world/level.dat", b"level-A")
        for properties in [b"level-name=elsewhere\n", b"", b"level-name=world\nlevel-name=world\n",
                           b"level-name=world\nlevel\\-name=elsewhere\n"]:
            self.write("server.properties", properties)
            with self.assertRaises(ValueError):
                self.prepare()
            self.assertFalse(self.target.exists())

    def test_optional_controls_preserved_without_forcing_village_mode(self) -> None:
        (self.source / "village-census-only.txt").unlink()
        self.write("reverse-order.txt", b"")
        self.write("verify-support.txt", b"")
        self.prepare()
        self.assertFalse((self.target / "village-census-only.txt").exists())
        manifest = json.loads((self.target / fixture.MANIFEST).read_text())
        self.assertIn("reverse-order.txt", manifest["sha256"])
        self.assertIn("verify-support.txt", manifest["sha256"])

    def test_invalid_source_controls_fail_before_writing(self) -> None:
        self.write("density-radius.txt", b"-1")
        with self.assertRaisesRegex(ValueError, "radius"):
            self.prepare()
        self.assertFalse(self.target.exists())

    def test_absent_river_cache_is_recorded_as_fresh(self) -> None:
        (self.source / "world/streamsreflowing/cache.bin").unlink()
        result = self.prepare()
        self.assertGreater(result["scannedFiles"], 0)
        manifest = json.loads((self.target / fixture.MANIFEST).read_text())
        self.assertEqual(manifest["cacheMode"], "fresh river cache; fresh chunks")

    def test_removed_source_input_refuses_stale_target(self) -> None:
        self.prepare()
        (self.source / "mods/example.jar").unlink()
        with self.assertRaisesRegex(ValueError, "unexpected or generated target file"):
            self.prepare()
        self.assertTrue((self.target / "mods/example.jar").is_file())

    def test_interrupted_copy_can_be_retried(self) -> None:
        original = fixture._atomic_copy
        calls = 0

        def interrupt_after_one(source: Path, target: Path, expected: str) -> None:
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError("simulated copy interruption")
            original(source, target, expected)

        with mock.patch.object(fixture, "_atomic_copy", side_effect=interrupt_after_one):
            with self.assertRaisesRegex(OSError, "simulated"):
                self.prepare()
        self.assertFalse((self.target / fixture.MANIFEST).exists())
        result = self.prepare()
        self.assertGreater(result["copiedFiles"], 0)
        self.assertGreater(result["reusedFiles"], 0)

    def test_copy_failure_leaves_no_partial_destination(self) -> None:
        with mock.patch.object(fixture.shutil, "copy2", side_effect=OSError("copy failed")):
            with self.assertRaisesRegex(OSError, "copy failed"):
                self.prepare()
        self.assertEqual(list(self.target.rglob(".prepare-fixture-*.tmp")), [])
        result = self.prepare()
        self.assertEqual(result["copiedFiles"], result["scannedFiles"])

    def test_generated_or_unknown_target_is_refused(self) -> None:
        self.prepare()
        generated = self.target / "world/region/r.0.0.mca"
        generated.parent.mkdir()
        generated.write_bytes(b"generated")
        with self.assertRaisesRegex(ValueError, "unexpected or generated target directory"):
            self.prepare()
        self.assertEqual(generated.read_bytes(), b"generated")

    def test_overlap_and_linked_paths_are_refused(self) -> None:
        with self.assertRaisesRegex(ValueError, "overlap"):
            fixture.prepare(self.target, self.target, root=self.root)
        with mock.patch.object(fixture, "is_link", side_effect=lambda path: path == self.source / "mods"):
            with self.assertRaisesRegex(ValueError, "linked source directory"):
                self.prepare()
        linked = self.source / "config/linked.toml"
        try:
            os.symlink(self.source / "config/example.toml", linked)
        except (OSError, NotImplementedError):
            self.skipTest("symlinks unavailable on this host")
        with self.assertRaisesRegex(ValueError, "linked fixture path"):
            self.prepare()

    def test_overrides_and_server_properties_are_in_manifest(self) -> None:
        self.prepare(center="1,-2", radius=12)
        self.assertEqual((self.target / "density-center.txt").read_text(), "1,-2\n")
        self.assertEqual((self.target / "density-radius.txt").read_text(), "12\n")
        manifest = json.loads((self.target / fixture.MANIFEST).read_text())
        self.assertEqual(manifest["sha256"]["density-center.txt"],
                         fixture._sha256(self.target / "density-center.txt"))
        self.assertEqual(manifest["sha256"]["server.properties"],
                         fixture._sha256(self.target / "server.properties"))

    def test_existing_hardlink_is_refused(self) -> None:
        self.prepare()
        target = self.target / "mods/example.jar"
        target.unlink()
        try:
            os.link(self.source / "mods/example.jar", target)
        except OSError:
            self.skipTest("hardlinks unavailable on this host")
        with self.assertRaisesRegex(ValueError, "hardlinked target file"):
            self.prepare()


if __name__ == "__main__":
    unittest.main()
