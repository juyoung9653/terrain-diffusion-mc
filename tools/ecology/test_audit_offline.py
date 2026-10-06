"""Focused offline tests for the production ecology atlas sampler."""
from __future__ import annotations

import csv
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "ecology" / "audit_offline.py"
SPEC = importlib.util.spec_from_file_location("audit_offline", SCRIPT)
offline = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(offline)


class EcologyOfflineAtlasTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.javac, cls.java = offline.java_tools()
        cls.classes = cls.root / "classes"
        offline.compile_sampler(cls.classes, cls.javac)
        cls.first = cls.root / "first"
        cls.second = cls.root / "second"
        cls.first.mkdir()
        cls.second.mkdir()
        # Include enough independent broad regions to assess material fractions;
        # a small window can legitimately sit inside a bare-soil patch.
        offline.run_sampler(cls.first, cls.classes, cls.java, 128, 16)
        offline.run_sampler(cls.second, cls.classes, cls.java, 128, 16)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temp.cleanup()

    def rows(self):
        with (self.first / "summary.csv").open(newline="", encoding="utf-8") as stream:
            return list(csv.DictReader(stream))

    def test_sampler_is_byte_reproducible(self) -> None:
        for name in ("cells.csv", "summary.csv", "climate-grid.csv", "manifest.json"):
            with self.subTest(name=name):
                left = hashlib.sha256((self.first / name).read_bytes()).digest()
                right = hashlib.sha256((self.second / name).read_bytes()).digest()
                self.assertEqual(left, right)

    def test_actual_classifier_covers_all_11_profiles(self) -> None:
        rows = self.rows()
        self.assertEqual(len(offline.PROFILES) * len(offline.SEEDS) * len(offline.SCALES), len(rows))
        self.assertEqual(set(offline.PROFILES), {row["profile"] for row in rows})
        for row in rows:
            self.assertEqual(offline.PROFILES.index(row["profile"]) + 1, int(row["biome_id"]))

    def test_material_fractions_and_tree_probability_bounds(self) -> None:
        for row in self.rows():
            current = sum(float(row[f"current_{name}_fraction"])
                          for name in ("grass", "coarse_dirt", "sand", "podzol"))
            legacy = sum(float(row[f"legacy_{name}_fraction"])
                         for name in ("grass", "coarse_dirt", "sand"))
            self.assertAlmostEqual(1.0, current, places=8)
            self.assertAlmostEqual(1.0, legacy, places=8)
            self.assertLessEqual(0.0, float(row["min_tree_probability"]))
            self.assertLessEqual(float(row["min_tree_probability"]), float(row["max_tree_probability"]))
            self.assertLessEqual(float(row["max_tree_probability"]), 0.5)
        dry = [row for row in self.rows() if row["profile"] == "dry_grassland"]
        self.assertTrue(all(float(row["current_grass_fraction"]) > 0.80 for row in dry))
        self.assertTrue(all(0.01 < float(row["current_coarse_dirt_fraction"]) < 0.20 for row in dry))
        treeless = [row for row in self.rows() if row["profile"] in ("dry_grassland", "desert")]
        self.assertTrue(all(int(row["tree_above_mean_patch_count"]) == 0 for row in treeless))
        self.assertTrue(all(float(row["tree_above_mean_largest_fraction"]) == 0 for row in treeless))

    def test_broad_climate_and_groves_are_scale_invariant(self) -> None:
        by_key = {}
        with (self.first / "cells.csv").open(newline="", encoding="utf-8") as stream:
            for row in csv.DictReader(stream):
                key = row["profile"], row["seed"], row["model_x"], row["model_z"]
                value = row["legacy_td_surface"], row["tree_probability"], row["tree_cover"]
                scale = int(row["scale"])
                if scale == 2:
                    by_key[key] = value
                else:
                    self.assertEqual(by_key[key], value)

    def test_renderer_writes_labeled_png_atlases(self) -> None:
        grids = offline.load_cells(self.first / "cells.csv", 128)
        offline.render_atlas(self.first, grids, 128)
        offline.render_climate(self.first)
        images = list(self.first.glob("*-atlas-seed-*.png"))
        self.assertEqual(len(offline.SEEDS) * len(offline.SCALES) * 3, len(images))
        for path in images + [self.first / "climate-label-grid.png"]:
            self.assertEqual(b"\x89PNG\r\n\x1a\n", path.read_bytes()[:8])


if __name__ == "__main__":
    unittest.main()
