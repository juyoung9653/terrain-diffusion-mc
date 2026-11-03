"""Validate live receipts and compare every capture pair in the fixed scene.

Pixel changes measure image variation, not physical motion or its cause.
Rectangles use the 2560x1440 reference and scale to the requested viewport.
All compared runs must have identical measured dimensions.
"""
import argparse
import hashlib
import itertools
import json
from pathlib import Path

import numpy as np
from PIL import Image

REGIONS = {
    'outside_leaves': (1436, 660, 1704, 802),
    'outside_shadow': (1344, 822, 1504, 882),
    'unshadowed_ground': (1800, 940, 2200, 1100),
}


def analyze(path):
    prepare = json.loads((path / 'prepare-receipt.json').read_text())
    launch = json.loads((path / 'launch-receipt.json').read_text())
    receipt = json.loads((path / 'receipt.json').read_text())
    assert launch['exit_code'] == 0 and not launch['timed_out'] and not launch['crash_reports']
    assert launch['shader_sha256'] == prepare['shader_sha256']
    expected={'width':prepare['width'],'height':prepare['height']}
    assert receipt['success'] and receipt['window'] == expected
    width,height=expected['width'],expected['height']
    regions={name:(round(x0*width/2560),round(y0*height/1440),round(x1*width/2560),round(y1*height/1440))
             for name,(x0,y0,x1,y1) in REGIONS.items()}
    captures = receipt['captures']
    assert len(captures) == 3 and all(c['day_time'] == 6000 for c in captures)
    assert all(c['camera'] == {'x': 40.5, 'y': 166.0, 'z': -572.5, 'yaw': -90.0, 'pitch': 7.0} for c in captures)
    assert all(captures[i]['elapsed_ms'] - captures[i - 1]['elapsed_ms'] >= 6000 for i in (1, 2))
    if 'settle_ms' in receipt:
        assert captures[0]['scene_ms'] >= receipt['settle_ms']
        assert captures[0]['stable_frames'] >= receipt['settle_frames']
        assert all(captures[i]['rendered_frames'] > captures[i - 1]['rendered_frames'] for i in (1, 2))
    log = (path / 'logs/latest.log').read_text(encoding='utf-8', errors='replace')
    bridge = 'OpenGL D3D12 SR/FG bridge initialized' in log
    if prepare.get('require_dlss_bridge'):
        assert bridge and 'DLSS native bridge not installed' not in log
    frames, images = [], []
    for c in captures:
        image_path = path / 'screenshots' / c['file']
        frames.append(np.asarray(Image.open(image_path).convert('RGB')).astype(np.int16))
        assert frames[-1].shape == (height, width, 3)
        images.append({'file': str(image_path), 'sha256': hashlib.sha256(image_path.read_bytes()).hexdigest()})
    pairs = []
    for i, j in itertools.combinations(range(3), 2):
        delta = frames[j] - frames[i]
        measured = {}
        for name, (x0, y0, x1, y1) in regions.items():
            crop = delta[y0:y1, x0:x1]
            measured[name] = {
                'mean_absolute_channel_delta': float(np.abs(crop).mean()),
                'mean_signed_channel_delta': float(crop.mean()),
                'fraction_pixels_changed_over_8': float((np.abs(crop).max(axis=2) > 8).mean()),
            }
        pairs.append({'frames': [i, j], 'regions': measured})
    return {
        'run': path.name, 'validated': True, 'settle_ms': receipt.get('settle_ms', 5000),
        'window':expected,'region_bounds':regions,
        'captures': captures, 'images': images, 'dlss_bridge_initialized': bridge,
        'depth_format_errors': log.count('Depth formats do not match'),
        'shader_option_overrides': prepare.get('shader_option_overrides', {}),
        'diagnostic_instrumentation': prepare.get('diagnostic_instrumentation'),
        'config_overrides': prepare.get('config_overrides', {}), 'pairs': pairs,
    }, prepare


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runs', nargs='+', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    results = [analyze(path) for path in args.runs]
    assert all(result['window']==results[0][0]['window'] for result,_ in results), 'Cannot compare different viewport sizes'
    baseline = results[0][1]['files_sha256']
    for result, inputs in results:
        current = inputs['files_sha256']
        result['prepared_file_differences_from_first'] = [
            name for name in sorted(baseline.keys() | current.keys()) if baseline.get(name) != current.get(name)
        ]
    report = {
        'scope': 'Fixed time and camera; pixel variation only. Controls and warmup must match for causal interpretation.',
        'regions': REGIONS, 'runs': [result for result, _ in results],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps([{'run': r['run'], 'depth_errors': r['depth_format_errors'],
                       'pair_mad': [{k: v['mean_absolute_channel_delta'] for k, v in p['regions'].items()} for p in r['pairs']]}
                      for r, _ in results]))


if __name__ == '__main__':
    main()
