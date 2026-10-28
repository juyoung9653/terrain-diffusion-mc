"""Run isolated decoder A/B/B/A measurements with the CUDA provider required.

Individual node placement is still chosen by ONNX Runtime. Receipts record
measurement and numerical differences, not terrain-compatibility acceptance.
"""
from __future__ import annotations
import argparse, hashlib, json, math, re, shutil, struct, subprocess, sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from probe_suite import exclusive_lock
ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).with_name('OnnxDecoderBenchmark.java')
N = 5 * 256 * 256 * 4
SAFE_ID = re.compile('[A-Za-z0-9_-]+\\Z')

def sha(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()

def load_inputs(root: Path, synthetic: bool) -> tuple[list[dict], str]:
    m = root / 'manifest.json'
    if m.is_file():
        doc = json.loads(m.read_text('utf-8'))
        cases = doc.get('cases', [])
        mode = doc.get('inputMode', 'capture')
        if not cases:
            raise ValueError('manifest has no cases')
        out = []
        seen = set()
        for c in cases:
            if not SAFE_ID.fullmatch(c['id']):
                raise ValueError(f"unsafe case id {c['id']!r}")
            if c['id'] in seen:
                raise ValueError(f"duplicate case id {c['id']!r}")
            seen.add(c['id'])
            x = (root / c['x']).resolve()
            n = (root / c['noiseLabels']).resolve()
            if root.resolve() not in x.parents or root.resolve() not in n.parents:
                raise ValueError('manifest path escapes input directory')
            if x.stat().st_size != N or n.stat().st_size != 4:
                raise ValueError(f"bad fp32 sizes for {c['id']}")
            for input_file in (x, n):
                if any(not math.isfinite(v[0]) for v in struct.iter_unpack('<f', input_file.read_bytes())):
                    raise ValueError(f"nonfinite input: {input_file}")
            out.append({'id': c['id'], 'x': x, 'noise': n})
        return (out, mode)
    if not synthetic:
        raise ValueError(f'missing {m}; pass --synthetic-fallback to create labelled fallback inputs')
    root.mkdir(parents=True, exist_ok=True)
    x = root / 'synthetic-x.bin'
    n = root / 'synthetic-noise_labels.bin'
    if not x.exists():
        x.write_bytes(b''.join((struct.pack('<f', (i * 1103515245 + 12345 & 65535) / 32768 - 1) for i in range(N // 4))))
        n.write_bytes(struct.pack('<f', 0.0))
    m.write_text(json.dumps({'inputMode': 'synthetic', 'cases': [{'id': 'synthetic', 'x': x.name, 'noiseLabels': n.name}]}, indent=2) + '\n')
    return ([{'id': 'synthetic', 'x': x, 'noise': n}], 'synthetic')

def compare(a: Path, b: Path) -> dict:
    import struct, math
    av = a.read_bytes()
    bv = b.read_bytes()
    if len(av) != 256 * 256 * 4 or len(bv) != len(av):
        raise ValueError('expected two 256x256 float32 outputs')
    vals = len(av) // 4
    aa = struct.unpack('<%df' % vals, av)
    bb = struct.unpack('<%df' % vals, bv)
    dif = [abs(x - y) for x, y in zip(aa, bb)]
    finite = [d for x, y, d in zip(aa, bb, dif) if math.isfinite(x) and math.isfinite(y)]
    return {'values': vals, 'bitDiff': sum((struct.pack('<f', x) != struct.pack('<f', y) for x, y in zip(aa, bb))), 'maxAbs': max(finite) if finite else None, 'mae': sum(finite) / len(finite) if finite else None, 'rmse': (sum((x * x for x in finite)) / len(finite)) ** 0.5 if finite else None, 'nonfinite': vals - len(finite)}

def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument('--model', type=Path, required=True)
    p.add_argument('--candidate', type=Path, action='append', default=[])
    p.add_argument('--inputs', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--ort-jar', type=Path, required=True)
    p.add_argument('--java-home', type=Path, default=Path('C:\\Program Files\\Java\\jdk-21'))
    p.add_argument('--warmup', type=int, default=4)
    p.add_argument('--runs', type=int, default=12)
    p.add_argument('--candidate-algorithm', choices=('HEURISTIC', 'EXHAUSTIVE'), default='HEURISTIC')
    p.add_argument('--synthetic-fallback', action='store_true')
    p.add_argument('--timeout', type=int, default=300)
    a = p.parse_args()
    if a.warmup < 1 or a.runs < 1 or a.timeout < 1:
        raise SystemExit('warmup, runs and timeout must be positive')
    if a.output.exists():
        raise SystemExit(f'refusing existing output {a.output}')
    if not a.candidate:
        raise SystemExit('at least one --candidate is required')
    for f in [a.model, *a.candidate, a.ort_jar, SOURCE, a.java_home / 'bin/javac.exe', a.java_home / 'bin/java.exe']:
        if not f.is_file():
            raise SystemExit(f'missing {f}')
    cases, mode = load_inputs(a.inputs, a.synthetic_fallback)
    a.output.mkdir(parents=True)
    classes = a.output / 'classes'
    classes.mkdir()
    frozen_source = a.output / SOURCE.name
    shutil.copy2(SOURCE, frozen_source)
    source_hash = sha(frozen_source)
    input_hashes = {c['id']: {'xSha256': sha(c['x']), 'noiseLabelsSha256': sha(c['noise'])} for c in cases}
    manifest_hash = sha(a.inputs / 'manifest.json')
    with exclusive_lock(ROOT):
        compile_result = subprocess.run([str(a.java_home / 'bin/javac.exe'), '--release', '21', '-cp', str(a.ort_jar), '-d', str(classes), str(frozen_source)], text=True, capture_output=True, cwd=ROOT, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        (a.output / 'compile.log').write_text(compile_result.stdout + compile_result.stderr, 'utf-8')
        compile_result.check_returncode()
        variants = [('baseline', a.model, 'HEURISTIC')] + [(f'candidate{i + 1}', m, a.candidate_algorithm) for i, m in enumerate(a.candidate)]
        order = []
        for v in variants[1:] or variants[:1]:
            order += [variants[0], v, v, variants[0]]
        receipts = []
        for seq, (name, model, alg) in enumerate(order):
            d = a.output / f'run-{seq:02d}-{name}'
            d.mkdir()
            result = d / 'result.json'
            cmd = [str(a.java_home / 'bin/java.exe'), '-cp', f'{classes};{a.ort_jar}', 'OnnxDecoderBenchmark', '--model', str(model), '--algorithm', alg, '--variant', name, '--warmup', str(a.warmup), '--runs', str(a.runs), '--out', str(d), '--result', str(result)]
            for c in cases:
                cmd += ['--case', f"{c['id']}|{c['x']}|{c['noise']}"]
            start = time.time()
            try:
                q = subprocess.run(cmd, cwd=ROOT, text=True, capture_output=True, timeout=a.timeout, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                stdout, stderr = (q.stdout, q.stderr)
            except subprocess.TimeoutExpired as exc:
                stdout = exc.stdout.decode('utf-8', errors='replace') if isinstance(exc.stdout, bytes) else exc.stdout or ''
                stderr = exc.stderr.decode('utf-8', errors='replace') if isinstance(exc.stderr, bytes) else exc.stderr or ''
                stderr += f'\nTIMEOUT after {a.timeout}s\n'
                q = None
            (d / 'stdout.log').write_text(stdout + stderr, 'utf-8')
            if q is None or q.returncode:
                failure = {'kind': 'decoder_cuda_abba', 'status': 'failed', 'run': seq, 'name': name, 'algorithm': alg, 'modelSha256': sha(model), 'stdoutSha256': sha(d / 'stdout.log'), 'timeout': q is None, 'exitCode': None if q is None else q.returncode, 'inputHashes': input_hashes, 'inputManifestSha256': manifest_hash, 'runs': receipts, 'ortJarSha256': sha(a.ort_jar), 'sourceSha256': sha(SOURCE)}
                (a.output / 'receipt.json').write_text(json.dumps(failure, indent=2) + '\n')
                raise RuntimeError(f"CUDA run failed ({name}); see {d / 'stdout.log'}")
            receipts.append({'sequence': seq, 'directory': d.name, 'name': name, 'algorithm': alg, 'modelSha256': sha(model), 'result': json.loads(result.read_text()), 'stdoutSha256': sha(d / 'stdout.log'), 'elapsedSeconds': time.time() - start, 'outputs': {c['id']: sha(d / (c['id'] + '.fp32')) for c in cases}})
        if input_hashes != {c['id']: {'xSha256': sha(c['x']), 'noiseLabelsSha256': sha(c['noise'])} for c in cases}:
            raise RuntimeError('input files changed during benchmark')
        if sha(a.inputs / 'manifest.json') != manifest_hash:
            raise RuntimeError('input manifest changed during benchmark')
        parity = []
        for r in receipts[1:]:
            parity.append({'sequence': r['sequence'], 'variant': r['name'], 'againstSequence': 0, 'cases': {c['id']: compare(a.output / 'run-00-baseline' / (c['id'] + '.fp32'), a.output / r['directory'] / (c['id'] + '.fp32')) for c in cases}})
        within_session_stable = all(c['repeatStability']['changedRepeats'] == 0 and c['repeatStability']['nonfinitePairs'] == 0 for r in receipts for c in r['result']['cases'])
        receipt = {'kind': 'decoder_cuda_abba', 'status': 'measured', 'allOutputsFinite': all((c['nonfinite'] == 0 for r in parity for c in r['cases'].values())), 'withinSessionBitStable': within_session_stable, 'inputMode': mode, 'inputManifestSha256': manifest_hash, 'inputs': input_hashes, 'ortJarSha256': sha(a.ort_jar), 'sourceSha256': source_hash, 'javaExecutableSha256': sha(a.java_home / 'bin/java.exe'), 'warmup': a.warmup, 'runsPerCase': a.runs, 'runs': receipts, 'parityPerRun': parity, 'createdAt': time.time()}
        (a.output / 'receipt.json').write_text(json.dumps(receipt, indent=2, allow_nan=False) + '\n')
    print(a.output / 'receipt.json')
    return 0
if __name__ == '__main__':
    raise SystemExit(main())
