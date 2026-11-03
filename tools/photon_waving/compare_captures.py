"""Measure image changes in explicit scene regions; does not infer their cause."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('images', type=Path, nargs='+')
    ap.add_argument('--region', action='append', default=[], help='label:x0,y0,x1,y1 in screenshot pixels')
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    if len(args.images) < 2:
        ap.error('At least two images are required')
    frames = [np.asarray(Image.open(p).convert('RGB')).astype(np.int16) for p in args.images]
    if any(f.shape != frames[0].shape for f in frames):
        raise ValueError('Screenshot dimensions differ')
    height, width, _ = frames[0].shape
    regions = {'full_frame': (0, 0, width, height)}
    for item in args.region:
        label, rectangle = item.split(':', 1)
        x0, y0, x1, y1 = map(int, rectangle.split(','))
        if not (0 <= x0 < x1 <= width and 0 <= y0 < y1 <= height):
            raise ValueError('Invalid region: ' + item)
        regions[label] = (x0, y0, x1, y1)
    report = {'scope': 'Pixel differences only; animation, filtering and lighting can all change pixels.',
              'images': [{'file': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in args.images],
              'width': width, 'height': height, 'pairs': []}
    for index, frame in enumerate(frames[1:], 1):
        difference = np.abs(frame - frames[0])
        row = {'first': str(args.images[0]), 'second': str(args.images[index]), 'regions': {}}
        for label, (x0, y0, x1, y1) in regions.items():
            values = difference[y0:y1, x0:x1]
            maximum_channel = values.max(axis=2)
            row['regions'][label] = {
                'bounds': [x0, y0, x1, y1], 'mean_absolute_channel_delta': float(values.mean()),
                'max_channel_delta': int(values.max()),
                'pixels_changed_over_8': int((maximum_channel > 8).sum()),
                'fraction_changed_over_8': float((maximum_channel > 8).mean()),
            }
        report['pairs'].append(row)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
