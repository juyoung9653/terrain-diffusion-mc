"""Receipt contract: distinguish effective eligibility from physical world evidence."""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("streams_run_suite", ROOT / "tools/streams_probe/run_suite.py")
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)

MAPPING = {"effectivePredicateAdmitsMappedBiome": True, "possibleStructureSetRetained": True,
           "locatePlacementRetained": True}
STRUCTURE = {"event": "mod_structure_compatibility_audit", "status": "passed", "mappings": [MAPPING] * 8,
             "galleon": {"placementClass": "net.mehvahdjukaar.supplementaries.common.worldgen.RandomSpreadStructurePlacementWithExclusion",
                         "plannerSet": "supplementaries:galleons", "plannerRetained": True},
             "physicalEvidenceLevel": "not_requested", "physicalTargets": []}
WETLAND = {"event": "wetland_biome_audit", "status": "passed", "evidenceLevel": "registry_only_no_full_sites"}

class WetlandCompatReceiptTest(unittest.TestCase):
    def check(self, physical=False, structure=STRUCTURE, wetland=WETLAND):
        path = ROOT / "build/wetland-compat-tests" / (uuid.uuid4().hex + ".jsonl")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(structure) + "\n" + json.dumps(wetland) + "\n", encoding="utf-8")
        return MODULE.wetland_compat_receipt(path, physical_requested=physical)
    def test_effective_predicate_receipt_passes_without_requested_physical_proof(self):
        self.assertEqual("success", self.check())
    def test_registry_only_does_not_pass_requested_physical_proof(self):
        self.assertEqual("wetland_or_structure_physical_evidence_not_proven", self.check(True))
    def test_full_chunk_proof_requires_piece_and_nbt(self):
        structure = STRUCTURE | {"physicalEvidenceLevel": "full_chunk_start_piece_nbt",
            "physicalTargets": [{"fullChunk": True, "nativeStart": True, "pieces": 2, "startNbtHash": 4,
                                 "centerBiomeMatchesExpectedHabitat": True}]}
        wetland = WETLAND | {"evidenceLevel": "natural_full_chunks", "samples": [{"label": "reed_marsh"}]}
        self.assertEqual("success", self.check(True, structure, wetland))

if __name__ == "__main__": unittest.main()
