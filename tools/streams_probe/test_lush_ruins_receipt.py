"""Focused receipt contract for native Lush Cave Ruins generation evidence."""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import unittest
import uuid

ROOT=Path(__file__).resolve().parents[2]
SPEC=importlib.util.spec_from_file_location("streams_run_suite",ROOT/"tools/streams_probe/run_suite.py")
assert SPEC and SPEC.loader
MODULE=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)

GOOD={"event":"lush_cave_ruins_audit","status":"passed","nativeStart":True,"locateMatched":True,"lootTablesLoaded":True,
      "structure":"keeb_struc_lush:keeb_struc_lush","biome":"terralith:cave/underground_jungle",
      "pieces":1,"fullChunks":1,"structureBlocks":1}
GOOD.update(minimumReferenceDistanceBlocks=1024, sampledMinimumReferenceDistanceSquared=1024**2)
GOOD.update(anchorObservedDuringGeneration=True)

class LushRuinsReceiptTest(unittest.TestCase):
    def receipt(self,*rows:dict)->str:
        directory=ROOT/"build/lush-cave-ruins-tests/receipt-fixtures"
        directory.mkdir(parents=True,exist_ok=True)
        path=directory/(uuid.uuid4().hex+".jsonl")
        path.write_text("".join(json.dumps(row)+"\n" for row in rows),encoding="utf-8")
        return MODULE.lush_cave_ruins_receipt(path)
    def test_valid_receipt(self): self.assertEqual("success",self.receipt(GOOD))
    def test_missing_event(self): self.assertEqual("lush_cave_ruins_receipt_missing",self.receipt({"event":"other"}))
    def test_wrong_id(self):
        row=GOOD|{"structure":"example:other"}; self.assertEqual("lush_cave_ruins_natural_generation_not_proven",self.receipt(row))
    def test_wrong_biome(self):
        row=GOOD|{"biome":"minecraft:lush_caves"}; self.assertEqual("lush_cave_ruins_natural_generation_not_proven",self.receipt(row))
    def test_missing_natural_or_locate(self):
        for key in ("nativeStart","locateMatched","lootTablesLoaded","anchorObservedDuringGeneration"):
            with self.subTest(key=key): self.assertEqual("lush_cave_ruins_natural_generation_not_proven",self.receipt(GOOD|{key:False}))
    def test_zero_counts(self):
        for key in ("pieces","fullChunks","structureBlocks"):
            with self.subTest(key=key): self.assertEqual("lush_cave_ruins_natural_generation_not_proven",self.receipt(GOOD|{key:0}))
    def test_spacing_evidence_required(self):
        for key, value in (("minimumReferenceDistanceBlocks", 999),
                           ("sampledMinimumReferenceDistanceSquared", 999**2)):
            with self.subTest(key=key):
                self.assertEqual("lush_cave_ruins_natural_generation_not_proven", self.receipt(GOOD|{key:value}))

if __name__=="__main__": unittest.main(testRunner=unittest.TextTestRunner(stream=__import__("sys").stdout))
