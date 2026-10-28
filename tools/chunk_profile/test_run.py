"""Focused checks for TD override identity validation; no client or fixture launch."""
from __future__ import annotations

import importlib.util
import gzip
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("chunk_profile_run", ROOT / "tools/chunk_profile/run.py")
RUN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN)
ANALYZE_SPEC = importlib.util.spec_from_file_location("chunk_profile_analyze", ROOT / "tools/chunk_profile/analyze.py")
ANALYZE = importlib.util.module_from_spec(ANALYZE_SPEC)
ANALYZE_SPEC.loader.exec_module(ANALYZE)


class TdJarIdentityTest(unittest.TestCase):
    def setUp(self):
        root = ROOT / "build/chunk-profile"
        root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="test-td-identity-", dir=root)
        self.path = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def jar(self, name: str, toml: str | None) -> Path:
        path = self.path / name
        with zipfile.ZipFile(path, "w") as archive:
            if toml is not None:
                archive.writestr("META-INF/neoforge.mods.toml", toml)
        return path

    def test_accepts_declared_td_mod_and_returns_hash(self):
        path = self.jar("candidate.jar", '[[mods]]\nmodId="terrain_diffusion_mc"\n')
        value = RUN.td_identity(path)
        self.assertEqual(value["path"], str(path.resolve()))
        self.assertEqual(value["bytes"], path.stat().st_size)
        self.assertEqual(value["sha256"], RUN.launch.sha256(path))

    def test_rejects_missing_path_and_non_zip(self):
        with self.assertRaises(RuntimeError): RUN.td_identity(self.path / "missing.jar")
        plain = self.path / "plain.jar"; plain.write_text("not a zip", encoding="utf-8")
        with self.assertRaises(RuntimeError): RUN.td_identity(plain)

    def test_rejects_missing_metadata_and_wrong_mod(self):
        with self.assertRaises(RuntimeError): RUN.td_identity(self.jar("metadata-missing.jar", None))
        with self.assertRaises(RuntimeError): RUN.td_identity(self.jar("wrong-mod.jar", '[[mods]]\nmodId="not_td"\n'))

    def test_streams_ablation_identity_requires_filename_and_declared_mod(self):
        good = self.jar("StreamsReflowing-1.21.1-neoforge-2.15.2.jar", '[[mods]]\nmodId = "streamsreflowing" # mandatory\n')
        identity = RUN.streams_identity(good)
        self.assertEqual(identity['source_filename'], good.name)
        self.assertEqual(identity['mod_id'], 'streamsreflowing')
        wrong_name = self.jar("other.jar", '[[mods]]\nmodId="streamsreflowing"\n')
        with self.assertRaisesRegex(RuntimeError, "filename"):
            RUN.streams_identity(wrong_name)
        wrong_id = self.jar("StreamsReflowing-other.jar", '[[mods]]\nmodId="other"\n')
        with self.assertRaisesRegex(RuntimeError, "modId"):
            RUN.streams_identity(wrong_id)

    def test_explicit_thread_dump_request_retains_jcmd_output(self):
        target = self.path / "fixture"
        target.mkdir()
        with patch.object(RUN.subprocess, "run",
                          return_value=RUN.subprocess.CompletedProcess([], 7, b"thread output", b"attach error")) as invoke:
            record = RUN.requested_thread_dump(target, 1234, 456789)
        self.assertEqual(record['exit_code'], 7)
        self.assertEqual(record['file'], 'threads-request-456789.txt')
        self.assertEqual((target / record['file']).read_bytes(), b"thread outputattach error")
        self.assertEqual(invoke.call_args.args[0][-2:], ['1234', 'Thread.print'])
        self.assertEqual(invoke.call_args.kwargs['timeout'], 30)


class StreamsAblationMetadataTest(unittest.TestCase):
    def setUp(self):
        root = ROOT / "build/chunk-profile"
        root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="test-streams-ablation-", dir=root)
        self.path = Path(self.temp.name) / "terrain_diffusion_natural_rivers.dat"

    def tearDown(self):
        self.temp.cleanup()

    def test_disables_only_enabled_byte_and_preserves_profile_identity(self):
        data = bytearray()
        data += b'\x0a\x00\x00'  # root compound with empty name
        data += b'\x0a\x00\x04data'
        data += b'\x01\x00\x07enabled\x01'
        data += b'\x03\x00\x07version' + struct.pack('>i', 8)
        data += b'\x04\x00\x04seed' + struct.pack('>q', 123456789)
        data += b'\x03\x00\x05scale' + struct.pack('>i', 2)
        data += b'\x00\x00'
        self.path.write_bytes(gzip.compress(bytes(data), mtime=0))
        result = RUN.disable_natural_rivers(self.path)
        fields = RUN.helper().nbt_fields(gzip.decompress(self.path.read_bytes()))
        self.assertEqual(fields[('data', 'enabled')][3], 0)
        self.assertEqual(fields[('data', 'version')][3], 8)
        self.assertEqual(fields[('data', 'seed')][3], 123456789)
        self.assertEqual(fields[('data', 'scale')][3], 2)
        self.assertEqual(result['previous_enabled'], 1)
        self.assertEqual(result['enabled'], 0)


class DecoderPrecisionFixtureTest(unittest.TestCase):
    def setUp(self):
        root = ROOT / "build/chunk-profile"
        root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="test-decoder-fp16-", dir=root)
        self.path = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_rejects_wrong_size_and_hash(self):
        wrong_size = self.path / "wrong-size.onnx"
        wrong_size.write_bytes(b"not a decoder")
        with self.assertRaisesRegex(RuntimeError, "byte length"):
            RUN.fp16_decoder_identity(wrong_size)
        wrong_hash = self.path / "wrong-hash.onnx"
        with wrong_hash.open("wb") as stream:
            stream.truncate(RUN.FP16_DECODER_BYTES)
        with self.assertRaisesRegex(RuntimeError, "SHA-256"):
            RUN.fp16_decoder_identity(wrong_hash)

    def test_records_pinned_candidate_provenance(self):
        candidate = self.path / "candidate.onnx"
        with candidate.open("wb") as stream:
            stream.truncate(RUN.FP16_DECODER_BYTES)
        with patch.object(RUN.launch, "sha256", return_value=RUN.FP16_DECODER_SHA256):
            identity = RUN.fp16_decoder_identity(candidate)
        self.assertEqual(identity, {
            "path": str(candidate.resolve()),
            "sha256": RUN.FP16_DECODER_SHA256,
            "bytes": RUN.FP16_DECODER_BYTES,
            "validated_sha256": RUN.FP16_DECODER_SHA256,
        })

    def test_sets_clone_property_and_rejects_duplicate_setting(self):
        config = self.path / "terrain-diffusion-mc.properties"
        config.write_text("inference.device=gpu\ninference.decoder_precision=fp32\n", encoding="utf-8")
        RUN.set_property(config, "inference.decoder_precision", "fp16")
        self.assertEqual(config.read_text(encoding="utf-8"),
                         "inference.device=gpu\ninference.decoder_precision=fp16\n")
        RUN.set_property(config, "inference.new_setting", "enabled")
        self.assertTrue(config.read_text(encoding="utf-8").endswith("inference.new_setting=enabled\n"))
        config.write_text("inference.decoder_precision=fp32\ninference.decoder_precision=fp32\n", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "at most one"):
            RUN.set_property(config, "inference.decoder_precision", "fp16")

    def test_reads_effective_precision_and_rejects_invalid_duplicate_config(self):
        config = self.path / "terrain-diffusion-mc.properties"
        config.write_text("# inference.decoder_precision=fp16\ninference.decoder_precision = fp16\n", encoding="utf-8")
        self.assertEqual(RUN.property_value(config, "inference.decoder_precision"), "fp16")
        config.write_text("inference.decoder_precision=fp32\ninference.decoder_precision=fp16\n", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "at most one"):
            RUN.property_value(config, "inference.decoder_precision")


class RequestTelemetryAnalysisTest(unittest.TestCase):
    def test_telemetry_delta_and_origin_work_are_compact(self):
        before = {'requestTelemetry': {'schemaVersion': 2, 'enabled': True, 'fullCompleted': {'count': 1, 'computedWindows': 2, 'executionNanos': 3},
                  'groundCompleted': {'count': 0, 'computedWindows': 0, 'executionNanos': 0}, 'groundToFullUpgrade': {'count': 0, 'computedWindows': 0, 'executionNanos': 0},
                  'bothCompletedOverlap': {'count': 0, 'computedWindows': 0, 'executionNanos': 0}, 'sameKindRepeat': {'count': 0, 'computedWindows': 0, 'executionNanos': 0},
                  'postEvictionRecompute': {'count': 0, 'computedWindows': 0, 'executionNanos': 0}, 'queuedGroundReusedFull': 0, 'historyTrackedKeys': 1, 'historyDrops': 0, 'historyComplete': True,
                  'origins': [{'origin': 'STREAMS_DEMAND', 'full': {'count': 1}, 'ground': {'count': 0}}]}}
        after = {'requestTelemetry': {**before['requestTelemetry'], 'fullCompleted': {'count': 3, 'computedWindows': 5, 'executionNanos': 9},
                 'queuedGroundReusedFull': 2, 'origins': [{'origin': 'STREAMS_DEMAND', 'full': {'count': 3}, 'ground': {'count': 1}}], 'repeatExamples': ['x']}}
        value = ANALYZE.request_telemetry(before, after)
        self.assertEqual(value['work_totals_delta']['fullCompleted']['count'], 2)
        self.assertEqual(value['scalar_delta']['queuedGroundReusedFull'], 2)
        self.assertEqual(value['origin_totals_delta']['STREAMS_DEMAND']['ground']['count'], 1)
        self.assertEqual(value['examples_after']['repeatExamples'], ['x'])
        self.assertEqual(value['schema_version'], 2)
        self.assertIn('postEvictionRecompute', {row['field'] for row in value['unsupported_fields']})

    def test_v1_suppresses_invalid_overlap_and_queued_reuse(self):
        base = {'enabled': True, 'fullCompleted': {'count': 0}, 'groundCompleted': {'count': 0},
                'groundToFullUpgrade': {'count': 0}, 'bothCompletedOverlap': {'count': 1},
                'sameKindRepeat': {'count': 0}, 'postEvictionRecompute': {'count': 0},
                'queuedGroundReusedFull': 2, 'historyTrackedKeys': 1, 'historyDrops': 0,
                'historyComplete': True, 'origins': [], 'overlapExamples': ['old']}
        after = {**base, 'bothCompletedOverlap': {'count': 9}, 'queuedGroundReusedFull': 20,
                 'sameKindRepeat': {'count': 2}, 'repeatExamples': ['repeat']}
        value = ANALYZE.request_telemetry({'requestTelemetry': base}, {'requestTelemetry': after})
        self.assertEqual(value['schema_version'], 1)
        self.assertNotIn('bothCompletedOverlap', value['work_totals_delta'])
        self.assertNotIn('queuedGroundReusedFull', value['scalar_delta'])
        self.assertNotIn('overlapExamples', value['examples_after'])
        self.assertEqual({row['field'] for row in value['unsupported_fields']},
                         {'bothCompletedOverlap', 'queuedGroundReusedFull', 'overlapExamples',
                          'postEvictionRecompute', 'postEvictionExamples'})

    def test_telemetry_absent_stays_compatible(self):
        self.assertIsNone(ANALYZE.request_telemetry({}, {}))

    def test_stage_window_delta_and_history_drops(self):
        before = {'windowTelemetry': {'decoder': {'computedWindows': 2, 'recomputedWindows': 0,
                   'trackedKeys': 2, 'historyDrops': 0}}}
        after = {'windowTelemetry': {'decoder': {'computedWindows': 5, 'recomputedWindows': 1,
                  'trackedKeys': 4, 'historyDrops': 2}, 'coarse': {'computedWindows': 1,
                  'recomputedWindows': 0, 'trackedKeys': 1, 'historyDrops': 0}}}
        value = ANALYZE.stage_window_telemetry(before, after)
        self.assertEqual(value['decoder']['delta']['computedWindows'], 3)
        self.assertEqual(value['decoder']['delta']['recomputedWindows'], 1)
        self.assertEqual(value['decoder']['history_drops_before_after'], [0, 2])
        self.assertEqual(value['decoder']['history_complete_before_after'], [True, False])

    def test_stage_generation_change_invalidates_delta(self):
        before = {'windowTelemetry': {'decoder': {'computedWindows': 2, 'recomputedWindows': 0,
                   'trackedKeys': 2, 'historyDrops': 0, 'historyGeneration': 7}}}
        after = {'windowTelemetry': {'decoder': {'computedWindows': 5, 'recomputedWindows': 1,
                  'trackedKeys': 4, 'historyDrops': 0, 'historyGeneration': 8}}}
        value = ANALYZE.stage_window_telemetry(before, after)['decoder']
        self.assertFalse(value['delta_valid'])
        self.assertIsNone(value['delta'])
        self.assertEqual(value['history_complete_before_after'], [False, False])

    def test_stage_window_missing_stays_compatible(self):
        self.assertIsNone(ANALYZE.stage_window_telemetry({}, {}))


if __name__ == "__main__":
    unittest.main()
