"""Synthetic contract checks for bounded probe input storage; no game launch."""
from __future__ import annotations
import json, hashlib, sys, tempfile, time, unittest
from unittest import mock
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from probe_storage import maintain, restore, MANAGED, PIN, GUARD
from build_evidence import PROBE_RECEIPT, sha256

class StorageTest(unittest.TestCase):

    def setUp(self):
        scratch = Path(__file__).resolve().parents[1] / 'build'
        scratch.mkdir(exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'build/structure-density').mkdir(parents=True)

    def make_run(self, name, *, good=True, models=('base_model.onnx', 'coarse_model.onnx')):
        r = self.root / 'build/structure-density' / name
        (r / 'world').mkdir(parents=True)
        (r / 'terrain-diffusion-models').mkdir()
        for n in models:
            (r / 'terrain-diffusion-models' / n).write_bytes((n + '-' + name).encode())
        hashes = {'terrain-diffusion-models/' + n: sha256(r / 'terrain-diffusion-models' / n) for n in models}
        (r / 'input-manifest.json').write_text(json.dumps({'sha256': hashes}))
        receipt = r / 'world/density-audit.json'
        receipt.write_text('{"success":true}')
        evidence = {'schema': 1, 'kind': 'probe-validation', 'success': good, 'processExit': 0 if good else 1, 'probeStatus': 'success' if good else 'not_success', 'receipt': 'world/density-audit.json', 'receiptSha256': sha256(receipt), 'inputManifestSha256': sha256(r / 'input-manifest.json')}
        (r / PROBE_RECEIPT).write_text(json.dumps(evidence))
        return r

    def test_keeps_two_and_archives_older(self):
        a, b, c = [self.make_run(x) for x in 'abc']
        for r in (a, b, c):
            time.sleep(0.002)
            maintain(self.root, 'structure-density', [r], keep_latest=2)
        self.assertTrue((a / GUARD).exists())
        self.assertFalse((b / GUARD).exists())
        self.assertFalse((c / GUARD).exists())
        self.assertFalse((a / 'terrain-diffusion-models/base_model.onnx').exists())
        self.assertTrue((a / 'world/density-audit.json').is_file())

    def test_pin_and_failed_never_archive_or_register(self):
        a, b, c = [self.make_run(x) for x in 'abc']
        bad = self.make_run('bad', good=False)
        maintain(self.root, 'structure-density', [a], keep_latest=0)
        maintain(self.root, 'structure-density', [b], keep_inputs=True, keep_latest=0)
        maintain(self.root, 'structure-density', [c], keep_latest=0)
        result = maintain(self.root, 'structure-density', [bad], keep_latest=0)
        self.assertTrue((b / PIN).exists())
        self.assertFalse((b / GUARD).exists())
        self.assertTrue((a / GUARD).exists())
        self.assertTrue((c / GUARD).exists())
        self.assertFalse((bad / MANAGED).exists())
        self.assertTrue(result['skipped'])

    def test_restore_is_independent_and_refuses_changed_target(self):
        a = self.make_run('a')
        maintain(self.root, 'structure-density', [a], keep_latest=0)
        marker = json.loads((a / GUARD).read_text())
        item = marker['models'][0]
        obj = self.root / 'build/probe-inputs/models' / (item['sha256'] + '.onnx')
        self.assertEqual(restore(self.root, a)['restored'], 2)
        target = self.root / item['path']
        self.assertEqual(sha256(target), sha256(obj))
        self.assertNotEqual(target.stat().st_ino, obj.stat().st_ino)
        (a / GUARD).write_text(json.dumps(marker))
        target.unlink()
        self.assertEqual(restore(self.root, a)['restored'], 1)
        (a / GUARD).write_text(json.dumps(marker))
        target.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'changed restore target'):
            restore(self.root, a)

    def test_changed_old_input_and_corrupt_master_are_not_deleted(self):
        a = self.make_run('a')
        maintain(self.root, 'structure-density', [a], keep_latest=2)
        target = a / 'terrain-diffusion-models/base_model.onnx'
        target.write_bytes(b'user edit')
        result = maintain(self.root, 'structure-density', [], keep_latest=0)
        self.assertTrue(target.exists())
        self.assertIn('evidence_or_inputs_changed', [x['reason'] for x in result['skipped']])
        b = self.make_run('b')
        maintain(self.root, 'structure-density', [b], keep_latest=0)
        restore(self.root, b)
        rec = json.loads((b / MANAGED).read_text())
        obj = self.root / 'build/probe-inputs/models' / (rec['models'][0]['sha256'] + '.onnx')
        obj.write_bytes(b'bad')
        with self.assertRaisesRegex(ValueError, 'corrupt retained model'):
            maintain(self.root, 'structure-density', [], keep_latest=0)
        self.assertTrue((b / 'terrain-diffusion-models/base_model.onnx').exists())

    def test_cache_matching_only_and_legacy_protection(self):
        a = self.make_run('a')
        models = a / 'terrain-diffusion-models'
        cache = models / 'onnx-cache'
        cache.mkdir()
        source = sha256(models / 'base_model.onnx')
        (cache / f'base-1.20.0-{source[:16]}.onnx').write_bytes(b'cache')
        (cache / 'unexpected.onnx').write_bytes(b'keep')
        (self.root / 'build/cleanup-old').mkdir()
        (self.root / 'build/cleanup-old/plan.json').write_text(json.dumps({'protected_fixtures': ['build/structure-density/a']}))
        result = maintain(self.root, 'structure-density', [a], keep_latest=0)
        self.assertFalse((a / MANAGED).exists())
        self.assertEqual(result['skipped'][0]['reason'], 'legacy_protected')
        (self.root / 'build/cleanup-old/plan.json').write_text(json.dumps({'protected_fixtures': []}))
        maintain(self.root, 'structure-density', [a], keep_latest=0)
        self.assertFalse((cache / f'base-1.20.0-{source[:16]}.onnx').exists())
        self.assertTrue((cache / 'unexpected.onnx').exists())

    def test_stale_evidence_and_bad_paths_rejected(self):
        a = self.make_run('a')
        data = json.loads((a / PROBE_RECEIPT).read_text())
        data['receiptSha256'] = 'bad'
        (a / PROBE_RECEIPT).write_text(json.dumps(data))
        self.assertTrue(maintain(self.root, 'structure-density', [a])['skipped'])
        self.assertFalse((a / MANAGED).exists())
        with self.assertRaises(ValueError):
            maintain(self.root, 'nope', [a])

    def test_linked_run_is_refused_before_storage(self):
        a = self.make_run('a')
        import probe_storage
        with mock.patch.object(probe_storage, '_link', side_effect=lambda p: p == a):
            with self.assertRaisesRegex(ValueError, 'linked path'):
                maintain(self.root, 'structure-density', [a])
if __name__ == '__main__':
    unittest.main()
