import csv
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("analyze_wetland_spatial.py")
SPEC = importlib.util.spec_from_file_location("analyze_wetland_spatial", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class WetlandSpatialAnalysisTest(unittest.TestCase):
    def test_components_fractions_boundary_and_clipping(self):
        rows = []
        # 3x3 four-block grid: five reed cells touch the map edge; one separate
        # freshwater cell establishes a second label and a measurable boundary.
        ids = ((20, 20, 1), (20, 21, 1), (20, 20, 1))
        for z, line in enumerate(ids):
            for x, biome_id in enumerate(line):
                rows.append({"window": "sample", "x": x * 4, "z": z * 4, "biomeId": biome_id,
                             "elevation": 10 if not (x == 2 and z == 2) else -2,
                             "wetness": .8 if biome_id == 20 else .6 if biome_id == 21 else 0,
                             "marineInfluence": 0, "reedWeight": .7 if biome_id == 20 else 0,
                             "swampWeight": .4 if biome_id == 21 else 0, "mangroveWeight": 0,
                             "riparianWeight": 0})
        # The sandbox's system temporary directory may be read-only; a workspace
        # temporary directory is also representative of ordinary local execution.
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as temp:
            root = Path(temp); source, output = root / "grid.csv", root / "out"
            with source.open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=rows[0].keys()); writer.writeheader(); writer.writerows(rows)
            report = MODULE.analyse(source, output)["windows"]["sample"]
            self.assertEqual(report["gridStepBlocks"], 4)
            self.assertAlmostEqual(report["biomeFractions"]["20"]["all"], 5 / 9)
            self.assertEqual(report["components"]["20"][0]["areaBlocks2"], 80)
            self.assertTrue(report["components"]["20"][0]["mapBoundaryClipped"])
            self.assertEqual(report["components"]["21"][0]["cells"], 1)
            self.assertEqual(report["fragmentation"]["21"]["isolatedCellFraction"], 1)
            self.assertEqual(report["fragmentation"]["20"]["isolatedCellFraction"], 0)
            self.assertEqual(report["fragmentation"]["20"]["largestAreaBlocks2"], 80)
            self.assertIsNone(report["fragmentation"]["22"]["isolatedCellFraction"])
            self.assertEqual(report["adjacentWetness"]["edges"], 12)
            self.assertAlmostEqual(report["adjacentWetness"]["p90AbsoluteDifference"], .8)
            self.assertIsNone(report["components"]["21"][0]["approximateInteriorWidth"]["maximumBlocks"])
            self.assertEqual(report["components"]["21"][0]["approximateInteriorWidth"]["status"], "singleton_no_interior_width")
            self.assertIn("20:21", report["boundaryContinuity"])
            self.assertTrue((output / "sample.png").is_file())
            self.assertEqual(json.loads((output / "summary.json").read_text(encoding="utf-8"))["windows"]["sample"]["sampleCount"], 9)

    def test_interior_width_distinguishes_strip_from_patch(self):
        rows = []
        for z in range(6):
            for x in range(10):
                biome_id = 1
                if x == 1 and 1 <= z <= 4:
                    biome_id = 20  # A one-cell-wide, non-clipped vertical strip.
                if 5 <= x <= 7 and 1 <= z <= 3:
                    biome_id = 21  # A non-clipped 3x3 patch.
                rows.append({"window": "width", "x": x * 4, "z": z * 4, "biomeId": biome_id,
                             "elevation": 10, "wetness": .8 if biome_id in (20, 21) else 0,
                             "marineInfluence": 0, "reedWeight": 1 if biome_id == 20 else 0,
                             "swampWeight": 1 if biome_id == 21 else 0, "mangroveWeight": 0,
                             "riparianWeight": 0})
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as temp:
            root = Path(temp); source, output = root / "grid.csv", root / "out"
            with source.open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=rows[0].keys()); writer.writeheader(); writer.writerows(rows)
            components = MODULE.analyse(source, output)["windows"]["width"]["components"]
            strip = components["20"][0]["approximateInteriorWidth"]
            patch = components["21"][0]["approximateInteriorWidth"]
            self.assertEqual(strip["status"], "estimate")
            self.assertEqual(strip["maximumBlocks"], 8)
            self.assertGreater(patch["maximumBlocks"], strip["maximumBlocks"])

    def test_missing_grid_column_is_rejected(self):
        rows = []
        for z in range(3):
            for x in (0, 1, 3):  # Missing x=2 produces an 8-block coordinate gap.
                rows.append({"window": "gap", "x": x * 4, "z": z * 4, "biomeId": 1,
                             "elevation": 10, "wetness": 0, "marineInfluence": 0,
                             "reedWeight": 0, "swampWeight": 0, "mangroveWeight": 0,
                             "riparianWeight": 0})
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as temp:
            source = Path(temp) / "grid.csv"
            with source.open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=rows[0].keys()); writer.writeheader(); writer.writerows(rows)
            with self.assertRaisesRegex(ValueError, "irregular x coordinate spacing"):
                MODULE.analyse(source, Path(temp) / "out")


if __name__ == "__main__":
    unittest.main()
