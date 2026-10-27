"""Summarize fresh per-frame receipts without calling GPU elapsed GPU busy time."""
import argparse
import csv
import json
import math
from pathlib import Path
import statistics
import hashlib


def metric(values):
    if not values:
        return None
    ordered = sorted(values)
    def percentile(p):
        return ordered[max(0, math.ceil(p * len(ordered)) - 1)]
    return {'samples': len(values), 'mean_ms': statistics.mean(values),
            'median_ms': statistics.median(values), 'p95_ms': percentile(.95),
            'p99_ms': percentile(.99), 'max_ms': max(values)}


def analyze(directory):
    receipt = json.loads((directory / 'receipt.json').read_text(encoding='utf-8'))
    inputs = json.loads((directory / 'input-receipt.json').read_text())
    launch = json.loads((directory / 'launch-result.json').read_text())
    rows = list(csv.DictReader((directory / 'fps-frames.csv').open(newline='')))
    columns = {key: [float(row[key]) for row in rows if row.get(key, '') != '' and float(row[key]) >= 0]
               for key in rows[0] if key != 'frame'} if rows else {}
    frames = columns.get('frame_ms', [])
    errors = []
    driver_before = directory / 'driver-settings-before.json'
    driver_after = directory / 'driver-settings-after.json'
    driver_hash = None
    if driver_before.exists():
        driver = json.loads(driver_before.read_text(encoding='utf-8'))
        driver_hash = hashlib.sha256(json.dumps(driver, sort_keys=True).encode()).hexdigest()
        if not driver_after.exists() or driver != json.loads(driver_after.read_text(encoding='utf-8')):
            errors.append('missing final driver verification or driver settings changed')
    if not receipt.get('success') or launch.get('exit_code') != 0:
        errors.append('unsuccessful probe or process')
    if launch.get('external_java_during_sample'):
        errors.append('other Java work overlapped sampling')
    if receipt.get('inactive_frames', 0) or receipt.get('invalid_frames', 0):
        errors.append('foreground/validity failure')
    start_identity = receipt.get('entity_snapshot_start_sha256')
    end_identity = receipt.get('entity_snapshot_end_sha256')
    if start_identity and end_identity and start_identity != end_identity:
        errors.append('server entity state changed during sampling')
    gpu = columns.get('gpu_elapsed_ms', [])
    if receipt.get('gpu_query_errors', 0) or receipt.get('gpu_query_dropped', 0):
        errors.append('GPU query errors/drops')
    if len(gpu) < max(0, len(frames) - 32):
        errors.append('insufficient GPU timestamp coverage')
    result = {'trial': directory.name, 'valid': not errors, 'errors': errors,
        'driver_settings_sha256': driver_hash,
        'conditions': {k: inputs.get(k) for k in ('baseline_manifest_sha256', 'probe_sha256',
            'vsync', 'cap', 'gecko', 'pom_disabled', 'entities_hidden',
            'spark_background_disabled', 'respawn_recap_disabled', 'max_heap_mib', 'min_heap_mib',
            'jfr', 'presentmon', 'warmup_seconds')},
        'scene': {k: receipt.get(k) for k in ('requested_camera', 'actual_camera', 'world_time', 'raining',
                   'world_tick_frozen', 'loaded_entity_count', 'framebuffer',
                   'entity_snapshot_start_sha256', 'entity_snapshot_end_sha256')},
        'metrics': {key: metric(values) for key, values in columns.items()},
        'swap': {k: receipt.get(k) for k in ('swap_present_samples', 'swap_wall_mean_ms',
                    'swap_cpu_mean_ms', 'swap_wall_p95_ms',
                    'swap_thread_cpu_p95_ms', 'swap_non_cpu_p95_ms')},
        'cpu_caveat': 'Windows thread CPU counters in these recordings have 15.625ms steps; use aggregate mean, not per-frame CPU percentiles or per-frame wall-minus-CPU as precise wait time',
        'gpu_caveat': 'GPU timeline elapsed includes idle; not GPU busy time',
        'sample_caveat': 'Frozen scene isolates stationary rendering; does not establish travel/animation performance'}
    if frames:
        swap_path = directory / 'swap-times.csv'
        swaps = list(csv.DictReader(swap_path.open(newline=''))) if swap_path.exists() else []
        elapsed = 0.0
        long_frames = []
        for index, row in enumerate(rows):
            elapsed += float(row['frame_ms']) / 1000
            if float(row['frame_ms']) > 25:
                long_frames.append({'elapsed_s': elapsed, **row,
                    'swap_in_interval_ms': swaps[index]['swap_wall_ms'] if index < len(swaps) else None})
        result['long_frames_over_25_ms'] = long_frames
        slow = sorted(frames, reverse=True)[:max(1, math.ceil(len(frames) * .01))]
        result.update({'average_fps': 1000 / statistics.mean(frames),
            'one_percent_low_fps': 1000 / statistics.mean(slow),
            'over_16_667_ms_percent': 100 * sum(x > 1000/60 for x in frames) / len(frames),
            'over_33_333_ms_percent': 100 * sum(x > 1000/30 for x in frames) / len(frames),
            'over_25_ms_frames': sum(x > 25 for x in frames),
            'over_50_ms_frames': sum(x > 50 for x in frames)})
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runs', type=Path, nargs='+')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    runs = [analyze(path) for path in args.runs]
    scene_keys = ('actual_camera', 'world_time', 'raining', 'world_tick_frozen',
                  'loaded_entity_count', 'framebuffer', 'entity_snapshot_start_sha256')
    mismatched = [key for key in scene_keys if len({str(run['scene'].get(key)) for run in runs}) > 1]
    identity_available = all(run['scene'].get('entity_snapshot_start_sha256') for run in runs)
    result = {'runs': runs, 'comparison': {
        'scene_fields_different': mismatched,
        'entity_identity_available': bool(identity_available),
        'same_probe': len({run['conditions']['probe_sha256'] for run in runs}) == 1,
        'same_snapshot': len({run['conditions']['baseline_manifest_sha256'] for run in runs}) == 1,
        'same_verified_driver_settings': all(run['driver_settings_sha256'] for run in runs)
            and len({run['driver_settings_sha256'] for run in runs}) == 1,
        'matched_scene': not mismatched and bool(identity_available),
        'note': 'Valid individual captures do not imply a valid matched comparison; declared setting differences must also be reviewed'}}
    text = json.dumps(result, indent=2)
    if args.output:
        args.output.write_text(text, encoding='utf-8')
    print(text)
