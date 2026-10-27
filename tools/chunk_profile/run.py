"""Profile fresh generation in a private copy of the installed client modpack."""
from __future__ import annotations

import argparse
import gzip
import importlib.util
import json
import os
import re
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/optimization_bench'))
sys.path.insert(0, str(ROOT / 'tools'))
from probe_suite import exclusive_lock, atomic_json
import launch
import psutil

WORK = ROOT / 'build/chunk-profile'
FLAGS = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


def save(path, obj):
    atomic_json(path, obj)


def helper():
    spec = importlib.util.spec_from_file_location('chunk_profile_nbt', ROOT / 'tools/river_planner/prepare-playtest.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def copy_checked(source, destination, manifest):
    if source.is_symlink() or (hasattr(source, 'is_junction') and source.is_junction()):
        raise RuntimeError(f'Linked input refused: {source}')
    if source.is_dir():
        for child in sorted(source.iterdir()):
            copy_checked(child, destination / child.name, manifest)
        return
    digest = launch.sha256(source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    if launch.sha256(destination) != digest:
        raise RuntimeError(f'Input changed during copy: {source}')
    manifest[destination.relative_to(manifest_root).as_posix()] = {'sha256': digest, 'bytes': source.stat().st_size}


manifest_root = None


def prepare(args):
    global manifest_root
    source = launch.DEFAULT_INSTANCE
    target = WORK / launch.safe_name(args.name)
    if target.exists():
        raise RuntimeError('Fixture already exists; choose a fresh name')
    if any(p.info['name'] in ('java.exe', 'javaw.exe') for p in psutil.process_iter(['name'])):
        raise RuntimeError('Java is running; inspect before preparing a fixture')
    pack = json.loads((source.parent / 'mmc-pack.json').read_text(encoding='utf-8'))
    versions = {c['uid']: c['version'] for c in pack['components']}
    if versions['net.minecraft'] != launch.MC or versions['net.neoforged'] != launch.NEO:
        raise RuntimeError('Installed versions differ from the validated direct launcher')
    launch.production_libraries(launch.DEFAULT_LIBRARIES)
    world = source / 'saves' / args.world if args.world else max((source / 'saves').glob('*/level.dat'), key=lambda p: p.stat().st_mtime).parent
    if not (world / 'level.dat').is_file():
        raise RuntimeError('Source world metadata absent')
    target.mkdir(parents=True)
    manifest_root = target
    files = {}
    for jar in sorted((source / 'mods').glob('*.jar')):
        copy_checked(jar, target / 'mods' / jar.name, files)
    for folder in ('config', 'defaultconfigs', 'terrain-diffusion-models'):
        if (source / folder).exists():
            copy_checked(source / folder, target / folder, files)
    copy_checked(source / 'options.txt', target / 'options.txt', files)
    options = dict(line.split(':', 1) for line in (source / 'options.txt').read_text(encoding='utf-8').splitlines() if ':' in line)
    missing_packs = []
    for name in json.loads(options.get('resourcePacks', '[]')):
        if not name.startswith('file/'):
            continue
        relative = name[5:]
        path = source / 'resourcepacks' / relative
        if path.exists():
            copy_checked(path, target / 'resourcepacks' / relative, files)
        else:
            missing_packs.append(relative)
    iris = dict(line.split('=', 1) for line in (source / 'config/iris.properties').read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#'))
    shader = iris.get('shaderPack')
    if shader:
        for name in (shader, shader + '.txt'):
            if (source / 'shaderpacks' / name).exists():
                copy_checked(source / 'shaderpacks' / name, target / 'shaderpacks' / name, files)
    copy_checked(source.parent / 'instance.cfg', target / 'source-instance.cfg', files)
    output_world = target / 'saves/chunk-profile'
    for folder in ('datapacks', 'serverconfig'):
        if (world / folder).exists():
            copy_checked(world / folder, output_world / folder, files)
    metadata = ['level.dat', 'data/terrain_diffusion_world_settings.dat',
                'data/terrain_diffusion_natural_rivers.dat', 'data/terrain_diffusion_streams_exact_v1.txt',
                'data/terrain_diffusion_hybrid_aquatic.properties', 'data/terrain_diffusion_river_preview.dat',
                'data/td-structure-placement.json']
    for name in metadata:
        if (world / name).exists():
            copy_checked(world / name, output_world / name, files)
    raw = gzip.decompress((output_world / 'level.dat').read_bytes())
    nbt = helper()
    fields = nbt.nbt_fields(raw)
    seed = fields[('Data', 'WorldGenSettings', 'seed')][3]
    edits = []
    player = fields.get(('Data', 'Player'))
    if player:
        kind, start, end, _ = player
        header = bytes([10]) + struct.pack('>H', 6) + b'Player'
        if raw[start-len(header):start] != header:
            raise RuntimeError('Unexpected Player tag header')
        edits.append((start-len(header), end, b''))
    kind, start, end, _ = fields[('Data', 'initialized')]
    if kind != 1:
        raise RuntimeError('Unexpected initialized tag')
    edits.append((start, end, b'\x00'))
    for start, end, replacement in sorted(edits, reverse=True):
        raw = raw[:start] + replacement + raw[end:]
    final_fields = nbt.nbt_fields(raw)
    if ('Data', 'Player') in final_fields or final_fields[('Data', 'WorldGenSettings', 'seed')][3] != seed:
        raise RuntimeError('Fresh world metadata verification failed')
    (output_world / 'level.dat').write_bytes(gzip.compress(raw, mtime=0))
    settings = nbt.nbt_fields(gzip.decompress((output_world / 'data/terrain_diffusion_world_settings.dat').read_bytes()))
    source_metadata = {name: launch.sha256(world / name) for name in metadata if (world / name).is_file()}
    overrides = {}
    if args.no_shaders:
        config = target / 'config/iris.properties'
        content, count = re.subn(r'(?m)^enableShaders=true$', 'enableShaders=false', config.read_text(encoding='utf-8'))
        if count != 1:
            raise RuntimeError('Expected exactly one enabled shader setting in source')
        config.write_text(content, encoding='utf-8')
        overrides['config/iris.properties'] = launch.sha256(config)
    save(target / 'input-manifest.json', {
        'source': str(source), 'source_world': str(world), 'versions': versions, 'seed': seed,
        'scale': settings[('data', 'scale')][3], 'active_mod_jars': len(list((target / 'mods').glob('*.jar'))),
        'files': files, 'source_world_metadata_sha256': source_metadata,
        'fresh_level_dat_sha256': launch.sha256(output_world / 'level.dat'),
        'excluded_world_data': ['regions', 'entities', 'poi', 'playerdata', 'Streams graphs', 'structure index'],
        'world_edits': ['remove copied Data.Player', 'initialized=0'],
        'input_overrides': overrides, 'shaders_disabled_for_diagnostic': args.no_shaders,
        'missing_selected_resourcepacks_in_source': missing_packs,
        'complete': True,
    })
    print(json.dumps({'prepared': str(target), 'mods': len(list((target / 'mods').glob('*.jar'))), 'seed': seed,
                      'scale': settings[('data', 'scale')][3]}, ensure_ascii=True), flush=True)


def build():
    base = ROOT / 'tools/chunk_profile'
    hashes = {p.relative_to(base).as_posix(): launch.sha256(p) for folder in ('java', 'resources')
              for p in sorted((base / folder).rglob('*')) if p.is_file()}
    cache = WORK / 'compiled-probe.json'
    if cache.is_file():
        prior = json.loads(cache.read_text(encoding='utf-8'))
        if prior['sources'] == hashes and Path(prior['jar']).is_file() and launch.sha256(Path(prior['jar'])) == prior['sha256']:
            return Path(prior['jar'])
    classes = WORK / f'classes-{time.time_ns()}'
    classes.mkdir(parents=True)
    sources = sorted((base / 'java').rglob('*.java'))
    if not sources:
        raise RuntimeError('Diagnostic source missing')
    result = subprocess.run([str(launch.DEFAULT_JAVA.with_name('javac.exe')), '-encoding', 'UTF-8', '--release', '21',
        '-proc:none', '-cp', launch.compile_classpath(launch.DEFAULT_LIBRARIES), '-d', str(classes), *map(str, sources)],
        capture_output=True, text=True, creationflags=FLAGS)
    (classes / 'compile.log').write_text(result.stdout + result.stderr, encoding='utf-8')
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    jar = WORK / f'chunk-profile-{time.time_ns()}.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for directory in (classes, base / 'resources'):
            for path in directory.rglob('*'):
                if path.is_file() and path.name != 'compile.log':
                    archive.write(path, path.relative_to(directory).as_posix())
    save(cache, {'sources': hashes, 'jar': str(jar), 'sha256': launch.sha256(jar)})
    return jar


def run(args):
    target = WORK / launch.safe_name(args.name)
    manifest = json.loads((target / 'input-manifest.json').read_text(encoding='utf-8'))
    if not manifest['complete'] or (target / 'process.json').exists():
        raise RuntimeError('Run requires a prepared, unrun fixture')
    if any(p.info['name'] in ('java.exe', 'javaw.exe') for p in psutil.process_iter(['name'])):
        raise RuntimeError('Java is running; inspect before starting the GPU workload')
    for relative, entry in manifest['files'].items():
        normalized = Path(relative).as_posix()
        expected = manifest['fresh_level_dat_sha256'] if normalized == 'saves/chunk-profile/level.dat' else manifest.get('input_overrides', {}).get(normalized, entry['sha256'])
        if launch.sha256(target / relative) != expected:
            raise RuntimeError(f'Prepared input changed: {relative}')
    jar = build()
    shutil.copy2(jar, target / 'mods' / jar.name)
    cfg = dict(line.split('=', 1) for line in (target / 'source-instance.cfg').read_text(encoding='utf-8').splitlines() if '=' in line)
    if cfg.get('JvmArgs') != '-XX:+UseZGC -XX:+ZGenerational':
        raise RuntimeError('Unexpected source JVM settings')
    runtime = argparse.Namespace(width=854, height=480, timeout=args.timeout,
        libraries=launch.DEFAULT_LIBRARIES, assets=launch.DEFAULT_ASSETS, java=launch.DEFAULT_JAVA,
        collector='zgc', heap_min_mib=int(cfg['MinMemAlloc']), heap_max_mib=int(cfg['MaxMemAlloc']), property=[
            'td.inference.metrics=true', 'chunkprofile.world=chunk-profile',
            f'chunkprofile.output={target / "receipt.json"}', f'chunkprofile.side={args.side}',
            f'chunkprofile.chunkX={args.chunk_x}', f'chunkprofile.chunkZ={args.chunk_z}',
            'chunkprofile.prefetch=8', f'chunkprofile.settleSeconds={args.settle}', 'chunkprofile.timeoutSeconds=900'])
    if args.regions:
        if not re.fullmatch(r'-?\d+,-?\d+(;-?\d+,-?\d+)*', args.regions):
            raise RuntimeError('Regions must be a semicolon-separated list of chunk X,Z pairs')
        runtime.property.append(f'chunkprofile.regions={args.regions}')
    _, native_jars, _ = launch.production_libraries(runtime.libraries)
    launch.extract_natives(native_jars, target / 'natives')
    command = launch.make_command(runtime, target, target / 'natives')
    (target / 'logs').mkdir(exist_ok=True)
    with (target / 'logs/launcher.log').open('w', encoding='utf-8') as log:
        process = subprocess.Popen(command, cwd=target, stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
    save(target / 'process.json', {'pid': process.pid, 'start_epoch': time.time(), 'diagnostic_sha256': launch.sha256(jar),
        'side': args.side, 'chunk_x': args.chunk_x, 'chunk_z': args.chunk_z, 'prefetch': 8,
        'regions': args.regions, 'shaders_disabled_for_diagnostic': manifest.get('shaders_disabled_for_diagnostic', False),
        'heap_min_mib':runtime.heap_min_mib, 'heap_max_mib':runtime.heap_max_mib})
    print(f'Launched isolated chunk profile PID {process.pid}', flush=True)
    gpu = None
    gpu_stream = (target / 'gpu.csv').open('w', encoding='utf-8')
    try:
        gpu = subprocess.Popen(['nvidia-smi', '--query-gpu=timestamp,utilization.gpu,utilization.memory,memory.used,power.draw',
            '--format=csv,noheader,nounits', '--loop=1'], stdout=gpu_stream, stderr=subprocess.DEVNULL, creationflags=FLAGS)
    except OSError:
        pass
    started = time.monotonic()
    watched = psutil.Process(process.pid)
    resources = (target / 'resources.csv').open('w', encoding='utf-8')
    resources.write('epoch,elapsed,process_cpu_seconds,rss,available_ram\n')
    last_report = -60
    timed_out = False
    try:
        while process.poll() is None:
            elapsed = time.monotonic() - started
            if elapsed > args.timeout:
                timed_out = True
                dump = subprocess.run([str(launch.DEFAULT_JAVA.with_name('jcmd.exe')), str(process.pid), 'Thread.print'],
                    capture_output=True, creationflags=FLAGS, timeout=30)
                (target / 'timeout-threads.txt').write_bytes(dump.stdout + dump.stderr)
                process.terminate()
                process.wait(timeout=30)
                break
            try:
                resources.write(f'{time.time()},{elapsed:.3f},{sum(watched.cpu_times()[:2])},{watched.memory_info().rss},{psutil.virtual_memory().available}\n')
                resources.flush()
            except psutil.NoSuchProcess:
                pass
            if elapsed-last_report >= 60:
                progress = target / 'progress.json'
                info = json.loads(progress.read_text(encoding='utf-8')) if progress.exists() else {'stage':'client_startup'}
                print(json.dumps({'elapsed_s':round(elapsed), 'progress':info}, ensure_ascii=True), flush=True)
                last_report = elapsed
            time.sleep(1)
    finally:
        resources.close()
        if gpu is not None and gpu.poll() is None:
            gpu.terminate()
            gpu.wait(timeout=10)
        gpu_stream.close()
    receipt = target / 'receipt.json'
    value = json.loads(receipt.read_text(encoding='utf-8')) if receipt.exists() else None
    unchanged = all(launch.sha256(Path(manifest['source_world']) / name) == digest for name,digest in manifest['source_world_metadata_sha256'].items())
    result = {'exit_code':process.returncode, 'timed_out':timed_out, 'receipt':value,
              'source_world_metadata_unchanged':unchanged}
    save(target / 'launch-result.json', result)
    print(json.dumps({'exit_code':process.returncode,'timed_out':timed_out,'receipt_status':None if value is None else value.get('status'),
                      'source_world_metadata_unchanged':unchanged}), flush=True)
    if process.returncode or timed_out or value is None or value.get('status') != 'success' or not unchanged:
        raise RuntimeError('Profile run did not pass; inspect retained logs and receipt')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['prepare','run'])
    p.add_argument('name')
    p.add_argument('--world')
    p.add_argument('--no-shaders', action='store_true', help='disable only Iris shaders in the prepared private clone')
    p.add_argument('--regions', help='ordered chunk origin pairs, e.g. -15,-64;1024,1024')
    p.add_argument('--side',type=int,choices=range(1,17),default=8)
    p.add_argument('--chunk-x',type=int,default=1024)
    p.add_argument('--chunk-z',type=int,default=1024)
    p.add_argument('--settle',type=int,choices=range(0,301),default=45)
    p.add_argument('--timeout',type=int,default=1500)
    args=p.parse_args()
    WORK.mkdir(parents=True,exist_ok=True)
    with exclusive_lock(ROOT):
        (prepare if args.action=='prepare' else run)(args)


if __name__=='__main__':
    main()
