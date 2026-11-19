from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("biome_preview", ROOT / "tools/biome_iteration/preview.py")
PREVIEW = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREVIEW)


class PreviewTest(unittest.TestCase):
    def scratch(self):
        directory = ROOT / ".tmp"
        directory.mkdir(exist_ok=True)
        return tempfile.TemporaryDirectory(dir=directory)

    def test_writes_non_release_material_map(self):
        with self.scratch() as tmp:
            root = Path(tmp); columns = root / "columns.json"
            columns.write_text(json.dumps({"columns": [
                {"x": 1, "z": 2, "bedBlock": "minecraft:mud"},
                {"x": 2, "z": 2, "bedBlock": "minecraft:sand"},
            ]}), encoding="utf-8")
            out = root / "preview"
            manifest = PREVIEW.render(columns, out, (1, 3, 2, 2), "edge")
            saved = json.loads((out / "preview.json").read_text(encoding="utf-8"))
            self.assertEqual(PREVIEW.KIND, manifest["kind"])
            self.assertFalse(saved["releaseEvidence"])
            self.assertFalse(saved["independentReview"])
            self.assertFalse(saved["sceneCapture"])
            self.assertFalse(saved["formalReviewTaskClaimed"])
            self.assertIn("#725849", (out / "material-map.svg").read_text(encoding="utf-8"))
            self.assertTrue(saved["sampling"]["partial"])
            self.assertEqual(2 / 3, saved["sampling"]["coverageDensity"])
            self.assertIn("unsampled: gray", (out / "material-map.svg").read_text(encoding="utf-8"))

    def test_escapes_title_and_marks_unknown_material(self):
        with self.scratch() as tmp:
            root = Path(tmp); columns = root / "columns.json"
            columns.write_text(json.dumps({"columns": [{"x": 1, "z": 2, "bedBlock": "example:unmapped"}]}), encoding="utf-8")
            PREVIEW.render(columns, root / "preview", (1, 1, 2, 2), '<edge & "quote">')
            content = (root / "preview" / "material-map.svg").read_text(encoding="utf-8")
            self.assertIn('&lt;edge &amp; &quot;quote&quot;&gt;', content)
            self.assertIn('url(#unknown)', content)

    def test_rejects_empty_window(self):
        with self.scratch() as tmp:
            root = Path(tmp); columns = root / "columns.json"
            columns.write_text(json.dumps({"columns": [{"x": 1, "z": 2, "bedBlock": "minecraft:sand"}]}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "no sampled"):
                PREVIEW.render(columns, root / "preview", (8, 9, 8, 9), "empty")

    def test_world_preview_keeps_cover_unknown_and_paired_control(self):
        class World:
            def __init__(self, path): self.world, self.hashes = Path(path), {"r.0.0.mca": "a" * 64}
            def stable(self): return True
        def scan(world, name, bounds, materials, min_y, max_y, *, include_grid):
            self.assertTrue(include_grid); self.assertEqual((1, 2, 2, 2), bounds)
            return {"grid": [{"x": 1, "z": 2, "classification": "selected", "bedBlock": "immersive_weathering:silt"},
                             {"x": 2, "z": 2, "classification": "covered", "topBlock": "minecraft:mangrove_roots"}]}
        fake = SimpleNamespace(SavedWorld=World, scan_patch=scan)
        with self.scratch() as tmp, patch.object(PREVIEW, "measure_module", return_value=fake):
            root = Path(tmp); output = root / "preview"
            manifest = PREVIEW.render_world(root / "candidate", output, (1, 2, 2, 2), "paired", 60, 70, root / "control")
            self.assertEqual({"candidate", "control"}, set(manifest["worldSources"]))
            self.assertEqual(2, len(manifest["artifacts"]))
            content = (output / "material-map.svg").read_text(encoding="utf-8")
            self.assertIn("#625c4f", content)
            self.assertIn("url(#cover)", content)

    def test_world_preview_refuses_output_inside_source_world(self):
        with self.scratch() as tmp:
            root = Path(tmp); world = root / "world"; world.mkdir()
            with self.assertRaisesRegex(ValueError, "outside"):
                PREVIEW.render_world(world, world / "preview", (1, 1, 2, 2), "bad", 60, 70)


if __name__ == "__main__":
    unittest.main()
