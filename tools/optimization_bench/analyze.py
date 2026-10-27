"""Summarize fresh successful render receipts; failed launches are never results."""
import argparse
import csv
import json
import re
import statistics
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo


SAMPLE_LOG = re.compile(r"\[(?:[^\]]* )?(\d{2}:\d{2}:\d{2}\.\d{3})\].*Optimization benchmark sample phase started")


def log_anchored_sample_window(run, launch, receipt):
    """Map the render-thread sample marker onto launcher-relative RSS samples.

    resources.csv starts immediately after Popen, whereas process_uptime is JVM
    uptime and may include post-sample GC or visual smoke.  The host's launcher
    logs use Asia/Seoul local time; timestamps are therefore converted from the
    UTC launch receipt and matched by nearest local day.  RSS is one-Hz, so its
    window edges are accurate to the sample log timestamp but each RSS point is
    inherently ±1 second.
    """
    started = datetime.fromisoformat(launch["started_utc"]).astimezone(ZoneInfo("Asia/Seoul"))
    marker = None
    for log in (run / "logs" / "latest.log", run / "logs" / "debug.log", run / "logs" / "launcher-stdout-stderr.log"):
        if not log.is_file(): continue
        for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
            found = SAMPLE_LOG.search(line)
            if found:
                marker = found.group(1)
                break
        if marker: break
    if not marker:
        return None, None, "unavailable:no_sample_phase_log"
    clock = datetime.strptime(marker, "%H:%M:%S.%f").time()
    candidates = [datetime.combine((started + timedelta(days=offset)).date(), clock, tzinfo=started.tzinfo)
                  for offset in (-1, 0, 1)]
    marked = min(candidates, key=lambda candidate: abs((candidate - started).total_seconds()))
    start = (marked - started).total_seconds()
    if start < 0:
        return None, None, "unavailable:sample_log_before_launcher"
    duration = sum(receipt["frameTimesNs"]) / 1e9
    return start, start + duration, "log_anchored_Asia_Seoul_rss_1Hz"


def summarize(run):
    launch = json.loads((run / 'launch-receipt.json').read_text(encoding='utf-8'))
    receipt = json.loads((run / 'optbench-receipt.json').read_text(encoding='utf-8'))
    if launch['exit_code'] != 0 or launch.get('timed_out') or not receipt['success']:
        raise ValueError(f'{run.name}: unsuccessful launch/probe')
    frames = receipt['frameTimesNs']
    if len(frames) != receipt['frame_count'] or sum(frames) < receipt['sampleSeconds'] * 0.95e9:
        raise ValueError(f'{run.name}: incomplete frame coverage')
    if receipt['effectiveVsync'] or receipt['effectiveFpsLimit'] != 260:
        raise ValueError(f'{run.name}: capped render measurement')
    sample_start_s, sample_end_s, rss_window_method = log_anchored_sample_window(run, launch, receipt)
    resources = list(csv.DictReader((run / 'resources.csv').open(encoding='utf-8')))
    rss = [int(r['rss_bytes']) / 1048576 for r in resources
           if sample_start_s is not None and sample_start_s <= float(r['elapsed_seconds']) <= sample_end_s]
    p99 = sorted(frames)[min(len(frames)-1, int(len(frames)*0.99))] / 1e6
    loading = {}
    with (run / 'logs' / 'launcher-stdout-stderr.log').open(encoding='utf-8', errors='replace') as stream:
        for line in stream:
            for key, pattern in (
                ('world_entry_seconds', r'Time from main menu to in-game was ([\d.]+) seconds'),
                ('full_entry_seconds', r'Total time to load game and open world was ([\d.]+) seconds'),
            ):
                match = re.search(pattern, line)
                if match:
                    loading[key] = float(match.group(1))
    return {
        'run': run.name,
        'fps': len(frames) * 1e9 / sum(frames),
        'p99_ms': p99,
        'frames_over_50ms_pct': 100 * sum(v > 50e6 for v in frames) / len(frames),
        'rss_mib_median': statistics.median(rss) if rss else None,
        'rss_sample_window_start_seconds_since_popen': sample_start_s,
        'rss_sample_window_end_seconds_since_popen': sample_end_s,
        'rss_sample_window_method': rss_window_method,
        'rss_sample_count': len(rss),
        'heap_start_mib': receipt['heap_start_used_bytes'] / 1048576,
        'heap_end_mib': receipt['heap_end_used_bytes'] / 1048576,
        'heap_post_explicit_gc_mib': receipt.get('heap_post_explicit_gc_bytes', receipt['heap_end_used_bytes']) / 1048576,
        'heap_end_committed_mib': receipt['heap_end_committed_bytes'] / 1048576,
        'heap_delta_mib': (receipt['heap_end_used_bytes'] - receipt['heap_start_used_bytes']) / 1048576,
        'gc_collections': receipt['gcCollectionsDelta'],
        'gc_ms': receipt['gcTimeMillisDelta'],
        'world_ready_since_probe_ms': receipt['startup_client_world_ready_ms'],
        'cpu_seconds': receipt['processCpuNanosDelta'] / 1e9,
        'render_mean_ms': receipt.get('mean_render_ms'),
        'render_p99_ms': receipt.get('p99_render_ms'),
        'gpu_mean_ms': receipt.get('mean_gpu_ms'),
        'gpu_p99_ms': receipt.get('p99_gpu_ms'),
        'gpu_samples': receipt.get('gpu_work_count'),
        'window_fps_limit': receipt.get('window_fps_limit_sample_end'),
        'inactive_frames': receipt.get('sample_inactive_frames'),
        'terrain_diffusion_world': receipt.get('terrain_diffusion_world', False),
        'terrain_requested_chunks': receipt.get('terrain_requested_chunks', 0),
        **loading,
        'framebuffer': receipt['framebuffer'],
        'gpu': receipt['glRenderer'],
        'excluded': [x.split(':', 1)[1] for x in launch['runtime_inputs'] if x.startswith('excluded:')],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runs', type=Path, nargs='+')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = {'runs': [summarize(p) for p in args.runs],
              'scope': 'Stationary synthetic scene with installed modpack. It measures client rendering and heap snapshots, not a terrain-generation retention census.',
              'interpretation': 'Failure to detect a difference is not proof of equivalence. Compare independent launches; frames are not independent trials.'}
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(rendered + '\n', encoding='utf-8')
    print(rendered)


if __name__ == '__main__':
    main()
