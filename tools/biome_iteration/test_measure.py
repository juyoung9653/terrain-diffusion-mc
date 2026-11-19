import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("measure", Path(__file__).with_name("measure.py"))
measure = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(measure)


class MaterialTopologyTest(unittest.TestCase):
    class FakeWorld:
        def __init__(self, states): self.states = states
        def state(self, x, y, z): return self.states.get((x, y, z))

    def test_components_and_singletons(self):
        grid = {(0, 0): "mud", (1, 0): "mud", (3, 0): "mud", (0, 1): "sand"}
        self.assertEqual({"components": 2, "largest": 2, "singletons": 1, "sizes": [2, 1]}, measure.components(grid, "mud"))

    def test_diagonal_pair_is_counted(self):
        grid = {(0, 0): "mud", (1, 0): "sand", (0, 1): "sand", (1, 1): "mud"}
        self.assertEqual(1, measure.diagonal_pairs(grid, "mud"))
        grid[(1, 1)] = "__unreadable__"
        self.assertEqual(0, measure.diagonal_pairs(grid, "mud"))

    def test_contact_run_is_bounded_and_directional(self):
        grid = {(x, z): "mud" if x < 2 else "sand" for x in range(4) for z in range(5)}
        self.assertEqual({"northSouth": 5, "eastWest": 0}, measure.straight_contact_runs(grid, "mud"))

    def test_contact_run_does_not_cross_unknown_or_switch_sides(self):
        grid = {(x, z): "mud" if x < 2 else "sand" for x in range(4) for z in range(5)}
        grid[(2, 2)] = "__unreadable__"
        self.assertEqual(2, measure.straight_contact_runs(grid, "mud")["northSouth"])

    def test_patch_parser_rejects_bad_bounds(self):
        self.assertEqual(("case", (1, 2, 3, 4)), measure.parse_patch("case:1,2,3,4"))
        with self.assertRaises(Exception): measure.parse_patch("case:3,2,1,4")

    def test_scan_keeps_buried_selected_material_unclassified(self):
        world = self.FakeWorld({(0, 3, 0): ("minecraft:stone", False),
                                (0, 2, 0): ("minecraft:mud", False),
                                (1, 3, 0): ("minecraft:water", True),
                                (1, 2, 0): ("minecraft:mud", False)})
        report = measure.scan_patch(world, "case", (0, 0, 1, 0), {"minecraft:mud"}, 0, 3)
        self.assertEqual({"minecraft:mud": 1}, report["materials"])
        self.assertEqual(1, report["unclassifiedColumns"])

    def test_waterlogged_root_is_a_cover_not_open_water(self):
        world = self.FakeWorld({(0, 3, 0): ("minecraft:mangrove_roots", True),
                                (0, 2, 0): ("minecraft:mud", False)})
        report = measure.scan_patch(world, "covered", (0, 0, 0, 0), {"minecraft:mud"}, 2, 3)
        self.assertEqual({}, report["materials"])

    def test_optional_grid_keeps_cover_and_unreadable_columns_distinct(self):
        world = self.FakeWorld({(0, 3, 0): ("minecraft:mangrove_roots", True),
                                (1, 3, 0): ("minecraft:mud", False)})
        report = measure.scan_patch(world, "grid", (0, 0, 2, 0), {"minecraft:mud"}, 0, 3, include_grid=True)
        by_point = {(row["x"], row["z"]): row for row in report["grid"]}
        self.assertEqual("covered", by_point[(0, 0)]["classification"])
        self.assertEqual("selected", by_point[(1, 0)]["classification"])
        self.assertEqual("unreadable", by_point[(2, 0)]["classification"])


if __name__ == "__main__": unittest.main()
