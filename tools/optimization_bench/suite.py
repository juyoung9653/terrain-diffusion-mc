"""Run one client at a time, preserving every fixture and checking receipts."""
import argparse
import json
import subprocess
import sys
from pathlib import Path

import analyze

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'build' / 'optimization-bench'
VARIANTS = {'baseline': None, 'no-badopt': 'BadOptimizations-',
            'no-cullleaves': 'cullleaves-', 'no-gpubooster': 'GPUBooster-'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--prefix', required=True)
    p.add_argument('--variants', nargs='+', choices=VARIANTS, default=list(VARIANTS))
    p.add_argument('--repeats', type=int, default=3)
    p.add_argument('--seconds', type=int, default=60)
    p.add_argument('--warmup', type=int, default=45)
    args = p.parse_args()
    runs = []
    out = WORK / (args.prefix + '-summary.json')
    if out.exists():
        raise SystemExit('Refusing existing suite name')
    for rep in range(args.repeats):
        # Rotate order to spread OS cache and thermal effects across variants.
        offset = rep % len(args.variants)
        order = args.variants[offset:] + args.variants[:offset]
        for variant in order:
            name = f'{args.prefix}-r{rep+1}-{variant}'
            command = [sys.executable, str(Path(__file__).with_name('launch.py')), 'run', name,
                       '--probe-jar', str(WORK / 'optimization-bench-1.0.0.jar'),
                       '--property', f'optbench.warmup={args.warmup}',
                       '--property', f'optbench.seconds={args.seconds}', '--timeout', '600']
            if VARIANTS[variant]:
                command.extend(['--exclude-prefix', VARIANTS[variant]])
            print(f'START {name}', flush=True)
            result = subprocess.run(command, cwd=ROOT, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            if result.returncode:
                raise SystemExit(f'FAILED {name}: process exit {result.returncode}; inspect retained logs')
            summary = analyze.summarize(WORK / 'runs' / name)
            summary.update(variant=variant, repeat=rep+1)
            runs.append(summary)
            out.write_text(json.dumps({'complete': False, 'runs': runs}, indent=2) + '\n', encoding='utf-8')
            print(json.dumps(summary), flush=True)
    out.write_text(json.dumps({'complete': True, 'runs': runs}, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
