from __future__ import annotations

import csv
import json
import tempfile
import unittest
from pathlib import Path

from restart_snapshot import capture_snapshot, compare_snapshots


class RestartSnapshotTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2] / "build")
        self.addCleanup(self.temp.cleanup)
        self.world = Path(self.temp.name) / "world"
        (self.world / "data").mkdir(parents=True)
        (self.world / "streamsreflowing/minecraft_overworld").mkdir(parents=True)
        for name in ("terrain_diffusion_world_settings.dat", "terrain_diffusion_natural_rivers.dat"):
            (self.world / "data" / name).write_bytes(b"marker")
        (self.world / "streamsreflowing/minecraft_overworld/t.0.0.srt").write_bytes(b"graph")
        (self.world / "td-natural-river-network.csv").write_text("x1,z1,y1,w1,x2,z2,y2,w2\n0,0,70,1,1,1,70,1\n")
        self.write_chunk()

    def write_chunk(self, mutate: dict[tuple[int, int], dict[str, str]] | None = None,
                    rows: int = 256, reverse: bool = False) -> None:
        fields = ["x", "z", "actualTopmostY", "actualTopmostBlock", "terrainTopSolidY",
                  "terrainTopSolidBlock", "actualSavedTerrainBiome", "actualTopWaterY",
                  "ecoTemperature", "ecoMoisture", "ecoSlope", "originalGroundY"]
        values = []
        for z in range(16):
            for x in range(16):
                row = {"x": str(x), "z": str(z), "actualTopmostY": "70",
                       "actualTopmostBlock": "minecraft:sand", "terrainTopSolidY": "70",
                       "terrainTopSolidBlock": "minecraft:sand",
                       "actualSavedTerrainBiome": "minecraft:desert", "actualTopWaterY": "-65",
                       "ecoTemperature": "30", "ecoMoisture": "0.1", "ecoSlope": "0.0",
                       "originalGroundY": "70"}
                row.update((mutate or {}).get((x, z), {})); values.append(row)
        if reverse: values.reverse()
        with (self.world / "td-streams-probe-surface-audit-0-0.csv").open("w", newline="") as out:
            writer = csv.DictWriter(out, fieldnames=fields); writer.writeheader(); writer.writerows(values[:rows])

    def test_diagnostics_can_change_but_generated_surface_cannot(self) -> None:
        before = capture_snapshot(self.world)
        self.write_chunk({(1, 1): {"ecoMoisture": "0.2", "originalGroundY": "71"}}, reverse=True)
        after = capture_snapshot(self.world)
        self.assertTrue(compare_snapshots(before, after)["passed"])
        self.assertTrue(compare_snapshots(json.loads(json.dumps(before)), json.loads(json.dumps(after)))["passed"])
        for field, value in (("actualTopmostBlock", "minecraft:grass_block"),
                             ("terrainTopSolidY", "71"), ("actualTopWaterY", "70"),
                             ("actualSavedTerrainBiome", "minecraft:beach")):
            self.write_chunk(); before = capture_snapshot(self.world)
            self.write_chunk({(1, 1): {field: value}})
            result = compare_snapshots(before, capture_snapshot(self.world))
            self.assertFalse(result["passed"])
            self.assertTrue(any(field in failure for failure in result["failures"]))

    def test_malformed_or_incomplete_evidence_is_rejected(self) -> None:
        self.write_chunk(rows=255)
        with self.assertRaisesRegex(ValueError, "256"): capture_snapshot(self.world)
        self.write_chunk()
        (self.world / "streamsreflowing/minecraft_overworld/t.0.0.srt").unlink()
        with self.assertRaisesRegex(ValueError, "empty"): capture_snapshot(self.world)

    def test_saved_graph_changes_fail(self) -> None:
        before = capture_snapshot(self.world)
        (self.world / "td-natural-river-network.csv").write_text("x1,z1,y1,w1,x2,z2,y2,w2\n1,1,70,1,2,2,70,1\n")
        self.assertFalse(compare_snapshots(before, capture_snapshot(self.world))["passed"])

    def test_added_or_dropped_cache_files_fail(self) -> None:
        before = capture_snapshot(self.world)
        (self.world / "streamsreflowing/minecraft_overworld/t.0.1.srt").write_bytes(b"new")
        self.assertFalse(compare_snapshots(before, capture_snapshot(self.world))["passed"])
        (self.world / "streamsreflowing/minecraft_overworld/t.0.1.srt").unlink()
        before = capture_snapshot(self.world)
        (self.world / "streamsreflowing/minecraft_overworld/t.0.0.srt").unlink()
        with self.assertRaisesRegex(ValueError, "empty"):
            capture_snapshot(self.world)

    def test_row_in_wrong_chunk_is_rejected(self) -> None:
        path = self.world / "td-streams-probe-surface-audit-0-0.csv"
        text = path.read_text()
        (self.world / "td-streams-probe-surface-audit-1-0.csv").write_text(text)
        with self.assertRaisesRegex(ValueError, "outside named chunk"):
            capture_snapshot(self.world)

    def test_duplicate_surface_coordinate_is_rejected(self) -> None:
        path = self.world / "td-streams-probe-surface-audit-0-0.csv"
        with path.open(newline="") as source:
            rows = list(csv.DictReader(source))
        rows[-1] = dict(rows[0])
        with path.open("w", newline="") as out:
            writer = csv.DictWriter(out, fieldnames=rows[0].keys())
            writer.writeheader(); writer.writerows(rows)
        with self.assertRaisesRegex(ValueError, "duplicate surface coordinate"):
            capture_snapshot(self.world)

    def test_duplicate_header_and_unknown_field_fail_closed(self) -> None:
        path = self.world / "td-streams-probe-surface-audit-0-0.csv"
        text = path.read_text()
        path.write_text(text.replace("x,z,", "x,x,z,", 1))
        with self.assertRaisesRegex(ValueError, "header"):
            capture_snapshot(self.world)
        self.write_chunk(); before = capture_snapshot(self.world)
        lines = path.read_text().splitlines()
        lines[0] += ",newProbeField"
        lines[1:] = [line + ",value" for line in lines[1:]]
        path.write_text("\n".join(lines) + "\n")
        self.assertFalse(compare_snapshots(before, capture_snapshot(self.world))["passed"])

    def test_empty_or_missing_snapshot_fails(self) -> None:
        self.assertFalse(compare_snapshots({}, {})["passed"])
        (self.world / "data/terrain_diffusion_world_settings.dat").unlink()
        with self.assertRaisesRegex(ValueError, "markers"):
            capture_snapshot(self.world)

    def test_malformed_network_and_physical_value_are_rejected(self) -> None:
        (self.world / "td-natural-river-network.csv").write_text(
            "x1,z1,y1,w1,x2,z2,y2,w2\nnot,a,number,1,1,1,1,1\n")
        with self.assertRaisesRegex(ValueError, "network"):
            capture_snapshot(self.world)
        (self.world / "td-natural-river-network.csv").write_text(
            "x1,z1,y1,w1,x2,z2,y2,w2\n0,0,70,1,1,1,70,1\n")
        self.write_chunk({(0, 0): {"actualTopmostBlock": ""}})
        with self.assertRaisesRegex(ValueError, "generated-block value"):
            capture_snapshot(self.world)


if __name__ == "__main__": unittest.main()
