import unittest

from river_hierarchy import Segment, node_key
from river_routes import measure


class RiverRoutesTest(unittest.TestCase):
    BOUNDS = (-10.0, -10.0, 100.0, 100.0)

    def test_known_grades_widths_and_thin_start(self):
        rows = [Segment(0, 0, 12, .5, 10, 0, 8, 1), Segment(10, 0, 8, 2, 20, 0, 4, 3)]
        result = measure(rows, self.BOUNDS, {node_key(20, 0): True})
        route = result["marineTerminalsInsideBounds"][0]
        self.assertEqual(route["chosenSourceToTerminalShortestPathLength"], 20)
        self.assertEqual(route["pathEdges"][0]["gradeY1"], 12)
        self.assertEqual(route["pathEdges"][1]["meanFullPlanWidth"], 5)
        self.assertEqual(route["pathWidth"]["initialUninterruptedLength"], {"meanFullWidthLt4": 10.0, "meanFullWidthLt8": 20.0})

    def test_cycle_with_source_still_has_finite_route(self):
        rows = [Segment(0, 0, 0, 1, 1, 0, 0, 1), Segment(1, 0, 0, 1, 2, 0, 0, 1),
                Segment(2, 0, 0, 1, 1, 0, 0, 1), Segment(2, 0, 0, 1, 3, 0, 0, 1)]
        route = measure(rows, self.BOUNDS, {node_key(3, 0): True})["marineTerminalsInsideBounds"][0]
        self.assertEqual(route["reachableSourceCount"], 1)
        self.assertEqual(route["chosenSourceToTerminalShortestPathLength"], 3)

    def test_split_outlets_are_reported_independently(self):
        rows = [Segment(0, 0, 0, 1, 1, 0, 0, 1), Segment(1, 0, 0, 1, 2, 0, 0, 1),
                Segment(1, 0, 0, 1, 1, 2, 0, 1)]
        result = measure(rows, self.BOUNDS, {node_key(2, 0): True, node_key(1, 2): True})
        self.assertEqual(len(result["marineTerminalsInsideBounds"]), 2)
        self.assertTrue(all(row["isDirectedGraphTerminal"] for row in result["marineTerminalsInsideBounds"]))

    def test_shuffled_duplicates_choose_shortest_geometry(self):
        long = Segment(0, 0, 0, 1, 10.001, 0, 0, 1)
        short = Segment(.001, 0, 0, 1, 9.97, 0, 0, 1)
        one = measure([long, short], self.BOUNDS, {node_key(10, 0): True})
        two = measure([short, long], self.BOUNDS, {node_key(10, 0): True})
        self.assertEqual(one["marineTerminalsInsideBounds"], two["marineTerminalsInsideBounds"])
        self.assertAlmostEqual(one["marineTerminalsInsideBounds"][0]["chosenSourceToTerminalShortestPathLength"], 9.969)

    def test_isolated_self_edge_and_no_source_route(self):
        self_only = measure([Segment(1, 1, 0, 1, 1.01, 1, 0, 1)], self.BOUNDS, {node_key(1, 1): True})
        self.assertFalse(self_only["marineTerminalsInsideBounds"][0]["matchedGraphNode"])
        no_source = measure([Segment(0, 0, 0, 1, 1, 0, 0, 1), Segment(1, 0, 0, 1, 0, 0, 0, 1),
                             Segment(1, 0, 0, 1, 2, 0, 0, 1)], self.BOUNDS, {node_key(2, 0): True})
        route = no_source["marineTerminalsInsideBounds"][0]
        self.assertTrue(route["isDirectedGraphTerminal"])
        self.assertEqual(route["reachableSourceCount"], 0)
        self.assertIsNone(route["chosenSource"])


if __name__ == "__main__":
    unittest.main()
