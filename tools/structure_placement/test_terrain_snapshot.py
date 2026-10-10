"""Pure checks for the terrain replay input and seal boundary."""

from __future__ import annotations

import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prepare_fixture import _sha256
from terrain_snapshot import prepare_snapshot, seal_snapshot


class TerrainSnapshotTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.run = self.root / "build/structure-density/fresh"
        self.snapshot = self.root / "build/terrain-replay/base"
        self.source = self.root / "common/src/main/java/example/Terrain.java"
        self._write(self.source, "terrain")
        self._write(self.root / "common/src/main/resources/worldgen.json", "{}")
        self._write(self.root / "build.gradle", "plugins {}")
        inputs = {
            "mods/infinitetensor.jar": b"mod",
            "config/terrain-diffusion-mc.json": b"config",
            "config/terrain-diffusion-structures.json": b"structure one",
            "defaultconfigs/default.json": b"{}",
            "terrain-diffusion-models/model.bin": b"model",
            "server.properties": b"level-name=world\n",
            "eula.txt": b"eula=true\n",
            "density-center.txt": b"0,0\n",
            "density-radius.txt": b"8\n",
            "world/level.dat": b"world",
        }
        for name, data in inputs.items():
            self._write(self.run / name, data)
        for name in ("mods", "config", "defaultconfigs", "terrain-diffusion-models"):
            (self.run / name).mkdir(exist_ok=True)
        hashes = {name: _sha256(self.run / name) for name in inputs}
        self._write(self.run / "input-manifest.json", json.dumps({"version": 1, "sha256": hashes}))

    @staticmethod
    def _write(path: Path, data: bytes | str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data.encode() if isinstance(data, str) else data)

    def _record(self) -> str:
        fingerprint = prepare_snapshot(self.root, self.run, self.snapshot, "record")
        self._write(self.snapshot / "0_0.tile", b"opaque codec tile")
        self._write(self.snapshot / ".tiles.lock", b"")
        receipt = self.root / "receipt.json"
        self._write(receipt, json.dumps({
            "success": True, "terrainMode": "record",
            "terrainReplay": {"mode": "record", "snapshotId": fingerprint,
                              "recordedTiles": 1, "replayedTiles": 0,
                              "misses": 0, "snapshotIoSeconds": 0.1,
                              "bypassed": 0, "failure": None},
        }))
        seal_snapshot(self.snapshot, receipt)
        return fingerprint

    def test_record_seal_and_unchanged_replay(self) -> None:
        fingerprint = self._record()
        self.assertEqual(fingerprint, prepare_snapshot(self.root, self.run, self.snapshot, "replay"))
        request = json.loads((self.snapshot / "request.json").read_text())
        self.assertIn("mods/infinitetensor.jar", request["files"]["fixture"])
        self.assertNotIn("config/terrain-diffusion-structures.json", request["files"]["fixture"])

    def test_protected_source_changes_and_new_inputs_invalidate(self) -> None:
        self._record()
        for path, data in ((self.source, b"changed"),
                           (self.root / "common/src/main/resources/added.json", b"new"),
                           (self.root / "neoforge/src/generated/resources/biome.json", b"new"),
                           (self.root / "common/src/client/resources/biome.json", b"new"),
                           (self.root / "buildSrc/src/main/groovy/plugin.gradle", b"new"),
                           (self.root / "libs/runtime.jar", b"new"),
                           (self.root / "tools/structure_placement/run_suite.py", b"new"),
                           (self.root / "build.gradle", None)):
            before = path.read_bytes() if path.exists() else None
            if data is None:
                path.unlink()
            else:
                self._write(path, data)
            with self.assertRaises(ValueError):
                prepare_snapshot(self.root, self.run, self.snapshot, "replay")
            if before is None:
                path.unlink()
            else:
                self._write(path, before)

    def test_structure_source_change_is_explicitly_excluded(self) -> None:
        self._record()
        structure = self.root / ("common/src/main/java/com/github/xandergos/"
                                 "terraindiffusionmc/world/structure/NewPlacement.java")
        self._write(structure, "changed geometry")
        self.assertEqual(json.loads((self.snapshot / "request.json").read_text())["fingerprint"],
                         prepare_snapshot(self.root, self.run, self.snapshot, "replay"))

    def test_fixture_change_and_extra_file_fail(self) -> None:
        self._record()
        config = self.run / "config/terrain-diffusion-mc.json"
        config.write_text("corrupt")
        with self.assertRaises(ValueError):
            prepare_snapshot(self.root, self.run, self.snapshot, "replay")
        config.write_bytes(b"config")
        self._write(self.run / "mods/new.jar", b"new mod")
        with self.assertRaises(ValueError):
            prepare_snapshot(self.root, self.run, self.snapshot, "replay")

    def test_missing_seal_tamper_extra_and_id_mismatch_fail(self) -> None:
        fingerprint = prepare_snapshot(self.root, self.run, self.snapshot, "record")
        self._write(self.snapshot / "0_0.tile", b"tile")
        with self.assertRaises(ValueError):
            prepare_snapshot(self.root, self.run, self.snapshot, "replay")
        self._write(self.root / "receipt.json", json.dumps({
            "success": True, "terrainMode": "record",
            "terrainReplay": {"mode": "record", "snapshotId": "wrong", "recordedTiles": 1,
                              "replayedTiles": 0, "misses": 0, "snapshotIoSeconds": 0,
                              "bypassed": 0},
        }))
        with self.assertRaises(ValueError):
            seal_snapshot(self.snapshot, self.root / "receipt.json")
        receipt = json.loads((self.root / "receipt.json").read_text())
        receipt["terrainReplay"]["snapshotId"] = fingerprint
        self._write(self.root / "receipt.json", json.dumps(receipt))
        seal_snapshot(self.snapshot, self.root / "receipt.json")
        tile = self.snapshot / "0_0.tile"
        tile.write_bytes(b"tampered")
        with self.assertRaises(ValueError):
            prepare_snapshot(self.root, self.run, self.snapshot, "replay")
        tile.write_bytes(b"tile")
        self._write(self.snapshot / "1_0.tile", b"extra")
        with self.assertRaises(ValueError):
            prepare_snapshot(self.root, self.run, self.snapshot, "replay")

    def test_generated_world_is_refused(self) -> None:
        self._record()
        self._write(self.run / "world/region/r.0.0.mca", b"generated")
        with self.assertRaises(ValueError):
            prepare_snapshot(self.root, self.run, self.snapshot, "replay")

    def test_linked_tile_is_refused(self) -> None:
        self._record()
        link = self.snapshot / "linked.tile"
        try:
            link.symlink_to(self.snapshot / "0_0.tile")
        except (OSError, NotImplementedError):
            self.skipTest("symlinks unavailable")
        with self.assertRaises(ValueError):
            prepare_snapshot(self.root, self.run, self.snapshot, "replay")

    def test_hardlinked_tile_is_refused(self) -> None:
        self._record()
        other = self.root / "other.tile"
        try:
            os.link(self.snapshot / "0_0.tile", other)
        except OSError:
            self.skipTest("hardlinks unavailable")
        with self.assertRaises(ValueError):
            prepare_snapshot(self.root, self.run, self.snapshot, "replay")


if __name__ == "__main__":
    unittest.main()
