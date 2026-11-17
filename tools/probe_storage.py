"""Bounded, content-addressed storage for successful probe model inputs.

Call ``maintain`` only while ``probe_suite.exclusive_lock`` is held and only
after the caller has completed every post-success action (for example, snapshot
sealing).  It never adopts an existing fixture unless that fixture was supplied
by that successful invocation.
"""
from __future__ import annotations
import argparse
import re
import json
import os
import shutil
import sys
import time
import uuid
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_evidence import PROBE_RECEIPT, sha256
FAMILIES = {'streams-probe', 'structure-density'}
MODELS = ('base_model.onnx', 'coarse_model.onnx', 'decoder_model.onnx', 'decoder_model_fp16.onnx')
MANAGED = 'probe-inputs.json'
PIN = 'keep-probe-inputs.json'
GUARD = 'archived-model-inputs.json'
SCHEMA = 1

def _link(path: Path) -> bool:
    return path.is_symlink() or (hasattr(path, 'is_junction') and path.is_junction())

def _root(root: Path) -> Path:
    for component in (root.absolute(), *root.absolute().parents):
        if _link(component):
            raise ValueError('linked workspace root')
    return root.resolve(strict=True)

def _regular(path: Path, root: Path) -> Path:
    """Check a finite lexical component list before resolving or modifying paths."""
    if '..' in path.parts:
        raise ValueError('path traversal is not allowed')
    path = path.absolute()
    if not path.is_relative_to(root):
        raise ValueError('path outside workspace')
    for component in (path, *path.parents):
        if _link(component):
            raise ValueError('linked path: ' + str(component))
    return path

def _run(root: Path, family: str, raw: Path) -> Path:
    if family not in FAMILIES:
        raise ValueError('unsupported probe family')
    root = _root(root)
    base = _regular(root / 'build' / family, root)
    candidate = raw if raw.is_absolute() else root / raw
    if candidate.parent != base or not candidate.name or any((p in ('.', '..') for p in candidate.parts)):
        raise ValueError('run must be a direct child of its build family')
    _regular(candidate, root)
    if not candidate.is_dir():
        raise ValueError('probe run missing')
    return candidate

def _rel(root: Path, path: Path) -> str:
    return path.relative_to(root).as_posix()

def _read(path: Path) -> dict:
    for component in (path, *path.parents):
        if _link(component):
            raise ValueError('linked metadata path: ' + str(component))
    if not path.is_file():
        raise ValueError('missing metadata: ' + str(path))
    value = json.loads(path.read_text(encoding='utf-8-sig'))
    if not isinstance(value, dict):
        raise ValueError('metadata must be an object')
    return value

def _atomic(path: Path, value: object) -> None:
    if _link(path):
        raise ValueError('linked metadata output')
    temp = path.with_name('.' + path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        temp.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)

def _validation(run: Path) -> None:
    row = _read(run / PROBE_RECEIPT)
    if row.get('schema') != 1 or row.get('kind') != 'probe-validation' or row.get('success') is not True or (type(row.get('processExit')) is not int) or (row['processExit'] != 0) or (row.get('probeStatus') != 'success'):
        raise ValueError('run lacks successful validation evidence')
    raw = row.get('receipt')
    if not isinstance(raw, str) or not raw or Path(raw).is_absolute():
        raise ValueError('invalid validation receipt path')
    receipt = _regular(run / raw, run)
    manifest = _regular(run / 'input-manifest.json', run)
    if not receipt.is_file() or not manifest.is_file():
        raise ValueError('missing validation inputs')
    if row.get('receiptSha256') != sha256(receipt) or row.get('inputManifestSha256') != sha256(manifest):
        raise ValueError('validation evidence is stale')

def _models(root: Path, run: Path) -> list[dict]:
    base = _regular(run / 'terrain-diffusion-models', root)
    if not base.is_dir():
        raise ValueError('missing model directory')
    manifest = _read(run / 'input-manifest.json')
    hashes, assets = (manifest.get('sha256', {}), manifest.get('modelAssets', {}))
    if not isinstance(hashes, dict) or not isinstance(assets, dict):
        raise ValueError('invalid model manifest')
    models = []
    for name in MODELS:
        path = base / name
        expected = hashes.get('terrain-diffusion-models/' + name)
        asset = assets.get(name)
        if not os.path.lexists(path):
            if expected is not None or asset is not None:
                raise ValueError('declared model missing: ' + name)
            continue
        _regular(path, root)
        if not path.is_file() or path.stat().st_nlink != 1:
            raise ValueError('linked or hardlinked model input')
        before = path.stat()
        digest = sha256(path)
        after = path.stat()
        if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
            raise ValueError('model changed while hashing')
        if asset is not None:
            if not isinstance(asset, dict) or asset.get('bytes') != before.st_size:
                raise ValueError('invalid model asset: ' + name)
            expected = asset.get('sha256')
        if expected != digest:
            raise ValueError('model does not match input manifest: ' + name)
        models.append({'path': _rel(root, path), 'name': name, 'bytes': before.st_size, 'mtime_ns': before.st_mtime_ns, 'sha256': digest})
    return models

def _objects(root: Path, *, create: bool = False) -> Path:
    p = _regular(root / 'build' / 'probe-inputs' / 'models', root)
    if create:
        p.mkdir(parents=True, exist_ok=True)
    return p

def _object(root: Path, model: dict) -> int:
    p = _objects(root, create=True) / (model['sha256'] + '.onnx')
    if os.path.lexists(p):
        if _link(p) or p.stat().st_nlink != 1 or p.stat().st_size != model['bytes'] or (sha256(p) != model['sha256']):
            raise ValueError('corrupt retained model')
        return 0
    temp = p.with_name('.' + p.name + '.' + uuid.uuid4().hex + '.tmp')
    source = root / model['path']
    try:
        with source.open('rb') as a, temp.open('xb') as b:
            shutil.copyfileobj(a, b, 8 * 1024 * 1024)
        if temp.stat().st_size != model['bytes'] or sha256(temp) != model['sha256']:
            raise ValueError('retained copy verification failed')
        if os.name == 'nt':
            os.rename(temp, p)
        else:
            with temp.open('rb') as src, p.open('xb') as dst:
                shutil.copyfileobj(src, dst, 8 * 1024 * 1024)
        return model['bytes']
    finally:
        temp.unlink(missing_ok=True)

def _legacy_protected(root: Path) -> set[str]:
    answer = set()
    for p in (root / 'build').glob('cleanup-*/plan.json'):
        try:
            value = _read(p)
            rows = value.get('protected_fixtures', [])
        except (OSError, ValueError, json.JSONDecodeError):
            raise ValueError('malformed cleanup protection plan')
        if not isinstance(rows, list) or not all((isinstance(x, str) for x in rows)):
            raise ValueError('malformed cleanup protection plan')
        answer.update(rows)
    return answer

def _managed(root: Path, family: str) -> list[tuple[Path, dict]]:
    base = _regular(root / 'build' / family, root)
    result = []
    if not base.exists():
        return result
    for p in base.iterdir():
        if _link(p) or not p.is_dir():
            continue
        record = p / MANAGED
        if record.exists():
            try:
                result.append((p, _read(record)))
            except (OSError, ValueError):
                continue
    return result

def _archivable(root: Path, run: Path, record: dict) -> tuple[bool, str]:
    if os.path.lexists(run / PIN):
        return (False, 'pinned')
    if os.path.lexists(run / GUARD):
        return (False, 'already_archived')
    try:
        _validation(run)
        if record.get('validation_sha256') != sha256(run / PROBE_RECEIPT):
            return (False, 'evidence_changed')
        current = _models(root, run)
        if not current or current != record.get('models'):
            return (False, 'inputs_changed')
    except (OSError, ValueError):
        return (False, 'evidence_or_inputs_changed')
    return (True, 'eligible')

def _archive(root: Path, run: Path, record: dict) -> tuple[int, int]:
    models = record['models']
    created = sum(_object(root, item) for item in models)
    if os.path.lexists(run / GUARD) or os.path.lexists(run / PIN):
        raise ValueError('fixture protection changed before archival')
    marker = {'schema': SCHEMA, 'kind': 'probe-input-archive', 'fixture': _rel(root, run), 'models': models, 'restore_command': 'python -B tools/probe_storage.py restore ' + _rel(root, run), 'created_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}
    _atomic(run / GUARD, marker)
    removed = 0
    for item in models:
        p = _regular(root / item['path'], root)
        if not p.is_file() or p.stat().st_nlink != 1 or p.stat().st_size != item['bytes'] or (sha256(p) != item['sha256']):
            raise ValueError('model changed before archival')
        p.unlink()
        removed += item['bytes']
    cache = run / 'terrain-diffusion-models' / 'onnx-cache'
    declared = _read(run / 'input-manifest.json').get('sha256', {})
    if not isinstance(declared, dict):
        declared = {}
    if cache.is_dir() and (not _link(cache)):
        allowed = {f"{Path(x['name']).stem.split('_')[0]}-{v}-{x['sha256'][:16]}.onnx" for x in models for v in ('1.20.0', 'unknown')}
        for p in cache.iterdir():
            key = 'terrain-diffusion-models/onnx-cache/' + p.name
            if p.name in allowed and key not in declared and p.is_file() and (not _link(p)) and (p.stat().st_nlink == 1):
                removed += p.stat().st_size
                p.unlink()
    return removed, created

def maintain(root: Path, family: str, runs: list[Path], *, keep_inputs: bool=False, keep_latest: int=2) -> dict:
    if family not in FAMILIES or type(keep_latest) is not int or keep_latest < 0:
        raise ValueError('invalid retention policy')
    root = _root(root)
    supplied = [_run(root, family, path) for path in runs]
    protected = _legacy_protected(root)
    registered, skipped = ([], [])
    for run in supplied:
        if _rel(root, run) in protected:
            skipped.append({'run': run.name, 'reason': 'legacy_protected'})
            continue
        if os.path.lexists(run / GUARD):
            skipped.append({'run': run.name, 'reason': 'already_archived'})
            continue
        try:
            if not os.path.lexists(run / 'terrain-diffusion-models'):
                skipped.append({'run': run.name, 'reason': 'no_models'})
                continue
            _validation(run)
            models = _models(root, run)
            if not models:
                skipped.append({'run': run.name, 'reason': 'no_models'})
                continue
            record = {'schema': SCHEMA, 'kind': 'probe-inputs', 'family': family, 'run': _rel(root, run), 'models': models, 'validation_sha256': sha256(run / PROBE_RECEIPT), 'recorded_ns': time.time_ns()}
            _atomic(run / MANAGED, record)
            if keep_inputs:
                _atomic(run / PIN, {'schema': SCHEMA, 'kind': 'probe-input-pin'})
            registered.append(run.name)
        except ValueError as exc:
            skipped.append({'run': run.name, 'reason': str(exc)})
    eligible = []
    for run, record in _managed(root, family):
        if record.get('schema') != SCHEMA or record.get('kind') != 'probe-inputs' or record.get('family') != family or (record.get('run') != _rel(root, run)) or (type(record.get('recorded_ns')) is not int):
            continue
        ok, reason = (False, 'legacy_protected') if _rel(root, run) in protected else _archivable(root, run, record)
        if ok:
            eligible.append((run, record))
        else:
            skipped.append({'run': run.name, 'reason': reason})
    eligible.sort(key=lambda pair: (pair[1]['recorded_ns'], pair[0].name), reverse=True)
    archived, removed, created = ([], 0, 0)
    for run, record in eligible[keep_latest:]:
        retired_bytes, object_bytes = _archive(root, run, record)
        removed += retired_bytes
        created += object_bytes
        archived.append(run.name)
    return {'status': 'success', 'registered': registered, 'archived': archived, 'skipped': skipped,
            'removed_bytes': removed, 'new_object_bytes': created, 'net_saved_bytes': removed - created,
            'keep_latest': keep_latest}

def restore(root: Path, run: Path) -> dict:
    root = _root(root)
    family = run.parent.name
    run = _run(root, family, run)
    rel = _rel(root, run)
    marker = _read(run / GUARD)
    if marker.get('schema') != SCHEMA or marker.get('kind') != 'probe-input-archive' or marker.get('fixture') != rel or (not isinstance(marker.get('models'), list)) or (not marker['models']):
        raise ValueError('not a managed probe archive')
    pending = []
    seen = set()
    for item in marker['models']:
        if (not isinstance(item, dict) or not isinstance(item.get('name'), str)
                or type(item.get('bytes')) is not int or type(item.get('mtime_ns')) is not int
                or item['mtime_ns'] < 0):
            raise ValueError('invalid archive model metadata')
        if not isinstance(item, dict) or item.get('path') != rel + '/terrain-diffusion-models/' + item.get('name', '') or item.get('name') not in MODELS or (item['name'] in seen) or (not isinstance(item.get('bytes'), int)) or (item['bytes'] < 0) or (not isinstance(item.get('mtime_ns'), int)) or (not isinstance(item.get('sha256'), str)) or (not re.fullmatch('[0-9a-f]{64}', item['sha256'])):
            raise ValueError('invalid archive model path')
        seen.add(item['name'])
        target = root / item['path']
        source = _objects(root) / (item['sha256'] + '.onnx')
        _regular(target, root)
        _regular(source, root)
        if _link(source) or not source.is_file() or source.stat().st_nlink != 1 or (source.stat().st_size != item['bytes']) or (sha256(source) != item['sha256']):
            raise ValueError('retained model mismatch')
        if os.path.lexists(target):
            if _link(target) or not target.is_file() or target.stat().st_nlink != 1 or (target.stat().st_size != item['bytes']) or (sha256(target) != item['sha256']):
                raise ValueError('refusing changed restore target')
        else:
            pending.append((item, source, target))
    for item, source, target in pending:
        _regular(target, root)
        target.parent.mkdir(exist_ok=True)
        temp = target.with_name('.' + target.name + '.restore.' + uuid.uuid4().hex + '.tmp')
        try:
            with source.open('rb') as a, temp.open('xb') as b:
                shutil.copyfileobj(a, b, 8 * 1024 * 1024)
            if sha256(temp) != item['sha256']:
                raise ValueError('restored copy mismatch')
            os.utime(temp, ns=(item['mtime_ns'], item['mtime_ns']))
            if os.name == 'nt':
                os.rename(temp, target)
            else:
                with temp.open('rb') as src, target.open('xb') as dst:
                    shutil.copyfileobj(src, dst, 8 * 1024 * 1024)
                os.utime(target, ns=(item['mtime_ns'], item['mtime_ns']))
        finally:
            temp.unlink(missing_ok=True)
    for item in marker['models']:
        target = _regular(root / item['path'], root)
        if (not target.is_file() or target.stat().st_nlink != 1
                or target.stat().st_size != item['bytes'] or sha256(target) != item['sha256']):
            raise ValueError('final restore mismatch')
    (run / GUARD).unlink()
    return {'run': rel, 'restored': len(pending)}

def inventory(root: Path) -> dict:
    root = _root(root)
    build = _regular(root / 'build', root)
    totals = {k: 0 for k in ('models', 'onnx_cache', 'mods', 'world', 'other')}
    unique = {k: 0 for k in totals}
    seen = set()
    folders = {}
    links = []
    for current, dirs, files in os.walk(build, followlinks=False):
        current = Path(current)
        links.extend(_rel(root, current / d) for d in dirs if _link(current / d))
        dirs[:] = [d for d in dirs if not _link(current / d)]
        for name in files:
            path = current / name
            if _link(path):
                links.append(_rel(root, path))
                continue
            st = path.stat(follow_symlinks=False)
            parts = path.relative_to(build).parts
            group = 'onnx_cache' if 'onnx-cache' in parts else 'models' if 'terrain-diffusion-models' in parts or parts[:2] == ('probe-inputs', 'models') else 'mods' if 'mods' in parts else 'world' if 'world' in parts or 'saves' in parts else 'other'
            totals[group] += st.st_size
            if (st.st_dev, st.st_ino) not in seen:
                unique[group] += st.st_size
                seen.add((st.st_dev, st.st_ino))
            top = '/'.join(parts[:2])
            folders[top] = folders.get(top, 0) + st.st_size
    largest = [{'path': 'build/' + k, 'logical_bytes': v} for k, v in sorted(folders.items(), key=lambda x: x[1], reverse=True)[:15]]
    return {'categories': {'logical_bytes': totals, 'unique_file_bytes': unique}, 'largest': largest, 'linked_paths_skipped': links[:15], 'linked_path_count': len(links)}

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('restore', 'pin', 'unpin'):
        sub.add_parser(name).add_argument('run')
    sub.add_parser('inventory')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    from probe_suite import exclusive_lock
    if args.command == 'inventory':
        result = inventory(root)
    else:
        run = root / args.run
        with exclusive_lock(root):
            if args.command == 'restore':
                result = restore(root, run)
            else:
                family = run.parent.name
                run = _run(root, family, run)
                path = run / PIN
                if args.command == 'pin':
                    _atomic(path, {'schema': SCHEMA, 'kind': 'probe-input-pin'})
                else:
                    path.unlink(missing_ok=True)
                result = {'run': _rel(root, run), 'pinned': args.command == 'pin'}
    print(json.dumps(result, indent=2))
if __name__ == '__main__':
    main()
