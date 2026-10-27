"""Summarize phase-scoped receipts without equating cumulative waits with wall time."""
from __future__ import annotations
import argparse
from collections import Counter
import csv
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import statistics


def gpu_rows(path):
    result = []
    if not path.exists():
        return result
    for row in csv.reader(path.read_text(encoding='utf-8').splitlines()):
        if len(row) != 5:
            continue
        try:
            timestamp = datetime.strptime(row[0].strip(), '%Y/%m/%d %H:%M:%S.%f').replace(
                tzinfo=timezone(timedelta(hours=9))).timestamp()
            result.append((timestamp, *map(float, row[1:])))
        except ValueError:
            continue
    return result


def delta(a, b):
    return {key: b.get(key, 0)-a.get(key, 0) for key in set(a) | set(b)
            if isinstance(a.get(key, 0), (int, float)) and not isinstance(a.get(key, 0), bool)
            and isinstance(b.get(key, 0), (int, float)) and not isinstance(b.get(key, 0), bool)}


def summarize(path):
    root = json.loads((path / 'receipt.json').read_text(encoding='utf-8'))
    launch = json.loads((path / 'launch-result.json').read_text(encoding='utf-8'))
    inputs = json.loads((path / 'input-manifest.json').read_text(encoding='utf-8'))
    if root['status'] != 'success' or launch['exit_code'] != 0 or not launch['source_world_metadata_unchanged']:
        raise ValueError('Process, receipt or source-preservation check failed')
    gpu = gpu_rows(path / 'gpu.csv')
    phases = root.get('phases', [root])
    summaries = []
    for phase in phases:
        expected = phase['target']['count']
        if phase['status'] != 'success' or len(phase['chunks']) != expected or phase['alreadyLoadedBefore']:
            raise ValueError('Incomplete or already-loaded region')
        if any(not phase[k].get('available') for k in ('tdBefore', 'tdAfter', 'onnxBefore', 'onnxAfter')):
            raise ValueError('Required TD/ONNX counters unavailable')
        before, after = phase['tdBefore']['snapshot'], phase['tdAfter']['snapshot']
        td, inference = delta(before, after), delta(before['inference'], after['inference'])
        onnx = delta(phase['onnxBefore']['runtimeMetrics'], phase['onnxAfter']['runtimeMetrics'])
        model_before, model_after = phase['onnxBefore']['modelRunMetrics'], phase['onnxAfter']['modelRunMetrics']
        models = {key: delta(model_before.get(key, {}), model_after.get(key, {})) for key in set(model_before)|set(model_after)}
        models = {k: {'calls': v['calls'], 'seconds': v['nanos']/1e9,
                      'mean_ms_per_call': v['nanos']/1e6/v['calls'] if v['calls'] else None}
                  for k,v in sorted(models.items())}
        started = datetime.fromisoformat(phase['startedUtc'].replace('Z', '+00:00')).timestamp()
        elapsed = phase['elapsedSeconds']
        samples = [row for row in gpu if started <= row[0] <= started+elapsed]
        waits = Counter()
        for row in phase['cpuProfile']['waitingOrBlockedRelevantSamples']:
            stack = row['stack']
            thread = stack.split(' [', 1)[0]
            if 'LocalTerrainProvider.fetchFastGround' in stack:
                kind = 'TD ground result'
            elif 'LocalTerrainProvider.fetchBiomeHeightmap' in stack:
                kind = 'TD complete biome result'
            elif 'LocalTerrainProvider' in stack and 'FutureTask.get' in stack:
                kind = 'TD other result'
            elif 'RiverEngine' in stack and ('join' in stack or 'get' in stack):
                kind = 'river engine result'
            else:
                kind = 'other or idle'
            group = 'C2ME workers' if thread.startswith('c2me-worker') else thread
            waits[f'{group}: {kind}'] += row['sampleCount']
        summaries.append({
            'target': phase['target'], 'started_utc': phase['startedUtc'], 'elapsed_seconds': elapsed,
            'requested_full_chunks': len(phase['chunks']), 'first_batch_seconds': phase['batches'][0]['totalSeconds'],
            'all_batch_seconds': [row['totalSeconds'] for row in phase['batches']],
            'biome_counts': dict(Counter(row['biome'] for row in phase['chunks'])),
            'structure_start_counts': dict(Counter(name for row in phase['chunks'] for name in row['structures'])),
            'process_cpu_seconds': phase['processCpuSeconds'],
            'average_utilized_logical_cores': phase['processCpuSeconds']/elapsed,
            'available_processors': phase['availableProcessors'],
            'td_counter_delta': td, 'inference_counter_delta': inference,
            'inference_before_active_queued': [before['inference']['active'], before['inference']['queued']],
            'inference_after_active_queued': [after['inference']['active'], after['inference']['queued']],
            'onnx_counter_delta': onnx, 'model_run_delta': models,
            'gpu_sample_count': len(samples),
            'gpu_utilization_mean_percent': statistics.mean(row[1] for row in samples) if samples else None,
            'gpu_memory_mib_min_max': [min(row[3] for row in samples), max(row[3] for row in samples)] if samples else None,
            'top_thread_cpu': phase['cpuProfile']['topThreadCpu'][:20],
            'waiting_sample_counts': dict(waits.most_common(20)),
            'cpu_profile_sample_intervals': phase['cpuProfile']['sampleIntervals'],
            'readiness': phase.get('readiness'),
        })
    summary = {'source_receipt': str(path / 'receipt.json'), 'status': 'success',
               'active_mod_jars': inputs['active_mod_jars'], 'seed': inputs['seed'], 'scale': inputs['scale'],
               'shaders_disabled': inputs.get('shaders_disabled_for_diagnostic', False), 'phases': summaries,
               'limitations': [
                   'Fresh chunks in one prepared world; shared river graphs/terrain caches warm during startup and earlier phases.',
                   'Requested FULL chunks include dependency work in neighboring chunks; this is not sustained chunks/second.',
                   'ONNX calls are host-observed run durations, not GPU kernel-only timing. GPU telemetry includes all applications.',
                   'Cumulative task queue waits overlap. Never add them to wall time. Live endpoint tasks can straddle counter boundaries.',
                   'Normal integrated-server and client rendering continue. CPU stack weights are diagnostic sampling estimates.',
                   'The first shaders-on exploratory run had different readiness/probe controls; do not report it as a matched speed comparison.',
               ]}
    (path / 'analysis.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    args = parser.parse_args()
    result = summarize(args.run)
    print(json.dumps({'status': result['status'], 'shaders_disabled':result['shaders_disabled'],
                      'phases': [{'target': p['target'], 'elapsed_seconds': p['elapsed_seconds'],
                                  'onnx_seconds': p['onnx_counter_delta']['runNanos']/1e9,
                                  'gpu_percent': p['gpu_utilization_mean_percent'], 'biomes':p['biome_counts']}
                                 for p in result['phases']]}, ensure_ascii=True))
