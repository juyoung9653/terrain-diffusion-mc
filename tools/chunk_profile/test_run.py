"""Focused checks for TD override identity validation; no client or fixture launch."""
from __future__ import annotations

import importlib.util
import argparse
import contextlib
import gzip
import json
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


class JavaWorkloadTest(unittest.TestCase):
    def test_only_version_queries_are_ignored(self):
        class Process:
            info = {'name': 'java.exe'}
            def __init__(self, args): self.args = args
            def cmdline(self):
                if self.args is None: raise RUN.psutil.AccessDenied()
                return self.args
        for args, blocking in [(['java', '-version'], False), (['java', '--version'], False),
                               (['java', '-jar', 'server.jar', '--version'], True),
                               ([], True), (None, True)]:
            with self.subTest(args=args), patch.object(RUN.psutil, 'process_iter', return_value=[Process(args)]):
                self.assertEqual(RUN.active_java_workload(), blocking)


class StreamsCacheAndGenerationTraceTest(unittest.TestCase):
    def setUp(self):
        root = ROOT / 'build/chunk-profile'
        root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='test-streams-cache-', dir=root)
        self.path = Path(self.temp.name)

    def tearDown(self):
        RUN.manifest_root = None
        self.temp.cleanup()

    def test_generation_trace_sites_are_bounded_coordinate_pairs(self):
        self.assertEqual(RUN._generation_trace_sites('582,254;-3,-4'), [(582, 254), (-3, -4)])
        for value in ('', '1,2;', 'one,2', ';'.join(f'{n},0' for n in range(9))):
            with self.assertRaisesRegex(RuntimeError, 'generation-trace-sites'):
                RUN._generation_trace_sites(value)

    def test_copy_streams_cache_hashes_each_staged_file_and_detects_mutation(self):
        source = self.path / 'source'; output = self.path / 'target'
        output_world = output / 'saves/chunk-profile'
        cache = source / 'streamsreflowing/minecraft_overworld'
        cache.mkdir(parents=True); output_world.mkdir(parents=True)
        (cache / 'r.1.0.srn').write_bytes(b'route')
        (cache / 't.1.0.srt').write_bytes(b'terrain')
        RUN.manifest_root = output
        files = {}
        receipt = RUN._copy_streams_cache(source, output_world, files)
        manifest = {'streams_cache': receipt}
        RUN._verify_staged_streams_cache(output, manifest)
        self.assertIn('saves/chunk-profile/streamsreflowing/minecraft_overworld/r.1.0.srn', files)
        (output / 'saves/chunk-profile/streamsreflowing/minecraft_overworld/r.1.0.srn').write_bytes(b'changed')
        with self.assertRaisesRegex(RuntimeError, 'copied Streams cache changed'):
            RUN._verify_staged_streams_cache(output, manifest)


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


class FastNoiseAuditReceiptTest(unittest.TestCase):
    def receipt(self, *, restart=False, delivery=True, chunk=True):
        value = {
            'fastnoiseBiomeAudit': {'status': 'passed',
                                     'cardinalities': [1, 2, 3, 4, 7, 8, 9, 10, 16, 17, 32, 33, 64,
                                                       33, 32, 17, 16, 10, 9, 8, 7, 4, 3, 2, 1],
                                     **({'mode': 'saved-chunk-reload'} if restart else {})},
            'fastnoiseClientDelivery': ({'status': 'passed', 'actualClientPacket': True}
                                         if delivery else {'status': 'failed'}),
            'saveFlushed': True,
            'phases': [{'chunks': [{'fastnoiseBiomeAudit': {'status': 'passed', 'biomeFingerprint': 'abc',
                                                             'maxBiomeCardinality': 10}}] if chunk else []}],
        }
        if restart:
            value['verifyRestart'] = True
        return value

    def test_fastnoise_receipt_requires_root_delivery_and_every_chunk(self):
        self.assertTrue(RUN._audit_passed(self.receipt()))
        self.assertFalse(RUN._audit_passed(self.receipt(delivery=False)))
        self.assertFalse(RUN._audit_passed(self.receipt(chunk=False)))

    def test_restart_receipt_requires_saved_reload_mode(self):
        receipt = self.receipt(restart=True)
        receipt['fastnoiseBiomeAudit'].pop('cardinalities')
        self.assertTrue(RUN._audit_passed(receipt))
        receipt = self.receipt(restart=True)
        receipt['fastnoiseBiomeAudit'].pop('mode')
        self.assertFalse(RUN._audit_passed(receipt))

    def test_fastnoise_receipt_requires_saved_delivery_and_cardinality_boundaries(self):
        receipt = self.receipt()
        receipt['saveFlushed'] = False
        self.assertFalse(RUN._audit_passed(receipt))
        receipt = self.receipt()
        receipt['fastnoiseBiomeAudit']['cardinalities'] = [1, 2, 3]
        self.assertFalse(RUN._audit_passed(receipt))
        receipt = self.receipt()
        receipt['phases'][0]['chunks'][0]['fastnoiseBiomeAudit']['maxBiomeCardinality'] = 8
        self.assertFalse(RUN._audit_passed(receipt))

    def test_saved_region_header_is_required(self):
        fixture_root = ROOT / 'build/chunk-profile'
        fixture_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='test-restart-region-', dir=fixture_root) as raw:
            world = Path(raw)
            region = world / 'region'
            region.mkdir()
            path = region / 'r.0.0.mca'
            path.write_bytes(b'\0' * 4096)
            self.assertFalse(RUN._saved_chunk_headers_present(world, [(0, 0)]))
            payload = bytearray(path.read_bytes())
            payload[:3] = b'\0\0\x02'
            path.write_bytes(payload)
            self.assertTrue(RUN._saved_chunk_headers_present(world, [(0, 0)]))

    def test_restart_mods_must_match_initial_manifest_and_diagnostic(self):
        fixture_root = ROOT / 'build/chunk-profile'
        fixture_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='test-restart-mods-', dir=fixture_root) as raw:
            target = Path(raw)
            mods = target / 'mods'
            mods.mkdir()
            original = mods / 'zfastnoise.jar'
            original.write_bytes(b'original-fastnoise')
            diagnostic = mods / 'chunk-profile.jar'
            diagnostic.write_bytes(b'diagnostic')
            digest = RUN.launch.sha256(diagnostic)
            manifest = {'files': {'mods/zfastnoise.jar': {'sha256': RUN.launch.sha256(original)}},
                        'input_overrides': {}}
            RUN._verify_initial_mods(target, manifest, digest)
            original.write_bytes(b'swapped-fastnoise')
            with self.assertRaisesRegex(RuntimeError, 'initial manifest'):
                RUN._verify_initial_mods(target, manifest, digest)

    def test_restart_snapshot_must_preexist_and_match(self):
        fixture_root = ROOT / 'build/chunk-profile'
        fixture_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='test-restart-snapshot-', dir=fixture_root) as raw:
            target = Path(raw)
            (target / 'config').mkdir()
            (target / 'config/settings').write_bytes(b'first')
            (target / 'mods').mkdir()
            (target / 'mods/diagnostic.jar').write_bytes(b'diagnostic')
            snapshot = RUN._freeze_restart_inputs(target)
            RUN._verify_restart_inputs(target, snapshot)
            (target / 'config/settings').write_bytes(b'changed-after-first-run')
            with self.assertRaisesRegex(RuntimeError, 'config'):
                RUN._verify_restart_inputs(target, snapshot)

    def test_restart_snapshot_accepts_generated_streams_cache_then_freezes_it(self):
        fixture_root = ROOT / 'build/chunk-profile'
        fixture_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='test-restart-streams-cache-', dir=fixture_root) as raw:
            target = Path(raw)
            cache = target / 'saves/chunk-profile/streamsreflowing/minecraft_overworld'
            cache.mkdir(parents=True)
            # This is the cache state after the first lifecycle, including a
            # plate Streams legitimately generated after the boot hash check.
            (cache / 'r.1.0.srn').write_bytes(b'generated-route')
            (target / 'config').mkdir()
            (target / 'mods').mkdir()
            snapshot = RUN._freeze_restart_inputs(target)
            RUN._verify_restart_inputs(target, snapshot)
            (cache / 'r.1.0.srn').write_bytes(b'changed-after-freeze')
            with self.assertRaisesRegex(RuntimeError, 'saves/chunk-profile'):
                RUN._verify_restart_inputs(target, snapshot)


class WetlandSpatialAuditReceiptTest(unittest.TestCase):
    def receipt(self, target):
        csv = target / 'wetland-spatial.csv'
        csv.write_text('x,z,wetland\n0,0,false\n', encoding='utf-8')
        return {'wetlandSpatialAudit': {'status': 'passed', 'samples': 262144,
                                        'file': 'wetland-spatial.csv',
                                        'csvSha256': RUN.launch.sha256(csv)}}

    def test_requires_minimum_samples_relative_csv_and_hash(self):
        fixture_root = ROOT / 'build/chunk-profile'
        fixture_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='test-wetland-spatial-', dir=fixture_root) as raw:
            target = Path(raw)
            receipt = self.receipt(target)
            self.assertTrue(RUN._wetland_spatial_audit_passed(receipt, target))
            receipt['wetlandSpatialAudit']['samples'] = 262143
            self.assertFalse(RUN._wetland_spatial_audit_passed(receipt, target))
            receipt = self.receipt(target)
            (target / 'wetland-spatial.csv').write_text('altered', encoding='utf-8')
            self.assertFalse(RUN._wetland_spatial_audit_passed(receipt, target))

    def test_cli_passes_opt_in_flag_to_runner(self):
        with patch.object(RUN, 'exclusive_lock', return_value=contextlib.nullcontext()), \
             patch.object(RUN, 'run') as run, \
             patch.object(RUN.sys, 'argv', ['run.py', 'run', 'fixture', '--wetland-spatial-audit',
                                            '--wetland-sites=-2400,-2560;-1152,-2304;-1664,-1952;320,-1696']):
            RUN.main()
        self.assertTrue(run.call_args.args[0].wetland_spatial_audit)
        self.assertEqual(run.call_args.args[0].wetland_sites,
                         '-2400,-2560;-1152,-2304;-1664,-1952;320,-1696')
        self.assertFalse(run.call_args.kwargs['restart'])

    def test_wetland_sites_require_exactly_four_coordinate_pairs(self):
        sites = '-2400,-2560;-1152,-2304;-1664,-1952;320,-1696'
        self.assertEqual(RUN._wetland_sites(sites), sites)
        for invalid in ('-2400,-2560', '-2400,-2560;-1152,-2304;-1664,-1952',
                        '-2400,-2560;-1152,-2304;-1664,-1952;320,-1696;1,1',
                        '-2400,-2560;bad;-1664,-1952;320,-1696'):
            with self.assertRaisesRegex(RuntimeError, 'exactly four'):
                RUN._wetland_sites(invalid)


class WetlandWorkflowTest(unittest.TestCase):
    def setUp(self):
        fixture_root = ROOT / 'build/chunk-profile'
        fixture_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='test-wetland-workflow-', dir=fixture_root)
        self.root = Path(self.temp.name)
        self.args = argparse.Namespace(name='candidate', wetland_spatial_audit=True,
            wetland_sites=None, wetland_seed=None, wetland_baseline=None,
            fastnoise_biome_audit=False, side=1, chunk_x=0, chunk_z=0, regions='0,0',
            request_telemetry=False, streams_settle_seconds=0, streams_ground_marine=None,
            streams_batch_sampling=None, streams_ground_marine_audit=False, settle=45,
            phase_timeout=900)
        self.manifest = {'complete': True, 'seed': -42, 'scale': 1, 'versions': {'neoforge': 'test'},
                         'files': {}, 'expected_active_mod_jars': 0,
                         'terrain_diffusion_artifact': {'source_filename': 'td.jar'},
                         'fresh_level_dat_sha256': 'initial-world'}

    def tearDown(self):
        self.temp.cleanup()

    def prepare(self):
        target = self.root / self.args.name
        target.mkdir()
        (target / 'mods').mkdir()
        RUN.save(target / 'input-manifest.json', self.manifest)
        return target

    def baseline(self):
        baseline = self.root / 'baseline'
        baseline.mkdir()
        self.manifest['files'] = {'mods/td.jar': {'sha256': 'old-td'},
                                  'config/settings': {'sha256': 'same-settings'}}
        RUN.save(baseline / 'input-manifest.json', self.manifest)
        process = vars(self.args).copy()
        process.update(settle_seconds=45, phase_timeout_seconds=900)
        RUN.save(baseline / 'process.json', process)
        RUN.save(baseline / 'launch-result.json', {'exit_code': 0, 'timed_out': False, 'passed': True})
        csv = baseline / 'wetland-spatial.csv'
        csv.write_text('retained evidence', encoding='utf-8')
        self.receipt = {'status': 'success', 'wetlandSpatialAudit': {
            'status': 'passed', 'seed': -42, 'samples': 262144, 'windows': 4,
            'sideBlocks': 1024, 'stepBlocks': 4, 'file': csv.name, 'csvSha256': RUN.launch.sha256(csv),
            'sites': [{'name': name, 'center': [index * 1024, -512]} for index, name in
                      enumerate(('reed', 'mangrove', 'freshwater', 'riparian'))]}}
        RUN.save(baseline / 'receipt.json', self.receipt)
        self.args.wetland_baseline = baseline
        return baseline

    def test_manual_sites_require_matching_seed(self):
        self.args.wetland_sites = '0,0;4,4;8,8;12,12'
        with self.assertRaisesRegex(RuntimeError, 'requires --wetland-seed'):
            RUN._wetland_inputs(self.args, self.manifest, False)
        self.args.wetland_seed = 42
        with self.assertRaisesRegex(RuntimeError, 'seed differs'):
            RUN._wetland_inputs(self.args, self.manifest, False)
        self.args.wetland_seed = -42
        self.assertEqual(RUN._wetland_inputs(self.args, self.manifest, False)[0], self.args.wetland_sites)
        self.args.wetland_spatial_audit = False
        with self.assertRaisesRegex(RuntimeError, 'require --wetland-spatial-audit'):
            RUN._wetland_inputs(self.args, self.manifest, False)

    def test_baseline_inherits_sites_and_allows_td_implementation_change(self):
        baseline = self.baseline()
        self.manifest['files']['mods/td.jar']['sha256'] = 'new-td'
        sites, reference = RUN._wetland_inputs(self.args, self.manifest, False)
        self.assertEqual(sites, '0,-512;1024,-512;2048,-512;3072,-512')
        self.assertEqual(reference, str(baseline.resolve()))

    def test_mismatched_baseline_stops_entrypoint_before_build_or_game(self):
        self.baseline()
        self.manifest['seed'] = 99
        self.prepare()
        with patch.object(RUN, 'WORK', self.root), patch.object(RUN, 'build') as build, \
             patch.object(RUN.subprocess, 'Popen') as game, patch.object(RUN, '_audit_preflight') as preflight:
            with self.assertRaisesRegex(RuntimeError, 'baseline seed differs'):
                RUN.run(self.args)
        preflight.assert_not_called()
        build.assert_not_called()
        game.assert_not_called()

    def test_baseline_rejects_settings_order_failed_evidence_and_unknown_launch_settings(self):
        baseline = self.baseline()
        self.manifest['files']['config/settings']['sha256'] = 'changed'
        with self.assertRaisesRegex(RuntimeError, 'mods/settings/models/initial caches differ'):
            RUN._wetland_inputs(self.args, self.manifest, False)
        self.manifest['files']['config/settings']['sha256'] = 'same-settings'
        self.args.regions = '1,1;0,0'
        with self.assertRaisesRegex(RuntimeError, 'launch setting differs: regions'):
            RUN._wetland_inputs(self.args, self.manifest, False)
        self.args.regions = '0,0'
        result = {'exit_code': 0, 'timed_out': False, 'passed': False}
        RUN.save(baseline / 'launch-result.json', result)
        with self.assertRaisesRegex(RuntimeError, 'successful retained spatial evidence'):
            RUN._wetland_inputs(self.args, self.manifest, False)
        result['passed'] = True
        RUN.save(baseline / 'launch-result.json', result)
        process = json.loads((baseline / 'process.json').read_text(encoding='utf-8'))
        process.pop('settle_seconds')
        RUN.save(baseline / 'process.json', process)
        with self.assertRaisesRegex(RuntimeError, 'lacks matching launch setting: settle_seconds'):
            RUN._wetland_inputs(self.args, self.manifest, False)

    def test_failed_source_check_stops_entrypoint_before_build_or_game(self):
        target = self.prepare()
        with patch.object(RUN, 'WORK', self.root), patch.object(RUN.psutil, 'process_iter', return_value=[]), \
             patch.object(RUN.subprocess, 'run', return_value=argparse.Namespace(returncode=1)) as check, \
             patch.object(RUN, 'build') as build, patch.object(RUN.subprocess, 'Popen') as game:
            with self.assertRaisesRegex(RuntimeError, 'Audit preflight failed before game launch'):
                RUN.run(self.args)
        self.assertEqual(Path(check.call_args.args[0][-1]).name, 'test_macro_context.py')
        check.assert_called_once()
        build.assert_not_called()
        game.assert_not_called()
        self.assertEqual(json.loads((target / 'audit-preflight.json').read_text())['status'], 'failed')

    def test_unselected_audits_do_not_run_preflight_checks(self):
        with patch.object(RUN.subprocess, 'run') as check:
            RUN._audit_preflight(self.root, False, False)
        check.assert_not_called()

    def test_selected_preflights_are_recorded_in_order(self):
        with patch.object(RUN.subprocess, 'run', return_value=argparse.Namespace(returncode=0)) as check:
            RUN._audit_preflight(self.root, True, True)
        self.assertEqual([Path(call.args[0][-1]).name for call in check.call_args_list],
                         ['test_fastnoise_gate.py', 'test_macro_context.py', 'test_analyze_wetland_spatial.py'])
        self.assertEqual(json.loads((self.root / 'audit-preflight.json').read_text())['status'], 'passed')

    def test_csv_hash_and_claimed_count_do_not_certify_measured_geometry(self):
        self.baseline()
        target = self.prepare()
        columns = 'window,x,z,biomeId,elevation,wetness,marineInfluence,reedWeight,swampWeight,mangroveWeight,riparianWeight\n'
        rows = ''.join(f'{name},{x},{z},21,10,0.5,0,0,1,0,0\n'
                       for name in ('reed', 'mangrove', 'freshwater', 'riparian')
                       for z in (0, 4) for x in (0, 4, 8))
        csv = target / 'wetland-spatial.csv'
        csv.write_text(columns + rows, encoding='utf-8')
        self.receipt['wetlandSpatialAudit']['csvSha256'] = RUN.launch.sha256(csv)
        with self.assertRaisesRegex(RuntimeError, 'CSV geometry differs'):
            RUN._wetland_report(self.receipt, target, self.manifest, None, None)

class NativeBiomePlanPreflightTest(unittest.TestCase):
    def plan(self):
        return {
            'schema': 1,
            'windows': [{'id': 'one', 'minX': -32, 'maxX': -17, 'minZ': 16, 'maxZ': 31}],
            'regressionSites': [{'id': 'reported', 'x': -20, 'z': 20}],
            'explicitSites': [{'id': 'detail', 'x': -19, 'z': 21}],
            'inspectionRequests': [{'id': 'bank', 'x': -20, 'z': 20, 'kind': 'bank'}],
            'generationTraceSites': [{'x': -20, 'z': 20}],
            'maxSites': 16,
        }

    def test_accepts_native_whole_chunk_plan(self):
        RUN._validate_native_biome_plan(self.plan())

    def test_rejects_java_invalid_window_before_launch(self):
        plan = self.plan(); plan['windows'][0]['maxZ'] = 30
        with self.assertRaisesRegex(RuntimeError, 'inclusive whole chunks'):
            RUN._validate_native_biome_plan(plan)

    def test_rejects_duplicate_or_out_of_window_evidence(self):
        plan = self.plan(); plan['explicitSites'][0]['id'] = 'reported'
        with self.assertRaisesRegex(RuntimeError, 'unique and inside'):
            RUN._validate_native_biome_plan(plan)
        plan = self.plan(); plan['inspectionRequests'][0]['x'] = 0
        with self.assertRaisesRegex(RuntimeError, 'unique and inside'):
            RUN._validate_native_biome_plan(plan)


if __name__ == "__main__":
    unittest.main()
