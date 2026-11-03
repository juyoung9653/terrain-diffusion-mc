"""Remove disposable completed probe copies, retaining reproducible evidence.

Only this task's fixed workspace run directory is accepted. Never use an archived
run as a writable game fixture. Logs, screenshots, timing, shader packs, configs,
input manifests and the diagnostic JAR are retained.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import stat
import time

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'build/photon-waving-9chunks-20261029/client-probe'
RUNS = WORK / 'runs'
KEEP = {'logs', 'screenshots', 'timing', 'shaderpacks', 'config', 'crash-reports'}


def checked(path):
    resolved = path.resolve(strict=True)
    if RUNS.resolve() not in resolved.parents:
        raise RuntimeError(f'Cleanup target escapes fixed runs root: {path}')
    for item in (path, *path.parents):
        if item == RUNS.parent:
            break
        attributes = item.lstat().st_file_attributes
        if attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT:
            raise RuntimeError(f'Reparse point refused: {item}')
    if path.is_dir():
        for folder, directories, files in os.walk(path, followlinks=False):
            for name in directories + files:
                child = Path(folder) / name
                if child.lstat().st_file_attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT:
                    raise RuntimeError(f'Reparse point refused: {child}')
    return resolved


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--apply', action='store_true')
    ap.add_argument('--include-unrun', action='store_true', help='Also archive abandoned prepared fixtures after all planned tests finish')
    args = ap.parse_args()
    import psutil
    for process in psutil.process_iter(['name']):
        if (process.info['name'] or '').lower() in ('java.exe', 'javaw.exe', 'python.exe'):
            try:
                command = process.cmdline()
            except psutil.NoSuchProcess:
                continue
            text = ' '.join(command).lower()
            if str(RUNS).lower() in text or ('client_probe.py' in text and any(x in command for x in ('run', 'prepare', 'refresh-probe'))):
                raise RuntimeError('A probe is active; cleanup waits until all runs finish')
    targets = []
    archived = []
    for run in sorted(RUNS.iterdir()):
        prepare = run / 'prepare-receipt.json'
        if not run.is_dir() or not prepare.exists() or (not args.include_unrun and not (run / 'launch-receipt.json').exists()):
            continue
        inputs = json.loads(prepare.read_text(encoding='utf-8'))
        diagnostic_jar = inputs['probe_jar']
        for child in run.iterdir():
            if child.name == 'mods':
                targets.extend(checked(p) for p in child.iterdir() if p.name != diagnostic_jar)
            elif child.is_dir() and child.name not in KEEP:
                targets.append(checked(child))
        archived.append(run.name)
    before = shutil.disk_usage(ROOT).free
    if args.apply:
        for path in targets:
            checked(path)
            if path.is_dir():
                shutil.rmtree(path)
            else:
                path.unlink()
        for name in archived:
            (RUNS / name / 'archived-evidence.json').write_text(json.dumps({
                'evidence_only': True, 'game_copy_removed': True,
                'retained': sorted(KEEP) + ['diagnostic JAR', 'root files and receipts']
            }, indent=2) + '\n', encoding='utf-8')
    report = {'applied': args.apply, 'runs': archived,
              'removed_paths' if args.apply else 'planned_paths': [str(p.relative_to(RUNS)) for p in targets],
              'free_bytes_before': before, 'free_bytes_after': shutil.disk_usage(ROOT).free}
    output = WORK / ('cleanup-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '.json')
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'report': str(output), 'applied': args.apply, 'runs': len(archived),
                      'targets': len(targets), 'free_bytes_increase': report['free_bytes_after'] - before}))


if __name__ == '__main__':
    main()
