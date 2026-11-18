from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("wetland_terrain", ROOT / "tools/ecology/analyze_wetland_terrain.py")
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC); sys.modules[SPEC.name] = MODULE; SPEC.loader.exec_module(MODULE)
Column, analyse_grid, _water_by_biome, _cover = MODULE.Column, MODULE.analyse_grid, MODULE._water_by_biome, MODULE._cover


class WetlandTerrainAnalysisTest(unittest.TestCase):
    def test_known_aquatic_and_greeneries_cover_is_skipped_but_unknown_block_is_not(self):
        self.assertTrue(_cover("minecraft:lily_pad"))
        self.assertTrue(_cover("greeneries:bushy_grass"))
        self.assertFalse(_cover("some_mod:unknown_block"))

    def test_tracks_connected_shallow_water_and_enclosed_dry_island(self):
        grid = {(x, z): Column("water", 63, 61, 2, "natural_solid", "freshwater") for x in range(5) for z in range(5)}
        grid[(2, 2)] = Column("land", 63, 63, 0, None, "freshwater")
        report = analyse_grid(grid, (0, 4, 0, 4))
        self.assertEqual(24, report["waterColumns"])
        self.assertEqual({"2": 24}, report["waterDepthColumns"])
        self.assertEqual(1, len(report["waterComponents"]))
        self.assertEqual(1, len(report["enclosedDryIslandComponents"]))
        self.assertEqual(1, report["enclosedDryIslandComponents"][0]["columns"])

    def test_reports_unsupported_water_and_separates_chunk_boundary_steps(self):
        grid = {(0, 0): Column("land", 60, 60, 0, None, "freshwater"),
                (1, 0): Column("land", 61, 61, 0, None, "freshwater"),
                (15, 0): Column("water", 63, 60, 3, "unsupported_air", "freshwater"),
                (16, 0): Column("land", 70, 70, 0, None, "freshwater")}
        report = analyse_grid(grid, (0, 16, 0, 0))
        self.assertEqual(1, report["unsupportedSurfaceWaterColumns"])
        self.assertEqual(1, report["groundStepMetrics"]["chunkBoundary"]["pairs"])
        self.assertEqual(10, report["groundStepMetrics"]["chunkBoundary"]["maxAbsoluteStep"])

    def test_biome_water_metrics_exclude_ocean_from_wetland_fraction(self):
        mangrove = "terrain-diffusion-mc:ecology/mangrove_swamp"
        freshwater = "terrain-diffusion-mc:ecology/freshwater_swamp"
        grid = {(0, 0): Column("water", 63, 61, 2, "natural_solid", mangrove),
                (1, 0): Column("land", 63, 63, 0, None, mangrove),
                (2, 0): Column("water", 63, 40, 23, "natural_solid", "minecraft:ocean"),
                (3, 0): Column("land", 64, 64, 0, None, "minecraft:ocean")}
        report = _water_by_biome(grid, (0, 3, 0, 0))
        self.assertEqual(.5, report[mangrove]["waterFraction"])
        self.assertEqual(0, report[freshwater]["waterColumns"])
        self.assertEqual(1, report[mangrove]["waterComponents"][0]["columns"])


if __name__ == "__main__":
    unittest.main()
