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
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/optimization_bench'))
sys.path.insert(0, str(ROOT / 'tools'))
from probe_suite import exclusive_lock, atomic_json
import launch
import psutil

WORK = ROOT / 'build/chunk-profile'
FLAGS = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
FP16_DECODER_SHA256 = 'af32fdd6955f15611c5fe4696f5a6c96dd008657b361f391db1c948739d125ee'
FP16_DECODER_BYTES = 56077790


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


def td_identity(path):
    """Require an actual TD NeoForge artifact before it can replace a cloned mod."""
    if not path.is_file() or path.suffix.lower() != '.jar':
        raise RuntimeError(f'--td-jar must name a regular JAR: {path}')
    try:
        with zipfile.ZipFile(path) as archive:
            text = archive.read('META-INF/neoforge.mods.toml').decode('utf-8')
    except (KeyError, OSError, zipfile.BadZipFile) as failure:
        raise RuntimeError(f'--td-jar is not a readable NeoForge mod JAR: {path}') from failure
    if not re.search(r'(?m)^\s*modId\s*=\s*"terrain_diffusion_mc"\s*$', text):
        raise RuntimeError(f'--td-jar does not declare modId terrain_diffusion_mc: {path}')
    return {'path': str(path.resolve()), 'sha256': launch.sha256(path), 'bytes': path.stat().st_size}


def streams_identity(path):
    """Require the installed StreamsReflowing mod, rather than excluding by a loose filename."""
    if not path.is_file() or path.suffix.lower() != '.jar' or 'streamsreflowing' not in path.name.lower():
        raise RuntimeError(f'Expected a StreamsReflowing JAR filename: {path}')
    try:
        with zipfile.ZipFile(path) as archive:
            metadata = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode('utf-8'))
    except (KeyError, OSError, UnicodeDecodeError, tomllib.TOMLDecodeError, zipfile.BadZipFile) as failure:
        raise RuntimeError(f'StreamsReflowing JAR has unreadable NeoForge metadata: {path}') from failure
    mods = metadata.get('mods')
    if not isinstance(mods, list) or not any(isinstance(mod, dict) and mod.get('modId') == 'streamsreflowing' for mod in mods):
        raise RuntimeError(f'StreamsReflowing JAR does not declare modId streamsreflowing: {path}')
    return {'source_filename': path.name, 'path': str(path.resolve()),
            'sha256': launch.sha256(path), 'bytes': path.stat().st_size,
            'mod_id': 'streamsreflowing'}


def fp16_decoder_identity(path):
    """Accept only the reviewed FP16 decoder artifact used by this benchmark."""
    if not path.is_file() or path.suffix.lower() != '.onnx':
        raise RuntimeError(f'--decoder-fp16 must name a regular .onnx file: {path}')
    size = path.stat().st_size
    if size != FP16_DECODER_BYTES:
        raise RuntimeError(f'--decoder-fp16 has unexpected byte length: {size}')
    digest = launch.sha256(path)
    if digest != FP16_DECODER_SHA256:
        raise RuntimeError('--decoder-fp16 does not match the validated FP16 decoder SHA-256')
    return {'path': str(path.resolve()), 'sha256': digest, 'bytes': size,
            'validated_sha256': FP16_DECODER_SHA256}


def set_property(path, key, value):
    """Change one clone-local properties entry while refusing ambiguous input."""
    content = path.read_text(encoding='utf-8')
    pattern = re.compile(rf'(?m)^(\s*{re.escape(key)}\s*=\s*)[^\r\n]*$')
    content, count = pattern.subn(lambda match: match[1] + value, content)
    if count > 1:
        raise RuntimeError(f'Expected at most one {key} setting in clone config')
    if count == 0:
        if content and not content.endswith(('\n', '\r')):
            content += '\n'
        content += f'{key}={value}\n'
    path.write_text(content, encoding='utf-8')


def property_value(path, key):
    """Read one active properties entry without treating duplicate input as valid."""
    pattern = re.compile(rf'^\s*{re.escape(key)}\s*=\s*([^\r\n]*)$')
    values = [match[1].strip() for line in path.read_text(encoding='utf-8').splitlines()
              if (match := pattern.match(line))]
    if len(values) > 1:
        raise RuntimeError(f'Expected at most one {key} setting in clone config')
    return values[0] if values else None


def disable_natural_rivers(path):
    """Change only the clone's persisted natural-river enabled byte, preserving its identity fields."""
    raw = gzip.decompress(path.read_bytes())
    nbt = helper()
    fields = nbt.nbt_fields(raw)
    enabled = fields.get(('data', 'enabled'))
    identity = {key: fields.get(('data', key)) for key in ('version', 'seed', 'scale')}
    if enabled is None or enabled[0] != 1 or enabled[3] != 1 or any(value is None for value in identity.values()):
        raise RuntimeError('Expected an enabled persisted natural-river profile in source world metadata')
    start, end = enabled[1], enabled[2]
    updated = raw[:start] + b'\x00' + raw[end:]
    verified = nbt.nbt_fields(updated)
    if verified[('data', 'enabled')][0] != 1 or verified[('data', 'enabled')][3] != 0:
        raise RuntimeError('Natural-river clone disable verification failed')
    if any(verified[('data', key)][3] != value[3] for key, value in identity.items()):
        raise RuntimeError('Natural-river identity changed while disabling clone profile')
    path.write_bytes(gzip.compress(updated, mtime=0))
    return {'path': 'data/terrain_diffusion_natural_rivers.dat', 'previous_enabled': 1,
            'enabled': 0, 'version': identity['version'][3], 'seed': identity['seed'][3],
            'scale': identity['scale'][3], 'sha256': launch.sha256(path)}


manifest_root = None


def prepare(args):
    global manifest_root
    source = launch.DEFAULT_INSTANCE
    if args.decoder_fp16 and args.decoder_precision:
        raise RuntimeError('--decoder-fp16 cannot be combined with --decoder-precision')
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
    source_jars = sorted((source / 'mods').glob('*.jar'))
    streams_sources = [jar for jar in source_jars if 'streamsreflowing' in jar.name.lower()]
    if len(streams_sources) != 1:
        raise RuntimeError(f'Expected exactly one StreamsReflowing JAR filename, found: {[p.name for p in streams_sources]}')
    source_streams = streams_identity(streams_sources[0])
    for jar in source_jars:
        copy_checked(jar, target / 'mods' / jar.name, files)
    source_files = json.loads(json.dumps(files))
    streams_ablation = {'enabled': bool(args.without_streams), 'removed_artifact': None}
    if args.without_streams:
        staged = target / 'mods' / streams_sources[0].name
        relative = staged.relative_to(target).as_posix()
        if not staged.is_file() or launch.sha256(staged) != source_streams['sha256']:
            raise RuntimeError('Staged StreamsReflowing artifact changed before clone-only exclusion')
        staged.unlink()
        files.pop(relative, None)
        streams_ablation['removed_artifact'] = source_streams
    td_sources = [jar for jar in source_jars
                  if jar.name.lower().endswith('.jar') and 'terrain-diffusion-mc' in jar.name.lower()]
    if len(td_sources) != 1:
        raise RuntimeError(f'Expected exactly one active Terrain Diffusion JAR, found: {[p.name for p in td_sources]}')
    source_td = {'source_filename': td_sources[0].name, **td_identity(td_sources[0])}
    selected_td = {'kind': 'installed', **source_td}
    if args.td_jar:
        replacement = Path(args.td_jar).resolve()
        candidate = td_identity(replacement)
        if replacement == td_sources[0].resolve():
            raise RuntimeError('--td-jar must be a different built artifact; omit it for the installed baseline')
        destination = target / 'mods' / td_sources[0].name
        shutil.copy2(replacement, destination)
        if launch.sha256(destination) != candidate['sha256']:
            raise RuntimeError('TD replacement changed while copying')
        relative = destination.relative_to(target).as_posix()
        files[relative] = {'sha256': candidate['sha256'], 'bytes': candidate['bytes']}
        selected_td = {'kind': 'replacement', 'source_filename': td_sources[0].name, **candidate}
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
    excluded_streams_marker = 'data/terrain_diffusion_streams_exact_v1.txt'
    for name in metadata:
        if args.without_streams and name == excluded_streams_marker:
            continue
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
    if args.without_streams:
        natural = output_world / 'data/terrain_diffusion_natural_rivers.dat'
        if not natural.is_file():
            raise RuntimeError('Streams ablation requires persisted natural-river world metadata')
        natural_profile = disable_natural_rivers(natural)
        relative = natural.relative_to(target).as_posix()
        overrides[relative] = natural_profile['sha256']
        streams_ablation['natural_river_profile'] = natural_profile
        marker_source = world / excluded_streams_marker
        streams_ablation['excluded_exact_marker'] = {
            'path': excluded_streams_marker, 'present_in_source': marker_source.is_file(),
            'source_sha256': launch.sha256(marker_source) if marker_source.is_file() else None,
            'staged': False,
        }
    config = target / 'config' / 'terrain-diffusion-mc.properties'
    if not config.is_file():
        raise RuntimeError('Terrain Diffusion configuration is absent from clone')
    precision_value = property_value(config, 'inference.decoder_precision')
    if precision_value not in (None, 'fp32', 'fp16'):
        raise RuntimeError(f'Unsupported inference.decoder_precision in clone config: {precision_value!r}')
    precision = {'mode': 'fp32', 'decoder_candidate': None, 'selection': 'installed-default'}
    if args.decoder_fp16:
        candidate = fp16_decoder_identity(Path(args.decoder_fp16).resolve())
        destination = target / 'terrain-diffusion-models' / 'decoder_model_fp16.onnx'
        shutil.copy2(candidate['path'], destination)
        if launch.sha256(destination) != candidate['sha256']:
            raise RuntimeError('FP16 decoder changed while copying')
        relative = destination.relative_to(target).as_posix()
        files[relative] = {'sha256': candidate['sha256'], 'bytes': candidate['bytes']}
        set_property(config, 'inference.decoder_precision', 'fp16')
        overrides['config/terrain-diffusion-mc.properties'] = launch.sha256(config)
        precision = {'mode': 'fp16', 'decoder_candidate': {
            'source': candidate,
            'staged_path': relative,
            'selected_config': 'inference.decoder_precision=fp16',
        }, 'selection': 'candidate-override'}
    elif args.decoder_precision == 'fp32':
        set_property(config, 'inference.decoder_precision', 'fp32')
        overrides['config/terrain-diffusion-mc.properties'] = launch.sha256(config)
        precision = {'mode': 'fp32', 'decoder_candidate': None, 'selection': 'forced-fp32'}
    elif precision_value == 'fp16':
        source_candidate = source / 'terrain-diffusion-models' / 'decoder_model_fp16.onnx'
        candidate = fp16_decoder_identity(source_candidate)
        destination = target / 'terrain-diffusion-models' / 'decoder_model_fp16.onnx'
        fp16_decoder_identity(destination)
        precision = {'mode': 'fp16', 'decoder_candidate': {
            'source': candidate,
            'staged_path': destination.relative_to(target).as_posix(),
            'selected_config': 'inference.decoder_precision=fp16',
        }, 'selection': 'installed-fp16'}
    elif precision_value == 'fp32':
        precision = {'mode': 'fp32', 'decoder_candidate': None, 'selection': 'installed-fp32'}
    if args.no_shaders:
        config = target / 'config/iris.properties'
        content, count = re.subn(r'(?m)^enableShaders=true$', 'enableShaders=false', config.read_text(encoding='utf-8'))
        if count != 1:
            raise RuntimeError('Expected exactly one enabled shader setting in source')
        config.write_text(content, encoding='utf-8')
        overrides['config/iris.properties'] = launch.sha256(config)
    active_mod_jars = len(list((target / 'mods').glob('*.jar')))
    expected_active_mod_jars = len(source_jars) - int(args.without_streams)
    if active_mod_jars != expected_active_mod_jars:
        raise RuntimeError(f'Unexpected staged mod count: {active_mod_jars}, expected {expected_active_mod_jars}')
    save(target / 'input-manifest.json', {
        'source': str(source), 'source_world': str(world), 'versions': versions, 'seed': seed,
        'scale': settings[('data', 'scale')][3], 'active_mod_jars': active_mod_jars,
        'expected_active_mod_jars': expected_active_mod_jars,
        'files': files, 'source_files': source_files,
        'streams_ablation': streams_ablation,
        'source_terrain_diffusion_artifact': source_td,
        'terrain_diffusion_artifact': selected_td,
        'source_world_metadata_sha256': source_metadata,
        'fresh_level_dat_sha256': launch.sha256(output_world / 'level.dat'),
        'excluded_world_data': ['regions', 'entities', 'poi', 'playerdata', 'Streams graphs', 'structure index'],
        'world_edits': ['remove copied Data.Player', 'initialized=0']
            + (['natural-river data.enabled=0 for Streams removal ablation',
                'exclude Streams exact-terrain marker'] if args.without_streams else []),
        'input_overrides': overrides, 'shaders_disabled_for_diagnostic': args.no_shaders,
        'decoder_precision': precision,
        'missing_selected_resourcepacks_in_source': missing_packs,
        'complete': True,
    })
    print(json.dumps({'prepared': str(target), 'mods': active_mod_jars, 'seed': seed,
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


def requested_thread_dump(target, pid, mtime_ns):
    """Capture a diagnostic dump only after the operator changes the clone-local request file."""
    output = target / f'threads-request-{mtime_ns}.txt'
    command = [str(launch.DEFAULT_JAVA.with_name('jcmd.exe')), str(pid), 'Thread.print']
    record = {'mtime_ns': mtime_ns, 'file': output.name, 'command': 'jcmd Thread.print'}
    def output_bytes(value):
        return value if isinstance(value, bytes) else b'' if value is None else str(value).encode('utf-8', errors='replace')
    try:
        result = subprocess.run(command, capture_output=True, creationflags=FLAGS, timeout=30)
        payload = output_bytes(result.stdout) + output_bytes(result.stderr)
        record['exit_code'] = result.returncode
    except subprocess.TimeoutExpired as failure:
        payload = output_bytes(failure.stdout) + output_bytes(failure.stderr)
        record['error'] = 'TimeoutExpired'
    except OSError as failure:
        payload = str(failure).encode('utf-8', errors='replace')
        record['error'] = failure.__class__.__name__
    try:
        output.write_bytes(payload)
    except OSError as failure:
        record['write_error'] = failure.__class__.__name__
    return record


def run(args):
    target = WORK / launch.safe_name(args.name)
    manifest = json.loads((target / 'input-manifest.json').read_text(encoding='utf-8'))
    if not manifest['complete'] or (target / 'process.json').exists():
        raise RuntimeError('Run requires a prepared, unrun fixture')
    if args.streams_settle_seconds and not args.request_telemetry:
        raise RuntimeError('--streams-settle-seconds requires --request-telemetry for the native trace')
    if any(p.info['name'] in ('java.exe', 'javaw.exe') for p in psutil.process_iter(['name'])):
        raise RuntimeError('Java is running; inspect before starting the GPU workload')
    for relative, entry in manifest['files'].items():
        normalized = Path(relative).as_posix()
        expected = manifest['fresh_level_dat_sha256'] if normalized == 'saves/chunk-profile/level.dat' else manifest.get('input_overrides', {}).get(normalized, entry['sha256'])
        if launch.sha256(target / relative) != expected:
            raise RuntimeError(f'Prepared input changed: {relative}')
    expected_mods = manifest.get('expected_active_mod_jars', manifest.get('active_mod_jars'))
    if len(list((target / 'mods').glob('*.jar'))) != expected_mods:
        raise RuntimeError('Prepared staged mod set changed')
    streams_ablation = manifest.get('streams_ablation', {'enabled': False, 'removed_artifact': None})
    if streams_ablation.get('enabled'):
        removed = streams_ablation.get('removed_artifact')
        if not isinstance(removed, dict) or removed.get('mod_id') != 'streamsreflowing':
            raise RuntimeError('Streams ablation provenance is incomplete')
        if (target / 'mods' / removed.get('source_filename', '')).exists():
            raise RuntimeError('StreamsReflowing artifact unexpectedly present in ablation clone')
    precision = manifest.get('decoder_precision', {'mode': 'fp32', 'decoder_candidate': None,
                                                   'selection': 'legacy-default'})
    if precision.get('mode') == 'fp16':
        candidate = precision.get('decoder_candidate')
        if not isinstance(candidate, dict) or candidate.get('selected_config') != 'inference.decoder_precision=fp16':
            raise RuntimeError('FP16 decoder provenance is incomplete')
        staged = candidate.get('staged_path')
        if staged != 'terrain-diffusion-models/decoder_model_fp16.onnx':
            raise RuntimeError('FP16 decoder staged path is invalid')
        fp16_decoder_identity(target / staged)
    elif precision.get('mode') != 'fp32' or precision.get('decoder_candidate') is not None:
        raise RuntimeError('Decoder precision manifest is invalid')
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
            'chunkprofile.prefetch=8', f'chunkprofile.settleSeconds={args.settle}',
            f'chunkprofile.streamsSettleSeconds={args.streams_settle_seconds}',
            f'chunkprofile.timeoutSeconds={args.phase_timeout}'])
    if args.request_telemetry:
        runtime.property.append('td.inference.requestTelemetry=true')
    if args.streams_ground_marine is not None:
        runtime.property.append(f'td.streams.groundMarine={args.streams_ground_marine}')
    if args.streams_batch_sampling is not None:
        runtime.property.append(f'td.streams.batchSampling={args.streams_batch_sampling}')
    if args.streams_ground_marine_audit:
        runtime.property.append('td.streams.groundMarineAudit=true')
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
        'request_telemetry': args.request_telemetry,
        'streams_settle_seconds': args.streams_settle_seconds,
        'phase_timeout_seconds': args.phase_timeout,
        'streams_ground_marine': args.streams_ground_marine,
        'streams_batch_sampling': args.streams_batch_sampling,
        'streams_ground_marine_audit': args.streams_ground_marine_audit,
        'streams_ablation': streams_ablation,
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
    request_file = target / 'dump-threads.request'
    observed_request_mtime = request_file.stat().st_mtime_ns if request_file.exists() else None
    thread_dump_requests = []
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
            try:
                request_mtime = request_file.stat().st_mtime_ns if request_file.exists() else None
                if request_mtime is not None and request_mtime != observed_request_mtime:
                    record = requested_thread_dump(target, process.pid, request_mtime)
                    thread_dump_requests.append(record)
                    with (target / 'logs' / 'thread-dump-requests.log').open('a', encoding='utf-8') as log:
                        log.write(json.dumps(record, ensure_ascii=True) + '\n')
                observed_request_mtime = request_mtime
            except OSError as failure:
                thread_dump_requests.append({'error': failure.__class__.__name__, 'operation': 'request_file_watch'})
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
              'source_world_metadata_unchanged':unchanged, 'thread_dump_requests':thread_dump_requests}
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
    p.add_argument('--td-jar', help='built Terrain Diffusion NeoForge JAR to use only in this new clone')
    p.add_argument('--decoder-fp16', help='reviewed FP16 decoder .onnx used only in this new clone')
    p.add_argument('--decoder-precision', choices=('fp32',),
                   help='force this decoder precision only in a new clone')
    p.add_argument('--request-telemetry', action='store_true',
                   help='opt in to TD request-shape telemetry for this cloned diagnostic run')
    p.add_argument('--without-streams', action='store_true',
                   help='prepare a clone-only StreamsReflowing removal ablation; source instance is unchanged')
    p.add_argument('--streams-ground-marine', choices=('true', 'false'),
                   help='clone-launch-only td.streams.groundMarine setting')
    p.add_argument('--streams-batch-sampling', choices=('true', 'false'),
                   help='clone-launch-only td.streams.batchSampling setting')
    p.add_argument('--streams-ground-marine-audit', action='store_true',
                   help='require zero mismatches from the opt-in Streams ground/marine audit; changes workload')
    p.add_argument('--streams-settle-seconds', type=int, default=0,
                   help='require observed TD and native Streams quiescence for this long; requires --request-telemetry')
    p.add_argument('--phase-timeout', type=int, default=900,
                   help='per-phase diagnostic timeout in seconds (30 through 1800; default 900)')
    p.add_argument('--regions', help='ordered chunk origin pairs, e.g. -15,-64;1024,1024')
    p.add_argument('--side',type=int,choices=range(1,17),default=8)
    p.add_argument('--chunk-x',type=int,default=1024)
    p.add_argument('--chunk-z',type=int,default=1024)
    p.add_argument('--settle',type=int,choices=range(0,301),default=45)
    p.add_argument('--timeout',type=int,default=1500)
    args=p.parse_args()
    if not 0 <= args.streams_settle_seconds <= 900:
        p.error('--streams-settle-seconds must be from 0 through 900')
    if not 30 <= args.phase_timeout <= 1800:
        p.error('--phase-timeout must be from 30 through 1800')
    WORK.mkdir(parents=True,exist_ok=True)
    with exclusive_lock(ROOT):
        (prepare if args.action=='prepare' else run)(args)


if __name__=='__main__':
    main()
