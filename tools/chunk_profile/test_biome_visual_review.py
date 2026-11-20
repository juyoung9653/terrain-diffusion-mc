"""Focused contract checks for generic biome visual-review staging and evidence."""
from __future__ import annotations

import argparse
import importlib.util
import json
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('chunk_profile_run', ROOT / 'tools/chunk_profile/run.py')
RUN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN)


def plan(manifest, *, fingerprint=None, artifact=None):
    return {
        'schema': 1, 'reviewTask': 'test-biome-plan-staging',
        'seed': manifest['seed'], 'scale': manifest['scale'],
        'sourceFingerprint': fingerprint if fingerprint is not None else RUN.source_fingerprint(ROOT),
        'artifactSha256': artifact if artifact is not None else manifest['terrain_diffusion_artifact']['sha256'],
        'requiredBiomes': sorted({'terrain-diffusion-mc:ecology/freshwater_swamp'} | RUN.changed_biome_resource_ids(ROOT)),
        'windows': [{'id': 'sample', 'minX': 0, 'maxX': 15, 'minZ': 0, 'maxZ': 15}],
        'regressionSites': [{'id': 'reported', 'x': 1, 'z': 1}], 'maxSites': 16,
    }


def expect_error(callable_):
    try:
        callable_()
    except RuntimeError:
        return
    raise AssertionError('expected RuntimeError')


def main():
    # FULL futures must be requested off the server thread in bounded batches;
    # this is the scheduling rule shared with ChunkProfileClient's probe phase.
    review_source = (ROOT / 'tools/chunk_profile/java/com/terrain/chunkprofile/BiomeVisualReview.java').read_text(encoding='utf-8')
    assert 'private static final int PREFETCH = 8;' in review_source
    assert 'loadFullBatches(server, level, chunks' in review_source
    assert 'server.submit(() -> chunks.stream().map(pos -> level.getChunkSource().getChunkFuture' not in review_source
    assert 'future.get(remainingSeconds(deadline), TimeUnit.SECONDS)' in review_source
    manifest = {'seed': 42, 'scale': 1,
                'terrain_diffusion_artifact': {'sha256': 'a' * 64, 'source_filename': 'td.jar'}}
    scratch = ROOT / '.tmp'
    scratch.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=scratch) as temporary:
        root = Path(temporary); source = root / 'plan.json'; target = root / 'fixture'; target.mkdir()
        source.write_text(json.dumps(plan(manifest)), encoding='utf-8')
        args = argparse.Namespace(biome_review_plan=source)
        staged = RUN._biome_review_plan(args, manifest, target, False)
        staged['diagnostic_jar_sha256'] = 'd' * 64
        assert staged['path'].is_file() and staged['artifact_sha256'] == 'a' * 64
        # A stale plan cannot silently bind an old source checkout to this fixture.
        bad = root / 'bad.json'; bad.write_text(json.dumps(plan(manifest, fingerprint='stale')), encoding='utf-8')
        expect_error(lambda: RUN._biome_review_plan(argparse.Namespace(biome_review_plan=bad), manifest, root / 'other', False))
        # Scoped reviews capture declared report/control sites and only the
        # inspections named by the frozen criteria; malformed aliases or facts
        # fail before a client clone is launched.
        scoped = plan(manifest)
        facts = root / 'baseline-facts.json'
        facts.write_text(json.dumps({'sites': [{'id': 'reported', 'x': 1, 'z': 1}]}), encoding='utf-8')
        scoped['reviewScope'] = {
            'schema': 1, 'id': 'test-scope', 'roundLimit': 1,
            'criteria': [{'id': 'reported-fix', 'siteIds': ['reported'],
                          'description': 'reported coordinate', 'acceptance': 'repair it'}],
            'baseline': {'bundleSha256': 'b' * 64, 'artifactSha256': 'c' * 64,
                         'sourceFingerprint': 'd' * 64,
                         'facts': {'path': str(facts),
                                   'sha256': RUN.launch.sha256(facts)}}}
        scoped_path = root / 'scoped.json'; scoped_path.write_text(json.dumps(scoped), encoding='utf-8')
        scoped_target = root / 'scoped-fixture'; scoped_target.mkdir()
        RUN._biome_review_plan(argparse.Namespace(biome_review_plan=scoped_path), manifest, scoped_target, False)
        scoped['reviewScope']['criteria'][0]['siteIds'] = ['missing']
        scoped_path.write_text(json.dumps(scoped), encoding='utf-8')
        missing_target = root / 'missing-fixture'; missing_target.mkdir()
        expect_error(lambda: RUN._biome_review_plan(argparse.Namespace(biome_review_plan=scoped_path), manifest, missing_target, False))
        # Captured evidence requires all three distinct native viewpoints inside the fixture.
        images = []
        for view in ('aerial', 'side', 'detail'):
            path = target / f'{view}.png'; path.write_bytes(b'x' * 1025)
            images.append({'view': view, 'path': str(path)})
        receipt = {'biomeVisualReview': {'status': 'captured', 'sourceFingerprint': staged['source_fingerprint'],
                   'artifactSha256': staged['artifact_sha256'], 'coverageGaps': [],
                   'captureToolFingerprint': staged['capture_tool_fingerprint'],
                   'diagnosticJarSha256': staged['diagnostic_jar_sha256'],
                   'sites': [{'status': 'captured', 'reachedServerTicks': 100, 'tickEligible': True, 'images': images}]}}
        assert RUN._biome_visual_review_captured(receipt, target, staged)
        receipt['biomeVisualReview']['sites'][0]['images'].pop()
        assert not RUN._biome_visual_review_captured(receipt, target, staged)
    print('biome visual review contract: PASS')


if __name__ == '__main__':
    main()
