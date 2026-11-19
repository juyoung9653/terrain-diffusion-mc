import json
from pathlib import Path
import unittest
from local import chunk_list, evaluate, sites


class PairedChecks(unittest.TestCase):
    def test_negative_coordinates_cover_both_sides_of_chunk_edge(self):
        case = json.loads(Path(__file__).with_name("reef-example.json").read_text())
        self.assertEqual(chunk_list(sites(case)), [(-119, 98), (-118, 98), (-60, 103)])
        case["sites"][1]["bounds"] = case["sites"][0]["bounds"]
        with self.assertRaises(ValueError):
            sites(case)

    def test_no_observation_cannot_pass_absence(self):
        row = [{"id":"inlet","coral":"absent"}]
        report = {"candidate":{"inlet":{"livingCoralBlocks":0,"unreadableColumns":0}},
                  "candidateSourceRegionsStable":True,"candidateMissingChunks":[(1,2)],"candidateNonFullChunks":{}}
        self.assertEqual(evaluate(row, report)["inlet"], "inconclusive")
        report["candidateMissingChunks"] = []
        self.assertEqual(evaluate(row, report)["inlet"], "matches")
        report["candidate"]["inlet"]["livingCoralBlocks"] = 1
        self.assertEqual(evaluate(row, report)["inlet"], "mismatch")
