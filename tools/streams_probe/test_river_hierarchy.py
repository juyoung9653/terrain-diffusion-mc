import tempfile
import unittest
from pathlib import Path

from river_hierarchy import Segment, measure, node_key, read_marine_terminals


class RiverHierarchyTest(unittest.TestCase):
    BOUNDS = (0.0, 0.0, 1000.0, 1000.0)

    def test_branched_tree_reports_lengths_widths_and_terminal(self):
        # 0->128->256 joins 64->256, then continues to 512.  The first
        # source's first-confluence path is 256 and the longest terminal arc 512.
        rows = [
            Segment(0, 0, 0, .5, 128, 0, 0, .5),
            Segment(128, 0, 0, 1.5, 256, 0, 0, 1.5),
            Segment(64, 64, 0, 2.5, 256, 0, 0, 2.5),
            Segment(256, 0, 0, 5, 512, 0, 0, 10),
        ]
        result = measure(rows, self.BOUNDS, {node_key(512, 0): True})
        self.assertEqual(result["firstOrderToFirstConfluence"]["count"], 2)
        self.assertEqual(result["firstOrderToFirstConfluence"]["countGe128"], 2)
        self.assertEqual(result["firstOrderToFirstConfluence"]["countGe256"], 1)
        self.assertEqual(result["terminalsInsideBounds"][0]["longestUpstreamArcLength"], 512)
        self.assertEqual(result["terminalsInsideBounds"][0]["upstreamSources"], 2)
        self.assertTrue(result["terminalsInsideBounds"][0]["marineTerminalTrue"])
        bins = result["width"]["lengthByFullWidthBin"]
        self.assertEqual(bins["lt_2"], 128.0)
        self.assertEqual(bins["2_to_4"], 128.0)
        self.assertAlmostEqual(bins["4_to_8"], (192 ** 2 + 64 ** 2) ** .5)
        self.assertEqual(bins["8_to_16"], 256.0)
        self.assertEqual(bins["ge_16"], 0.0)
        self.assertEqual(result["width"]["lengthWeightedQuantiles"]["p50"], 5.0)

    def test_clipping_does_not_reset_source_path(self):
        rows = [Segment(-200, 0, 0, 1, 0, 0, 0, 1), Segment(0, 0, 0, 1, 200, 0, 0, 1),
                Segment(100, 100, 0, 1, 200, 0, 0, 1), Segment(200, 0, 0, 1, 300, 0, 0, 1)]
        result = measure(rows, (0, -10, 300, 10))
        # The only source is outside the window, so it is not a fixture-local
        # first-order sample; its full exported path still determines terminal length.
        self.assertEqual(result["firstOrderToFirstConfluence"]["countGe256"], 0)
        self.assertEqual(result["firstOrderToFirstConfluence"]["sourcesInsideBounds"], 0)
        self.assertEqual(result["terminalsInsideBounds"][0]["longestUpstreamArcLength"], 500)

    def test_quantization_parallel_and_self_edges_do_not_invent_topology(self):
        rows = [Segment(0.001, 0, 0, 1, 10, 0, 0, 1), Segment(0, 0.001, 0, 1, 10, 0, 0, 1),
                Segment(10, 0, 0, 1, 10.01, 0, 0, 1)]
        result = measure(rows, self.BOUNDS)
        self.assertEqual(result["segments"], {"input": 3, "uniqueDirectedEdges": 1,
                         "parallelEdgesIgnored": 1, "selfEdgesIgnored": 1})
        self.assertEqual(result["components"][0]["confluences"], 0)

    def test_java_rounding_and_parallel_maximum_are_order_independent(self):
        self.assertEqual(node_key(-.09375, -.03125), (-1, 0))  # Java Math.round(-1.5) == -1.
        long = Segment(0.001, 0, 0, 1, 10.001, 0, 0, 1)
        short = Segment(-.001, 0, 0, 1, 9.97, 0, 0, 1)
        one = measure([long, short], self.BOUNDS)
        two = measure([short, long], self.BOUNDS)
        self.assertEqual(one["segments"], two["segments"])
        self.assertAlmostEqual(one["terminalsInsideBounds"][0]["longestUpstreamArcLength"], 10.0)
        self.assertEqual(one["terminalsInsideBounds"], two["terminalsInsideBounds"])

    def test_actual_natural_river_audit_header_binds_marine_flag(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "terminal.csv"
            path.write_text("x,z,gradeY,fullWidth,marineWithin32\n12.0,-4.0,62,19.4,true\n", encoding="utf-8")
            flags = read_marine_terminals(path)
        self.assertEqual(flags, {node_key(12, -4): True})

    def test_cycle_and_multiple_terminals_disable_dag_metrics(self):
        cycle = measure([Segment(0, 0, 0, 1, 1, 0, 0, 1), Segment(1, 0, 0, 1, 0, 0, 0, 1)], self.BOUNDS)
        self.assertTrue(cycle["components"][0]["cycle"])
        self.assertEqual(cycle["terminalsInsideBounds"], [])
        fork = measure([Segment(0, 0, 0, 1, 1, 0, 0, 1), Segment(0, 0, 0, 1, 0, 1, 0, 1)], self.BOUNDS)
        self.assertTrue(fork["components"][0]["multiTerminalAmbiguity"])
        self.assertEqual(fork["firstOrderToFirstConfluence"]["count"], 0)

    def test_empty_input_keeps_known_zeros(self):
        result = measure([], self.BOUNDS)
        self.assertEqual(result["segments"]["uniqueDirectedEdges"], 0)
        self.assertEqual(result["components"], [])
        self.assertEqual(result["width"]["lengthWeightedQuantiles"]["p50"], 0)
        self.assertEqual(result["firstOrderToFirstConfluence"]["countGe512"], 0)


if __name__ == "__main__":
    unittest.main()
