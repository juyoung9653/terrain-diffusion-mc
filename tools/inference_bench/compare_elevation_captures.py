"""Compare captured pipeline elevation, before biome/river/block placement.

Uses NumPy from the optional conversion venv. Integer differences are floor/clamp
proxies matching LocalTerrainProvider.toHeights, not final Minecraft ground Y.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def compare(baseline: Path, candidate: Path) -> dict:
    manifests = [json.loads((p / 'manifest.json').read_text('utf-8'))
                 for p in (baseline, candidate)]
    before, after = manifests
    for key in ('seed', 'shape', 'floatEncoding', 'pipelineSourceSha256',
                'releaseJarSha256', 'queryContract', 'regions', 'cases'):
        if before[key] != after[key]:
            raise ValueError(f'capture contract differs: {key}')
    for case in before['cases']:
        for key in ('x', 'noiseLabels'):
            if sha(baseline / case[key]) != sha(candidate / case[key]):
                raise ValueError(f'decoder input differs: {case["id"]}/{key}')
    regions = []
    for region in before['regions']:
        paths = [p / region['elevation'] for p in (baseline, candidate)]
        a, b = [np.fromfile(p, dtype='<f4').astype(np.float64) for p in paths]
        if a.size != 256 * 256 or a.shape != b.shape:
            raise ValueError('expected 256x256 elevation crops')
        if not np.isfinite(a).all() or not np.isfinite(b).all():
            raise ValueError('nonfinite elevation')
        difference = np.abs(a - b)
        ha, hb = [np.clip(np.floor(v), -32768, 32767).astype(np.int64) for v in (a, b)]
        dh = np.abs(ha - hb)
        regions.append({
            'id': region['id'], 'samples': int(a.size),
            'baselineSha256': sha(paths[0]), 'candidateSha256': sha(paths[1]),
            'maxAbsElevation': float(difference.max()),
            'meanAbsElevation': float(difference.mean()),
            'p95AbsElevation': float(np.quantile(difference, .95)),
            'rmseElevation': float(np.sqrt(np.mean(difference ** 2))),
            'floorHeightChanged': int(np.count_nonzero(dh)),
            'floorHeightChangedPercent': float(np.mean(dh != 0) * 100),
            'floorHeightDeltaMax': int(dh.max()),
            'floorHeightDeltaAtLeast2': int(np.count_nonzero(dh >= 2)),
            'floorHeightDeltaAtLeast10': int(np.count_nonzero(dh >= 10)),
        })
    return {
        'kind': 'pipeline_elevation_comparison', 'status': 'measured',
        'baseline': str(baseline), 'candidate': str(candidate),
        'decoderInputsIdentical': True, 'regions': regions,
        'limitation': 'Pipeline elevation and floor/clamp proxy only; excludes biome, river and block placement. No world compatibility acceptance implied.',
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit('refusing existing result')
    result = compare(args.baseline, args.candidate)
    args.output.write_text(json.dumps(result, indent=2, allow_nan=False) + '\n', 'utf-8')
    print(json.dumps(result['regions'], indent=2))


if __name__ == '__main__':
    main()
