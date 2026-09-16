import unittest

from river_metrics import Segment, clip_segment, measure


class RiverMetricsTest(unittest.TestCase):
    BOUNDS = (0.0, 0.0, 10.0, 10.0)

    def test_clip_interpolates_geometry(self):
        clipped = clip_segment(Segment(-5, 5, 0, 1, 15, 5, 20, 5), self.BOUNDS)
        self.assertEqual(clipped, Segment(0, 5, 5, 2, 10, 5, 15, 4))

    def test_shared_node_is_one_junction_and_one_crossing(self):
        segments = [
            Segment(0, 0, 10, 1, 5, 5, 5, 2),
            Segment(0, 10, 10, 1, 5, 5, 5, 2),
            Segment(5, 5, 5, 2, 10, 5, 0, 3),
        ]
        result = measure(segments, self.BOUNDS, [(5, 0, 5, 10)])
        self.assertEqual(result["network"], {"components": 1, "nodes": 4, "sources": 2, "outlets": 1, "junctions": 1})
        self.assertEqual(result["transects"][0]["crossings"], 1)

    def test_parallel_branches_remain_distinct_crossings(self):
        segments = [Segment(1, 2, 3, 1, 9, 2, 2, 1), Segment(1, 8, 3, 1, 9, 8, 2, 1)]
        result = measure(segments, self.BOUNDS, [(5, 0, 5, 10)])
        self.assertEqual(result["network"]["components"], 2)
        self.assertEqual(result["transects"][0]["crossings"], 2)

    def test_slope_and_width_are_length_weighted(self):
        segments = [Segment(0, 0, 10, 2, 4, 0, 10, 2), Segment(4, 0, 10, 4, 4, 4, 9, 6)]
        result = measure(segments, self.BOUNDS)
        geometry = result["geometry"]
        self.assertAlmostEqual(geometry["length"], 8)
        self.assertAlmostEqual(geometry["lengthWeightedHalfWidth"], 3.5)
        self.assertAlmostEqual(geometry["totalDownwardChange"], 1)
        self.assertEqual(geometry["slopeBins"], {"uphill": 0, "level": 1, "down_lt_0_01": 0, "down_0_01_to_0_05": 0, "down_ge_0_05": 1})

    def test_empty_input_is_deterministic(self):
        result = measure([], self.BOUNDS, [(0, 0, 10, 10)])
        self.assertEqual(result["segments"], {"input": 0, "intersectingBounds": 0})
        self.assertEqual(result["network"], {"components": 0, "nodes": 0, "sources": 0, "outlets": 0, "junctions": 0})
        self.assertEqual(result["geometry"]["lengthPerArea"], 0)
        self.assertEqual(result["transects"][0]["crossings"], 0)

    def test_bounds_do_not_invent_sources_or_outlets(self):
        segments = [Segment(-5, 5, 2, 1, 5, 5, 1, 1), Segment(5, 5, 1, 1, 15, 5, 0, 1)]
        result = measure(segments, self.BOUNDS)
        self.assertEqual(result["network"], {"components": 1, "nodes": 1, "sources": 0, "outlets": 0, "junctions": 0})
        with self.assertRaises(ValueError):
            measure([], (0, 0, 0, 0))


if __name__ == "__main__":
    unittest.main()
