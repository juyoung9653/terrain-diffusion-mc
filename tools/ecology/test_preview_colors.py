#!/usr/bin/env python3
"""Validate TD World Preview biome-color coverage and its accepted resource shape."""
from __future__ import annotations

import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
COLORS = ROOT / "common/src/main/resources/data/c/worldgen/biome_colors.json"
CLASSIFIER = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/EcologyClassifier.java"
BIOME_SOURCE = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/TerrainDiffusionBiomeSource.java"


def expected_td_ids() -> set[str]:
    classifier = CLASSIFIER.read_text(encoding="utf-8")
    ecology_list = re.search(r"BIOMES\s*=\s*List\.of\((.*?)\);", classifier, re.S)
    assert ecology_list, "could not find EcologyClassifier.BIOMES"
    ecology = re.findall(r'"([a-z_]+)"', ecology_list.group(1))
    source = BIOME_SOURCE.read_text(encoding="utf-8")
    sparse = re.findall(r'"terrain-diffusion-mc",\s*"([a-z_]+_sparse)"', source)
    return ({f"terrain-diffusion-mc:ecology/{name}" for name in ecology}
            | {f"terrain-diffusion-mc:{name}" for name in sparse})


class PreviewBiomeColorsTest(unittest.TestCase):
    def test_colors_cover_all_td_biomes_the_source_can_return(self) -> None:
        colors = json.loads(COLORS.read_text(encoding="utf-8-sig"))
        expected = expected_td_ids()
        self.assertEqual(expected, set(colors), "keep Preview color entries in lockstep with TD custom biome IDs")
        self.assertEqual(22, len(expected))

    def test_resource_uses_world_preview_shared_c_namespace_path(self) -> None:
        self.assertEqual(Path("common/src/main/resources/data/c/worldgen/biome_colors.json"), COLORS.relative_to(ROOT))

    def test_entries_match_world_preview_rgb_schema(self) -> None:
        colors = json.loads(COLORS.read_text(encoding="utf-8-sig"))
        for biome_id, entry in colors.items():
            with self.subTest(biome_id=biome_id):
                self.assertIsInstance(entry.get("name"), str)
                self.assertTrue(entry["name"].strip())
                for channel in ("r", "g", "b"):
                    self.assertIsInstance(entry.get(channel), int)
                    self.assertGreaterEqual(entry[channel], 0)
                    self.assertLessEqual(entry[channel], 255)


if __name__ == "__main__":
    unittest.main()
