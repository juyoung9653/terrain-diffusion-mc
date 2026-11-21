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
from build_evidence import source_fingerprint
from biome_review.contract import changed_biome_resource_ids, capture_tool_fingerprint
from biome_review.contract import fixed_camera_poses, matches_site_views
from biome_review.budget import check_budget, scope as review_scope
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


def active_java_workload():
    for process in psutil.process_iter(['name']):
        if (process.info['name'] or '').lower() not in ('java.exe', 'javaw.exe'):
            continue
        try:
            argv = process.cmdline()
        except psutil.NoSuchProcess:
            continue
        except psutil.AccessDenied:
            return True
        # Oracle's Windows javapath shim can linger after a version query.
        # Any VM flags, unknown command, class, or jar still blocks the run.
        if argv[1:] not in (['-version'], ['--version']):
            return True
    return False


def _generation_trace_sites(value):
    """Parse a small, normal-generation trace selector without a review plan."""
    if value is None:
        return []
    if not re.fullmatch(r'-?\d+,-?\d+(;-?\d+,-?\d+){0,7}', value):
        raise RuntimeError('--generation-trace-sites requires 1..8 x,z pairs separated by semicolons')
    return [tuple(map(int, pair.split(','))) for pair in value.split(';')]


def _copy_streams_cache(source_world, output_world, files):
    """Copy saved Streams routing inputs to a fresh clone and prove every byte."""
    source_cache = source_world / 'streamsreflowing'
    if not source_cache.is_dir():
        raise RuntimeError('--copy-streams-cache requires source world streamsreflowing cache')
    staged_cache = output_world / 'streamsreflowing'
    copy_checked(source_cache, staged_cache, files)
    source_entries = {}
    for source_file in sorted(path for path in source_cache.rglob('*') if path.is_file()):
        relative = source_file.relative_to(source_cache).as_posix()
        staged = staged_cache / relative
        source_hash = launch.sha256(source_file)
        if not staged.is_file() or launch.sha256(staged) != source_hash:
            raise RuntimeError(f'Streams cache staging verification failed: {relative}')
        source_entries[relative] = source_hash
    if not source_entries:
        raise RuntimeError('--copy-streams-cache refuses an empty Streams cache')
    staged_entries = {path.relative_to(staged_cache).as_posix()
                      for path in staged_cache.rglob('*') if path.is_file()}
    if staged_entries != set(source_entries):
        raise RuntimeError('Streams cache staging file set differs from source')
    return {'enabled': True, 'source_files': source_entries,
            'staged_root': 'saves/chunk-profile/streamsreflowing'}


def _verify_staged_streams_cache(target, manifest):
    cache = manifest.get('streams_cache', {'enabled': False})
    if not cache.get('enabled'):
        return
    files, root = cache.get('source_files'), cache.get('staged_root')
    if not isinstance(files, dict) or not files or root != 'saves/chunk-profile/streamsreflowing':
        raise RuntimeError('Copied Streams cache provenance is incomplete')
    staged = target / root
    actual = {path.relative_to(staged).as_posix(): launch.sha256(path)
              for path in staged.rglob('*') if path.is_file()} if staged.is_dir() else {}
    if actual != files:
        raise RuntimeError('Prepared copied Streams cache changed')


def prepare(args):
    global manifest_root
    prepare_started = time.monotonic()
    if plan_path := getattr(args, 'biome_review_plan', None):
        plan = json.loads(Path(plan_path).read_text(encoding='utf-8'))
        check_budget(plan)
        _validate_native_biome_plan(plan)
    source = launch.DEFAULT_INSTANCE
    if args.decoder_fp16 and args.decoder_precision:
        raise RuntimeError('--decoder-fp16 cannot be combined with --decoder-precision')
    if getattr(args, 'copy_streams_cache', False) and args.without_streams:
        raise RuntimeError('--copy-streams-cache is incompatible with --without-streams')
    target = WORK / launch.safe_name(args.name)
    if target.exists():
        raise RuntimeError('Fixture already exists; choose a fresh name')
    if active_java_workload():
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
    copy_started = time.monotonic()
    for jar in source_jars:
        copy_checked(jar, target / 'mods' / jar.name, files)
    copy_seconds = {'mods': time.monotonic() - copy_started}
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
            copy_started = time.monotonic()
            copy_checked(source / folder, target / folder, files)
            copy_seconds[folder] = time.monotonic() - copy_started
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
    streams_cache = {'enabled': False}
    if getattr(args, 'copy_streams_cache', False):
        streams_cache = _copy_streams_cache(world, output_world, files)
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
    # The saved codec omits its semantic default, scale 2.
    saved_scale = settings.get(('data', 'scale'), (None, None, None, 2))[3]
    if type(saved_scale) is not int or not 1 <= saved_scale <= 6:
        raise RuntimeError(f'Invalid saved Terrain Diffusion scale: {saved_scale!r}')
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
        'scale': saved_scale, 'active_mod_jars': active_mod_jars,
        'expected_active_mod_jars': expected_active_mod_jars,
        'files': files, 'source_files': source_files,
        'streams_ablation': streams_ablation,
        'source_terrain_diffusion_artifact': source_td,
        'terrain_diffusion_artifact': selected_td,
        'source_world_metadata_sha256': source_metadata,
        'fresh_level_dat_sha256': launch.sha256(output_world / 'level.dat'),
        'excluded_world_data': ['regions', 'entities', 'poi', 'playerdata', 'structure index']
            + ([] if getattr(args, 'copy_streams_cache', False) else ['Streams graphs']),
        'streams_cache': streams_cache,
        'world_edits': ['remove copied Data.Player', 'initialized=0']
            + (['natural-river data.enabled=0 for Streams removal ablation',
                'exclude Streams exact-terrain marker'] if args.without_streams else []),
        'input_overrides': overrides, 'shaders_disabled_for_diagnostic': args.no_shaders,
        'decoder_precision': precision,
        'missing_selected_resourcepacks_in_source': missing_packs,
        'complete': True,
    })
    save(target / 'prepare-timing.json', {'throughManifestSeconds': time.monotonic() - prepare_started,
                                          'verifiedCopySecondsByGroup': copy_seconds})
    print(json.dumps({'prepared': str(target), 'mods': active_mod_jars, 'seed': seed,
                      'scale': saved_scale}, ensure_ascii=True), flush=True)


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
        capture_output=True, text=True, errors='replace', creationflags=FLAGS)
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


def _tree_hashes(root):
    """Hash a clone-local mutable tree after the first successful lifecycle."""
    if not root.exists():
        return {}
    return {path.relative_to(root).as_posix(): launch.sha256(path)
            for path in sorted(root.rglob('*')) if path.is_file()}


def _audit_passed(receipt):
    audit = receipt.get('fastnoiseBiomeAudit') if isinstance(receipt, dict) else None
    if not isinstance(audit, dict) or audit.get('status') != 'passed':
        return False
    is_restart = receipt.get('verifyRestart') is True
    if not is_restart:
        cardinalities = audit.get('cardinalities')
        required = {1, 2, 3, 4, 7, 8, 9, 10, 16, 17, 32, 33, 64}
        if (not isinstance(cardinalities, list) or not required.issubset(set(cardinalities))
                or len(cardinalities) < 25):
            return False
    # The concrete generated target is recorded per phase, separately from the
    # synthetic 1..64 cardinality cases at the receipt root.
    if is_restart and audit.get('mode') != 'saved-chunk-reload':
        return False
    delivery = receipt.get('fastnoiseClientDelivery')
    if (not isinstance(delivery, dict) or delivery.get('status') != 'passed'
            or not delivery.get('actualClientPacket') or receipt.get('saveFlushed') is not True):
        return False
    chunks = [sample for phase in receipt.get('phases', ()) if isinstance(phase, dict)
              for sample in phase.get('chunks', ()) if isinstance(sample, dict)]
    audits = [sample.get('fastnoiseBiomeAudit') for sample in chunks]
    return (bool(chunks)
            and all(isinstance(audit, dict) and audit.get('status') == 'passed'
                    and audit.get('biomeFingerprint')
                    and isinstance(audit.get('maxBiomeCardinality'), int)
                    for audit in audits)
            and any(audit['maxBiomeCardinality'] > 8 for audit in audits))


def _wetland_spatial_audit_passed(receipt, target):
    """Validate the fresh, clone-local spatial CSV referenced by its receipt."""
    audit = receipt.get('wetlandSpatialAudit') if isinstance(receipt, dict) else None
    if (not isinstance(audit, dict) or audit.get('status') != 'passed'
            or not isinstance(audit.get('samples'), int) or audit['samples'] < 262144
            or audit.get('file') != 'wetland-spatial.csv'
            or not isinstance(audit.get('csvSha256'), str)):
        return False
    csv = target / audit['file']
    return csv.is_file() and launch.sha256(csv) == audit['csvSha256']


def _wetland_sites(value):
    """Accept exactly four block-coordinate sites in the diagnostic's wire format."""
    if value is None:
        return None
    if not re.fullmatch(r'-?\d+,-?\d+(;-?\d+,-?\d+){3}', value):
        raise RuntimeError('--wetland-sites requires exactly four x,z pairs separated by semicolons')
    return value


def _wetland_site_string(audit):
    sites = audit.get('sites', [])
    names = ['reed', 'mangrove', 'freshwater', 'riparian']
    if ([site.get('name') for site in sites] != names
            or any(not isinstance(site.get('center'), list) or len(site['center']) != 2
                   or any(type(number) is not int for number in site['center']) for site in sites)):
        raise RuntimeError('Wetland receipt requires four named integer-coordinate sites')
    return ';'.join(','.join(map(str, site['center'])) for site in sites)


def _wetland_input_hashes(manifest):
    """Compare prepared inputs, allowing only the TD implementation to change."""
    td = 'mods/' + manifest['terrain_diffusion_artifact']['source_filename']
    hashes = {Path(name).as_posix(): entry['sha256'] for name, entry in manifest['files'].items()}
    hashes.update(manifest.get('input_overrides', {}))
    if td not in hashes or not manifest.get('fresh_level_dat_sha256'):
        raise RuntimeError('Wetland baseline lacks prepared artifact/world identity')
    hashes.pop(td)
    hashes['saves/chunk-profile/level.dat'] = manifest['fresh_level_dat_sha256']
    return hashes


def _wetland_inputs(args, manifest, restart):
    """Reject mismatched coordinate provenance before compilation or game launch."""
    sites = _wetland_sites(getattr(args, 'wetland_sites', None))
    seed = getattr(args, 'wetland_seed', None)
    baseline = getattr(args, 'wetland_baseline', None)
    enabled = getattr(args, 'wetland_spatial_audit', False)
    if (sites is not None or seed is not None or baseline is not None) and not enabled:
        raise RuntimeError('Wetland sites/seed/baseline require --wetland-spatial-audit')
    if baseline is not None:
        if restart:
            raise RuntimeError('--wetland-baseline is for fresh comparisons, not restart')
        baseline = Path(baseline).resolve()
        previous = json.loads((baseline / 'input-manifest.json').read_text(encoding='utf-8'))
        process = json.loads((baseline / 'process.json').read_text(encoding='utf-8'))
        result = json.loads((baseline / 'launch-result.json').read_text(encoding='utf-8'))
        receipt = json.loads((baseline / 'receipt.json').read_text(encoding='utf-8'))
        if (result.get('exit_code') != 0 or result.get('timed_out') is not False
                or result.get('passed') is not True or receipt.get('status') != 'success'
                or not process.get('wetland_spatial_audit')
                or not _wetland_spatial_audit_passed(receipt, baseline)):
            raise RuntimeError('Wetland baseline requires successful retained spatial evidence')
        audit = receipt['wetlandSpatialAudit']
        for key in ('seed', 'scale', 'versions'):
            if key not in previous or previous[key] != manifest.get(key):
                raise RuntimeError(f'Wetland baseline {key} differs from prepared inputs')
        if audit.get('seed') != manifest['seed']:
            raise RuntimeError('Wetland baseline receipt seed differs from prepared inputs')
        if _wetland_input_hashes(previous) != _wetland_input_hashes(manifest):
            raise RuntimeError('Wetland baseline mods/settings/models/initial caches differ')
        # Chunk order and diagnostic switches also affect the inference workload.
        for key in ('side', 'chunk_x', 'chunk_z', 'regions', 'request_telemetry',
                    'streams_settle_seconds', 'streams_ground_marine', 'streams_batch_sampling',
                    'streams_ground_marine_audit', 'fastnoise_biome_audit'):
            if key not in process or process[key] != getattr(args, key):
                raise RuntimeError(f'Wetland baseline launch setting differs: {key}')
        for key, value in (('settle_seconds', args.settle), ('phase_timeout_seconds', args.phase_timeout)):
            if process.get(key) != value:
                raise RuntimeError(f'Wetland baseline lacks matching launch setting: {key}')
        prior_sites = _wetland_site_string(audit)
        if sites is not None and sites != prior_sites:
            raise RuntimeError('Wetland comparison sites differ from baseline')
        sites = prior_sites
        if seed is not None and seed != manifest['seed']:
            raise RuntimeError('Wetland coordinate seed differs from prepared world')
        seed = manifest['seed']
    if sites is not None and seed is None:
        raise RuntimeError('--wetland-sites requires --wetland-seed or --wetland-baseline')
    if seed is not None and seed != manifest['seed']:
        raise RuntimeError('Wetland coordinate seed differs from prepared world')
    return sites, None if baseline is None else str(baseline)


def _audit_preflight(target, fastnoise, wetland):
    """Cheap source/fixture checks fail closed before diagnostic build or GPU use."""
    checks = []
    if fastnoise:
        checks.append('tools/streams_probe/test_fastnoise_gate.py')
    if wetland:
        checks.extend(('tools/test_macro_context.py',
                       'tools/terrain_sampling/test_analyze_wetland_spatial.py'))
    if not checks:
        return
    evidence = {'status': 'running', 'scope': 'repository source checks; runtime JAR audited in game',
                'checks': []}
    for index, script in enumerate(checks):
        log_path = target / f'audit-preflight-{index}.log'
        with log_path.open('w', encoding='utf-8') as log:
            completed = subprocess.run([sys.executable, '-B', str(ROOT / script)], cwd=ROOT,
                                       stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
        evidence['checks'].append({'script': script, 'exit_code': completed.returncode,
                                   'log': log_path.name})
        evidence['status'] = 'failed' if completed.returncode else 'running'
        save(target / 'audit-preflight.json', evidence)
        if completed.returncode:
            raise RuntimeError(f'Audit preflight failed before game launch: {script}; see {log_path}')
    evidence['status'] = 'passed'
    save(target / 'audit-preflight.json', evidence)


def _wetland_report(receipt, target, manifest, sites, baseline):
    """Require measured grid geometry, then retain metrics separately from visual acceptance."""
    if not _wetland_spatial_audit_passed(receipt, target):
        raise RuntimeError('Wetland spatial receipt or CSV hash is invalid')
    audit = receipt['wetlandSpatialAudit']
    measured_sites = _wetland_site_string(audit)
    if (audit.get('seed') != manifest['seed'] or audit.get('samples') != 262144
            or audit.get('windows') != 4 or audit.get('sideBlocks') != 1024
            or audit.get('stepBlocks') != 4 or (sites is not None and sites != measured_sites)):
        raise RuntimeError('Wetland runtime seed/sites/grid differs from requested inputs')
    spec = importlib.util.spec_from_file_location('wetland_analysis',
                                                 ROOT / 'tools/terrain_sampling/analyze_wetland_spatial.py')
    analyzer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(analyzer)
    report = analyzer.analyse(target / audit['file'], target / 'wetland-analysis')
    if set(report['windows']) != {site['name'] for site in audit['sites']}:
        raise RuntimeError('Wetland CSV window names differ from runtime receipt')
    for site in audit['sites']:
        window = report['windows'][site['name']]
        x, z = site['center']
        if (window['sampleCount'] != 65536 or window['gridStepBlocks'] != 4
                or window['extentBlocks'] != {'x': [x - 512, x + 512], 'z': [z - 512, z + 512]}):
            raise RuntimeError(f'Wetland CSV geometry differs from receipt: {site["name"]}')
    report['provenance'] = {'seed': manifest['seed'], 'scale': manifest['scale'],
                            'sites': measured_sites, 'csvSha256': audit['csvSha256'],
                            'inputManifestSha256': launch.sha256(target / 'input-manifest.json'),
                            'tdArtifact': manifest['terrain_diffusion_artifact'], 'baseline': baseline}
    save(target / 'wetland-analysis/summary.json', report)
    return {'status': 'measured', 'qualityAcceptance': 'review_required',
            'summary': 'wetland-analysis/summary.json',
            'summarySha256': launch.sha256(target / 'wetland-analysis/summary.json')}


def _freeze_restart_inputs(target):
    snapshot = {
        'version': 1,
        'mutable': {name: _tree_hashes(target / name)
                    for name in ('config', 'terrain-diffusion-models', 'saves/chunk-profile')},
        'mods': _tree_hashes(target / 'mods'),
    }
    save(target / 'restart-input-manifest.json', snapshot)
    return snapshot


def _verify_restart_inputs(target, snapshot):
    for name, expected in snapshot['mutable'].items():
        actual = _tree_hashes(target / name)
        if actual != expected:
            raise RuntimeError(f'Restart input changed after freeze: {name}')
    if _tree_hashes(target / 'mods') != snapshot['mods']:
        raise RuntimeError('Restart mod set changed after freeze')


def _verify_initial_mods(target, manifest, diagnostic_sha256):
    """Bind a reload to exactly the active artifacts selected at preparation."""
    expected = {}
    for relative, entry in manifest['files'].items():
        if Path(relative).parent.as_posix() != 'mods':
            continue
        expected[Path(relative).name] = manifest.get('input_overrides', {}).get(relative, entry['sha256'])
    actual = {path.name: launch.sha256(path) for path in (target / 'mods').glob('*.jar')}
    diagnostic_names = [name for name, digest in actual.items() if digest == diagnostic_sha256]
    if len(diagnostic_names) != 1:
        raise RuntimeError('Prior diagnostic JAR is missing, duplicated, or changed')
    expected[diagnostic_names[0]] = diagnostic_sha256
    if actual != expected:
        raise RuntimeError('Restart mod artifacts differ from the initial manifest')


def _requested_chunks(regions, side, fallback_x, fallback_z):
    starts = [(fallback_x, fallback_z)] if not regions else [tuple(map(int, pair.split(','))) for pair in regions.split(';')]
    return [(x + dx, z + dz) for x, z in starts for dx in range(side) for dz in range(side)]


def _saved_chunk_headers_present(world, chunks):
    """A restart must read prior FULL chunks rather than silently regenerate them."""
    for chunk_x, chunk_z in chunks:
        path = world / 'region' / f'r.{chunk_x >> 5}.{chunk_z >> 5}.mca'
        if not path.is_file() or path.stat().st_size < 4096:
            return False
        with path.open('rb') as region:
            index = ((chunk_z & 31) * 32 + (chunk_x & 31)) * 4
            region.seek(index)
            if region.read(4)[:3] == b'\0\0\0':
                return False
    return True


def _wetland_terrain_review_passed(receipt, target):
    """Require the bounded final-world review without treating it as the spatial census."""
    review = receipt.get('wetlandTerrainReview') if isinstance(receipt, dict) else None
    if not isinstance(review, dict) or review.get('status') != 'passed':
        return False
    if review.get('seed') != -2049264310295890081 or review.get('scale') != 1:
        return False
    sites = review.get('sites')
    expected = {'freshwater': [17383, 3260], 'mangrove': [17647, 3406],
                'swamp_ruin': [17828, 3408]}
    if (not isinstance(sites, list) or len(sites) != len(expected)
            or {site.get('name') for site in sites if isinstance(site, dict)} != set(expected)):
        return False
    root = target.resolve()
    for site in sites:
        if not isinstance(site, dict) or site.get('status') != 'passed':
            return False
        if expected.get(site.get('name')) != site.get('center'):
            return False
        if site.get('reachedServerTicks', 0) < 100 or site.get('fullChunkCount') != 9:
            return False
        if site.get('siteChunkCount') != 9 or not site.get('clientTargetChunkArrived'):
            return False
        snapshots = site.get('surfaceWaterMudTreeSupportSnapshots')
        eligibility = site.get('tickEligibility')
        if (site.get('phase') != 'after_100_actual_server_ticks_before_ticket_release'
                or not isinstance(snapshots, list) or len(snapshots) != 9
                or not isinstance(eligibility, dict)
                or eligibility.get('ticketLevel') != 31
                or eligibility.get('allBlockTickEligible') is not True):
            return False
        path = site.get('screenshotPath')
        if not isinstance(path, str):
            return False
        try:
            screenshot = Path(path).resolve()
            screenshot.relative_to(root)
        except (OSError, ValueError):
            return False
        if not screenshot.is_file() or screenshot.stat().st_size < 1024:
            return False
    return True


def _biome_review_plan(args, manifest, target, restart):
    """Stage a review plan only after binding it to current sources and the clone JAR."""
    supplied = getattr(args, 'biome_review_plan', None)
    if not supplied:
        return None
    source = Path(supplied).resolve()
    if not source.is_file():
        raise RuntimeError(f'Biome review plan is not a file: {source}')
    try:
        plan = json.loads(source.read_text(encoding='utf-8'))
    except (OSError, ValueError) as failure:
        raise RuntimeError(f'Biome review plan is invalid JSON: {source}') from failure
    if not isinstance(plan, dict) or plan.get('schema') != 1:
        raise RuntimeError('Biome review plan must have schema 1')
    _validate_native_biome_plan(plan)
    check_budget(plan)  # Avoid starting Minecraft for a review that cannot run.
    fingerprint = source_fingerprint(ROOT)
    artifact = manifest['terrain_diffusion_artifact']['sha256']
    if plan.get('sourceFingerprint') != fingerprint:
        raise RuntimeError('Biome review plan sourceFingerprint differs from current production sources')
    if plan.get('artifactSha256') != artifact:
        raise RuntimeError('Biome review plan artifactSha256 differs from the selected fixture JAR')
    if plan.get('seed') != manifest.get('seed') or plan.get('scale') != manifest.get('scale'):
        raise RuntimeError('Biome review plan seed/scale differs from the prepared fixture')
    if not isinstance(plan.get('requiredBiomes'), list) or not plan['requiredBiomes']:
        raise RuntimeError('Biome review plan requires nonempty requiredBiomes')
    omitted = changed_biome_resource_ids(ROOT) - set(plan['requiredBiomes'])
    if omitted:
        raise RuntimeError(f'Biome review plan omits changed biome resources: {sorted(omitted)}')
    if not isinstance(plan.get('windows'), list) or not plan['windows']:
        raise RuntimeError('Biome review plan requires windows')
    for field in ('regressionSites', 'explicitSites'):
        for site in plan.get(field, []):
            if not isinstance(site, dict) or not isinstance(site.get('x'), int) or not isinstance(site.get('z'), int):
                raise RuntimeError(f'Biome review {field} must contain integer x/z')
            if not any(window['minX'] <= site['x'] <= window['maxX'] and window['minZ'] <= site['z'] <= window['maxZ']
                       for window in plan['windows'] if isinstance(window, dict)):
                raise RuntimeError('Biome review sites must stay inside plan windows')
    staged = target / ('biome-visual-review-restart-plan.json' if restart else 'biome-visual-review-plan.json')
    if staged.is_symlink() or (staged.exists() and (not staged.is_file() or launch.sha256(source) != launch.sha256(staged))):
        raise RuntimeError(f'Biome review plan destination already exists: {staged}')
    if not staged.exists():
        shutil.copy2(source, staged)
    if launch.sha256(source) != launch.sha256(staged):
        raise RuntimeError('Biome review plan changed during staging')
    return {'path': staged, 'sha256': launch.sha256(staged), 'source_fingerprint': fingerprint,
            'artifact_sha256': artifact, 'capture_tool_fingerprint': capture_tool_fingerprint(ROOT), 'data': plan}


def _validate_native_biome_plan(plan):
    """Reject plan shapes that BiomeVisualReview would reject after client launch."""
    def integer(value):
        return isinstance(value, int) and not isinstance(value, bool)

    windows = plan.get('windows')
    if not isinstance(windows, list) or not windows:
        raise RuntimeError('Biome review plan requires nonempty windows')
    window_ids = set()
    for window in windows:
        if not isinstance(window, dict):
            raise RuntimeError('Biome review window must be an object')
        identifier = window.get('id')
        values = tuple(window.get(key) for key in ('minX', 'maxX', 'minZ', 'maxZ'))
        if not isinstance(identifier, str) or not identifier.strip() or not all(map(integer, values)):
            raise RuntimeError('Biome review window has malformed id or bounds')
        min_x, max_x, min_z, max_z = values
        if (identifier in window_ids or min_x > max_x or min_z > max_z
                or min_x % 16 or min_z % 16 or (max_x + 1) % 16 or (max_z + 1) % 16):
            raise RuntimeError('Biome review window bounds must be unique inclusive whole chunks')
        window_ids.add(identifier)

    def inside(x, z):
        return any(w['minX'] <= x <= w['maxX'] and w['minZ'] <= z <= w['maxZ'] for w in windows)

    site_ids = set()
    for field in ('regressionSites', 'explicitSites'):
        rows = plan.get(field, [])
        if not isinstance(rows, list):
            raise RuntimeError(f'Biome review {field} must be an array')
        for site in rows:
            if (not isinstance(site, dict) or not isinstance(site.get('id'), str)
                    or not site['id'].strip() or not integer(site.get('x')) or not integer(site.get('z'))):
                raise RuntimeError(f'Biome review {field} contains a malformed site')
            if site['id'] in site_ids or not inside(site['x'], site['z']):
                raise RuntimeError(f'Biome review {field} site must be unique and inside a window')
            site_ids.add(site['id'])

    try:
        fixed_camera_poses(plan)
    except ValueError as error:
        raise RuntimeError(str(error)) from error
    requests = plan.get('inspectionRequests', [])
    if not isinstance(requests, list):
        raise RuntimeError('Biome review inspectionRequests must be an array')
    request_ids = set()
    for request in requests:
        if (not isinstance(request, dict) or not isinstance(request.get('id'), str)
                or not request['id'].strip() or request.get('kind') not in {'root', 'bank', 'underwater', 'boundary'}
                or not integer(request.get('x')) or not integer(request.get('z'))):
            raise RuntimeError('Biome review inspection request is malformed')
        detail_view = request.get('detailView', 'bed')
        if (detail_view not in {'bed', 'waterline'}
                or (detail_view == 'waterline' and request['kind'] != 'underwater')):
            raise RuntimeError('Biome review waterline detail requires underwater inspection')
        if request['id'] in request_ids or not inside(request['x'], request['z']):
            raise RuntimeError('Biome review inspection request must be unique and inside a window')
        request_ids.add(request['id'])

    # A scoped report is a bounded bug review.  Its frozen criteria name the
    # only rendered sites; broad biome sampling remains the unscoped workflow.
    try:
        scoped = review_scope(plan)
    except ValueError as failure:
        raise RuntimeError(str(failure)) from failure
    if scoped is not None:
        scoped_ids = {site_id for criterion in scoped['criteria'] for site_id in criterion['siteIds']}
        selected_ids = site_ids | request_ids
        if len(selected_ids) != len(site_ids) + len(request_ids):
            raise RuntimeError('Scoped biome review site and inspection IDs must be globally unique')
        unknown = scoped_ids - selected_ids
        if unknown:
            raise RuntimeError(f'Scoped biome review criteria name undefined sites: {sorted(unknown)}')
        missing = {row['id'] for row in plan.get('regressionSites', [])} - scoped_ids
        if missing:
            raise RuntimeError(f'Scoped criteria omit reported regression sites: {sorted(missing)}')
        planned = {row['id']: (row['x'], row['z'])
                   for field in ('regressionSites', 'explicitSites') for row in plan.get(field, [])}
        fixed = [point for site_id, point in planned.items() if site_id in scoped_ids]
        if len(fixed) != len(set(fixed)):
            raise RuntimeError('Give each frozen fixed coordinate one ID; use that ID in every applicable criterion')
        planned.update({row['id']: (row['x'], row['z']) for row in requests})
        facts_ref = scoped['baseline']['facts']
        facts_path = Path(facts_ref['path'])
        if not facts_path.is_absolute():
            facts_path = ROOT / facts_path
        if not facts_path.is_file():
            raise RuntimeError('Scoped biome review baseline facts are unavailable')
        if launch.sha256(facts_path) != facts_ref['sha256']:
            raise RuntimeError('Scoped biome review baseline facts changed')
        try:
            facts = json.loads(facts_path.read_text(encoding='utf-8-sig'))
            fact_sites = {row['id']: (row['x'], row['z']) for row in facts['sites']}
        except (KeyError, TypeError, ValueError, OSError) as failure:
            raise RuntimeError('Scoped biome review baseline facts are malformed') from failure
        mismatched = sorted(site_id for site_id in scoped_ids
                            if fact_sites.get(site_id) != planned[site_id])
        if mismatched:
            raise RuntimeError('Scoped baseline facts differ from frozen requested sites: ' + ', '.join(mismatched))

    maximum = plan.get('maxSites', 16)
    if not integer(maximum) or not 1 <= maximum <= 64:
        raise RuntimeError('Biome review maxSites must be 1..64')
    if scoped is not None and maximum < len(scoped_ids):
        raise RuntimeError('Scoped biome review maxSites is below its frozen evidence sites')
    for field in ('terrainTraceSites', 'generationTraceSites'):
        rows = plan.get(field, [])
        if not isinstance(rows, list) or len(rows) > 8 or any(not isinstance(row, dict)
                or not integer(row.get('x')) or not integer(row.get('z')) for row in rows):
            raise RuntimeError(f'Biome review {field} requires at most 8 integer x/z positions')


def _biome_plan_chunks(plan):
    chunks = []
    for window in plan['windows']:
        if not isinstance(window, dict) or not all(isinstance(window.get(key), int) for key in ('minX', 'maxX', 'minZ', 'maxZ')):
            raise RuntimeError('Biome review plan has malformed window')
        if window['minX'] > window['maxX'] or window['minZ'] > window['maxZ']:
            raise RuntimeError('Biome review plan window bounds are inverted')
        chunks.extend((x, z) for x in range(window['minX'] >> 4, (window['maxX'] >> 4) + 1)
                      for z in range(window['minZ'] >> 4, (window['maxZ'] >> 4) + 1))
    return chunks


def _biome_visual_review_captured(receipt, target, plan):
    review = receipt.get('biomeVisualReview') if isinstance(receipt, dict) else None
    if not isinstance(review, dict) or review.get('status') != 'captured':
        return False
    if review.get('sourceFingerprint') != plan['source_fingerprint'] or review.get('artifactSha256') != plan['artifact_sha256']:
        return False
    if review.get('captureToolFingerprint') != plan.get('capture_tool_fingerprint'):
        return False
    if review.get('captureToolFingerprint') != capture_tool_fingerprint(ROOT):
        return False
    if review.get('diagnosticJarSha256') != plan.get('diagnostic_jar_sha256'):
        return False
    if review.get('coverageGaps') or not isinstance(review.get('sites'), list) or not review['sites']:
        return False
    root = target.resolve()
    for site in review['sites']:
        if site.get('status') != 'captured' or site.get('reachedServerTicks', 0) < 100 or site.get('tickEligible') is not True:
            return False
        images = site.get('images')
        if not matches_site_views(site, fixed_camera_poses(plan.get('data', {}))):
            return False
        for image in images:
            try:
                path = Path(image['path']).resolve()
                path.relative_to(root)
            except (KeyError, OSError, ValueError):
                return False
            if not path.is_file() or path.stat().st_size < 1024:
                return False
    return True


def run(args, restart=False):
    target = WORK / launch.safe_name(args.name)
    manifest = json.loads((target / 'input-manifest.json').read_text(encoding='utf-8'))
    if not manifest['complete'] or (not restart and (target / 'process.json').exists()):
        raise RuntimeError('Run requires a prepared, unrun fixture')
    wetland_sites, wetland_baseline = _wetland_inputs(args, manifest, restart)
    biome_review = _biome_review_plan(args, manifest, target, restart)
    if args.streams_settle_seconds and not args.request_telemetry:
        raise RuntimeError('--streams-settle-seconds requires --request-telemetry for the native trace')
    if active_java_workload():
        raise RuntimeError('Java is running; inspect before starting the GPU workload')
    if not restart:
        for relative, entry in manifest['files'].items():
            normalized = Path(relative).as_posix()
            expected = manifest['fresh_level_dat_sha256'] if normalized == 'saves/chunk-profile/level.dat' else manifest.get('input_overrides', {}).get(normalized, entry['sha256'])
            if launch.sha256(target / relative) != expected:
                raise RuntimeError(f'Prepared input changed: {relative}')
    fastnoise_biome_audit = getattr(args, 'fastnoise_biome_audit', False)
    wetland_spatial_audit = getattr(args, 'wetland_spatial_audit', False)
    wetland_terrain_review = getattr(args, 'wetland_terrain_review', False)
    if wetland_terrain_review and restart:
        raise RuntimeError('--wetland-terrain-review is a fresh-world-only final review')
    if wetland_terrain_review and (manifest.get('seed') != -2049264310295890081
                                   or manifest.get('scale') != 1):
        raise RuntimeError('--wetland-terrain-review requires seed -2049264310295890081 and scale 1')
    expected_mods = manifest.get('expected_active_mod_jars', manifest.get('active_mod_jars')) + (1 if restart else 0)
    if len(list((target / 'mods').glob('*.jar'))) != expected_mods:
        raise RuntimeError('Prepared staged mod set changed')
    streams_ablation = manifest.get('streams_ablation', {'enabled': False, 'removed_artifact': None})
    if streams_ablation.get('enabled'):
        removed = streams_ablation.get('removed_artifact')
        if not isinstance(removed, dict) or removed.get('mod_id') != 'streamsreflowing':
            raise RuntimeError('Streams ablation provenance is incomplete')
        if (target / 'mods' / removed.get('source_filename', '')).exists():
            raise RuntimeError('StreamsReflowing artifact unexpectedly present in ablation clone')
    # Streams writes newly discovered routing plates while generation runs.  The
    # copied manifest proves the starting cache only; a review restart instead
    # verifies the post-generation cache through restart-input-manifest.json.
    if not restart:
        _verify_staged_streams_cache(target, manifest)
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
    process_name = 'restart-process.json' if restart else 'process.json'
    receipt_name = 'restart-receipt.json' if restart else 'receipt.json'
    progress_name = 'restart-progress.json' if restart else 'progress.json'
    result_name = 'restart-launch-result.json' if restart else 'launch-result.json'
    launcher_name = 'restart-launcher.log' if restart else 'launcher.log'
    gpu_name = 'restart-gpu.csv' if restart else 'gpu.csv'
    resources_name = 'restart-resources.csv' if restart else 'resources.csv'
    if restart:
        if (target / process_name).exists():
            raise RuntimeError('Restart requires no prior restart attempt for this fixture')
        prior = json.loads((target / 'process.json').read_text(encoding='utf-8'))
        prior_result = json.loads((target / 'launch-result.json').read_text(encoding='utf-8'))
        prior_receipt = json.loads((target / 'receipt.json').read_text(encoding='utf-8'))
        if prior_result.get('exit_code') or prior_result.get('timed_out') or prior_receipt.get('status') != 'success':
            raise RuntimeError('Restart requires a prior successful profile run')
        if not (fastnoise_biome_audit or biome_review is not None):
            raise RuntimeError('Restart requires --fastnoise-biome-audit or --biome-review-plan')
        if fastnoise_biome_audit and (not prior.get('fastnoise_biome_audit') or not _audit_passed(prior_receipt)):
            raise RuntimeError('Restart requires a prior successful Fast Noise biome audit')
        if biome_review is not None and (not prior.get('biome_review_plan_sha256')
                                        or prior_receipt.get('biomeVisualReview', {}).get('status') != 'captured'):
            raise RuntimeError('Biome visual review restart requires a prior successful capture')
        if biome_review is not None and (not prior_result.get('passed')
                or prior.get('biome_capture_tool_fingerprint') != biome_review['capture_tool_fingerprint']
                or prior_receipt['biomeVisualReview'].get('captureToolFingerprint') != biome_review['capture_tool_fingerprint']):
            raise RuntimeError('Biome capture tools or provenance changed; use a fresh fixture')
        if wetland_spatial_audit != bool(prior.get('wetland_spatial_audit')):
            raise RuntimeError('Restart wetland spatial audit flag must match the prior run')
        if wetland_spatial_audit and not _wetland_spatial_audit_passed(prior_receipt, target):
            raise RuntimeError('Restart requires a prior successful wetland spatial audit')
        if wetland_sites is not None and wetland_sites != prior.get('wetland_sites'):
            raise RuntimeError('Restart wetland sites must match the prior run')
        if (args.side, args.chunk_x, args.chunk_z, args.regions) != (prior['side'], prior['chunk_x'], prior['chunk_z'], prior['regions']):
            raise RuntimeError('Restart target must match the prior audited run')
        _verify_initial_mods(target, manifest, prior['diagnostic_sha256'])
        diagnostic = next(path for path in (target / 'mods').glob('*.jar')
                          if launch.sha256(path) == prior['diagnostic_sha256'])
        requested = _requested_chunks(prior['regions'], prior['side'], prior['chunk_x'], prior['chunk_z'])
        if not _saved_chunk_headers_present(target / 'saves/chunk-profile', requested):
            raise RuntimeError('Restart requires prior generated chunks in the copied saved-world region files')
        if biome_review is not None and not _saved_chunk_headers_present(target / 'saves/chunk-profile',
                                                                          _biome_plan_chunks(biome_review['data'])):
            raise RuntimeError('Biome visual review restart requires its plan windows from the first saved lifecycle')
        # Minecraft rotates latest.log on launch. Retain the first lifecycle's
        # game logs before starting the independent saved-chunk reload process.
        for name in ('latest.log', 'debug.log'):
            source = target / 'logs' / name
            preserved = target / 'logs' / f'pre-restart-{name}'
            if source.is_file() and not preserved.exists():
                shutil.copy2(source, preserved)
        snapshot_path = target / 'restart-input-manifest.json'
        if not snapshot_path.is_file():
            raise RuntimeError('Restart input snapshot is missing from the successful first run')
        snapshot = json.loads(snapshot_path.read_text(encoding='utf-8'))
        _verify_restart_inputs(target, snapshot)
        jar = diagnostic
    else:
        _audit_preflight(target, fastnoise_biome_audit, wetland_spatial_audit or wetland_terrain_review or biome_review is not None)
        jar = build()
        shutil.copy2(jar, target / 'mods' / jar.name)
    cfg = dict(line.split('=', 1) for line in (target / 'source-instance.cfg').read_text(encoding='utf-8').splitlines() if '=' in line)
    if cfg.get('JvmArgs') != '-XX:+UseZGC -XX:+ZGenerational':
        raise RuntimeError('Unexpected source JVM settings')
    runtime = argparse.Namespace(width=854, height=480, timeout=args.timeout,
        libraries=launch.DEFAULT_LIBRARIES, assets=launch.DEFAULT_ASSETS, java=launch.DEFAULT_JAVA,
        collector='zgc', heap_min_mib=int(cfg['MinMemAlloc']), heap_max_mib=int(cfg['MaxMemAlloc']), property=[
            'td.inference.metrics=true', 'chunkprofile.world=chunk-profile',
            f'chunkprofile.output={target / receipt_name}', f'chunkprofile.side={args.side}',
            f'chunkprofile.progress={target / progress_name}',
            f'chunkprofile.chunkX={args.chunk_x}', f'chunkprofile.chunkZ={args.chunk_z}',
            'chunkprofile.prefetch=8', f'chunkprofile.settleSeconds={args.settle}',
            f'chunkprofile.streamsSettleSeconds={args.streams_settle_seconds}',
            f'chunkprofile.timeoutSeconds={args.phase_timeout}'])
    if fastnoise_biome_audit:
        runtime.property.append('chunkprofile.fastnoiseBiomeAudit=true')
    if wetland_spatial_audit and not restart:
        runtime.property.append('chunkprofile.wetlandSpatialAudit=true')
    if wetland_terrain_review and not restart:
        runtime.property.extend(('chunkprofile.wetlandTerrainReview=true',
                                 'chunkprofile.wetlandTerrainReviewScale=1'))
    if biome_review is not None:
        if biome_review['capture_tool_fingerprint'] != capture_tool_fingerprint(ROOT):
            raise RuntimeError('Biome review tool changed after plan staging')
        biome_review['diagnostic_jar_sha256'] = launch.sha256(jar)
        runtime.property.extend(('chunkprofile.biomeVisualReview=true',
                                 f'chunkprofile.biomeReviewPlan={biome_review["path"]}',
                                 f'chunkprofile.biomeCaptureToolFingerprint={biome_review["capture_tool_fingerprint"]}',
                                 f'chunkprofile.biomeReviewDiagnosticJarSha256={biome_review["diagnostic_jar_sha256"]}'))
        runtime.width, runtime.height = 1600, 900
        trace_sites = biome_review['data'].get('terrainTraceSites', [])
        if trace_sites:
            if len(trace_sites) > 8 or any(not isinstance(s, dict) or type(s.get('x')) is not int or type(s.get('z')) is not int for s in trace_sites):
                raise RuntimeError('terrainTraceSites requires at most 8 integer x/z positions')
            runtime.property.append('td.wetland.tracePositions=' + ';'.join(f"{s['x']},{s['z']}" for s in trace_sites))
        generation_trace_sites = biome_review['data'].get('generationTraceSites', [])
        if generation_trace_sites:
            if (len(generation_trace_sites) > 8
                    or any(not isinstance(s, dict) or type(s.get('x')) is not int
                           or type(s.get('z')) is not int for s in generation_trace_sites)):
                raise RuntimeError('generationTraceSites requires at most 8 integer x/z positions')
            # The diagnostic trace records stage-local block writes during normal
            # generation.  It does not query the provider before generation, so
            # it preserves the candidate's cache warm-up order.
            runtime.property.append('td.generation.tracePositions='
                                    + ';'.join(f"{s['x']},{s['z']}" for s in generation_trace_sites))
            runtime.property.append('td.wetland.shapeTracePositions='
                                    + ';'.join(f"{s['x']},{s['z']}" for s in generation_trace_sites))
    standalone_trace_sites = _generation_trace_sites(getattr(args, 'generation_trace_sites', None))
    if standalone_trace_sites and biome_review is not None and biome_review['data'].get('generationTraceSites'):
        raise RuntimeError('--generation-trace-sites conflicts with biome review generationTraceSites')
    if standalone_trace_sites:
        positions = ';'.join(f'{x},{z}' for x, z in standalone_trace_sites)
        runtime.property.extend(('td.generation.tracePositions=' + positions,
                                 'td.wetland.shapeTracePositions=' + positions))
    if wetland_sites is not None and not restart:
        runtime.property.append(f'chunkprofile.wetlandSites={wetland_sites}')
    if restart:
        runtime.property.extend(('chunkprofile.verifyRestart=true',
                                 f'chunkprofile.priorReceipt={target / "receipt.json"}'))
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
    # The extractor deliberately requires a new directory. Keep the first
    # lifecycle's native files intact when launching the independent reload.
    natives = target / ('restart-natives' if restart else 'natives')
    launch.extract_natives(native_jars, natives)
    command = launch.make_command(runtime, target, natives)
    (target / 'logs').mkdir(exist_ok=True)
    with (target / 'logs' / launcher_name).open('w', encoding='utf-8') as log:
        process = subprocess.Popen(command, cwd=target, stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
    save(target / process_name, {'pid': process.pid, 'start_epoch': time.time(), 'diagnostic_sha256': launch.sha256(jar),
        'side': args.side, 'chunk_x': args.chunk_x, 'chunk_z': args.chunk_z, 'prefetch': 8,
        'regions': args.regions, 'shaders_disabled_for_diagnostic': manifest.get('shaders_disabled_for_diagnostic', False),
        'request_telemetry': args.request_telemetry,
        'streams_settle_seconds': args.streams_settle_seconds,
        'settle_seconds': args.settle,
        'phase_timeout_seconds': args.phase_timeout,
        'streams_ground_marine': args.streams_ground_marine,
        'streams_batch_sampling': args.streams_batch_sampling,
        'streams_ground_marine_audit': args.streams_ground_marine_audit,
        'fastnoise_biome_audit': fastnoise_biome_audit,
        'wetland_spatial_audit': wetland_spatial_audit,
        'wetland_terrain_review': wetland_terrain_review,
        'wetland_sites': wetland_sites,
        'wetland_baseline': wetland_baseline,
        'biome_review_plan': None if biome_review is None else str(biome_review['path']),
        'biome_review_plan_sha256': None if biome_review is None else biome_review['sha256'],
        'biome_capture_tool_fingerprint': None if biome_review is None else biome_review['capture_tool_fingerprint'],
        'biome_review_diagnostic_jar_sha256': None if biome_review is None else biome_review['diagnostic_jar_sha256'],
        'restart': restart,
        'streams_ablation': streams_ablation,
        'copied_streams_cache': manifest.get('streams_cache', {'enabled': False}),
        'generation_trace_sites': standalone_trace_sites,
        'heap_min_mib':runtime.heap_min_mib, 'heap_max_mib':runtime.heap_max_mib})
    print(f'Launched isolated chunk profile PID {process.pid}', flush=True)
    gpu = None
    gpu_stream = (target / gpu_name).open('w', encoding='utf-8')
    try:
        gpu = subprocess.Popen(['nvidia-smi', '--query-gpu=timestamp,utilization.gpu,utilization.memory,memory.used,power.draw',
            '--format=csv,noheader,nounits', '--loop=1'], stdout=gpu_stream, stderr=subprocess.DEVNULL, creationflags=FLAGS)
    except OSError:
        pass
    started = time.monotonic()
    watched = psutil.Process(process.pid)
    resources = (target / resources_name).open('w', encoding='utf-8')
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
                progress = target / progress_name
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
    receipt = target / receipt_name
    value = json.loads(receipt.read_text(encoding='utf-8')) if receipt.exists() else None
    unchanged = all(launch.sha256(Path(manifest['source_world']) / name) == digest for name,digest in manifest['source_world_metadata_sha256'].items())
    result = {'exit_code':process.returncode, 'timed_out':timed_out, 'receipt':value,
              'source_world_metadata_unchanged':unchanged, 'thread_dump_requests':thread_dump_requests}
    print(json.dumps({'exit_code':process.returncode,'timed_out':timed_out,'receipt_status':None if value is None else value.get('status'),
                      'source_world_metadata_unchanged':unchanged}), flush=True)
    passed = not (process.returncode or timed_out or value is None or value.get('status') != 'success'
                   or not unchanged or (fastnoise_biome_audit and not _audit_passed(value))
                   or (wetland_spatial_audit and not restart
                       and not _wetland_spatial_audit_passed(value, target))
                   or (wetland_terrain_review and not restart
                       and not _wetland_terrain_review_passed(value, target))
                   or (biome_review is not None and not _biome_visual_review_captured(value, target, biome_review)))
    if biome_review is not None:
        if (source_fingerprint(ROOT) != biome_review['source_fingerprint']
                or manifest['terrain_diffusion_artifact']['sha256'] != biome_review['artifact_sha256']
                or launch.sha256(target / 'mods' / manifest['terrain_diffusion_artifact']['source_filename']) != biome_review['artifact_sha256']
                or launch.sha256(biome_review['path']) != biome_review['sha256']):
            passed = False
            result['biome_visual_review_provenance'] = {'status': 'failed', 'error': 'sources, selected artifact, or staged plan changed'}
    if wetland_spatial_audit and not restart and not process.returncode and not timed_out and value is not None:
        try:
            result['wetland_analysis'] = _wetland_report(value, target, manifest, wetland_sites, wetland_baseline)
        except (RuntimeError, ValueError, OSError) as failure:
            result['wetland_analysis'] = {'status': 'failed', 'error': str(failure)}
            passed = False
    result['passed'] = passed
    save(target / result_name, result)
    if not restart and passed and (fastnoise_biome_audit or biome_review is not None):
        # Freeze only after the first lifecycle has stopped and its receipt has
        # passed. A user change between the two lifecycles is therefore rejected.
        _freeze_restart_inputs(target)
    if not passed:
        raise RuntimeError('Profile run did not pass; inspect retained logs and receipt')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['prepare','run','restart'])
    p.add_argument('name')
    p.add_argument('--world')
    p.add_argument('--no-shaders', action='store_true', help='disable only Iris shaders in the prepared private clone')
    p.add_argument('--td-jar', help='built Terrain Diffusion NeoForge JAR to use only in this new clone')
    p.add_argument('--decoder-fp16', help='reviewed FP16 decoder .onnx used only in this new clone')
    p.add_argument('--decoder-precision', choices=('fp32',),
                   help='force this decoder precision only in a new clone')
    p.add_argument('--request-telemetry', action='store_true',
                   help='opt in to TD request-shape telemetry for this cloned diagnostic run')
    p.add_argument('--fastnoise-biome-audit', action='store_true',
                   help='require the Fast Noise 1.0.13 biome-palette boundary and generated-chunk audit')
    p.add_argument('--wetland-spatial-audit', action='store_true',
                   help='require a fresh 4x256x256 wetland spatial CSV audit; restart verifies the first receipt')
    p.add_argument('--wetland-terrain-review', action='store_true',
                   help='fresh-only bounded final review of three fixed wetland/ruin sites with native screenshots; does not run the spatial CSV census')
    p.add_argument('--biome-review-plan', type=Path,
                   help='schema-1 generic native biome visual-review plan bound to current sources and selected fixture JAR; supports saved-chunk restart captures')
    p.add_argument('--wetland-sites',
                   help='exactly four block-coordinate sites: x,z;x,z;x,z;x,z')
    p.add_argument('--wetland-seed', type=int,
                   help='seed that supplied manually selected wetland sites; must match prepared world')
    p.add_argument('--wetland-baseline', type=Path,
                   help='successful fixture directory to inherit wetland sites and check comparison inputs')
    p.add_argument('--without-streams', action='store_true',
                   help='prepare a clone-only StreamsReflowing removal ablation; source instance is unchanged')
    p.add_argument('--copy-streams-cache', action='store_true',
                   help='copy and hash-verify source world Streams cache into this fresh clone; incompatible with --without-streams')
    p.add_argument('--generation-trace-sites',
                   help='fixture-only normal-generation trace: 1..8 x,z pairs separated by semicolons')
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
    if args.action != 'run' and args.generation_trace_sites is not None:
        p.error('--generation-trace-sites is only valid with run')
    if args.action != 'prepare' and args.copy_streams_cache:
        p.error('--copy-streams-cache is only valid with prepare')
    if not 0 <= args.streams_settle_seconds <= 900:
        p.error('--streams-settle-seconds must be from 0 through 900')
    if not 30 <= args.phase_timeout <= 1800:
        p.error('--phase-timeout must be from 30 through 1800')
    WORK.mkdir(parents=True,exist_ok=True)
    with exclusive_lock(ROOT):
        if args.action == 'prepare':
            prepare(args)
        else:
            run(args, restart=args.action == 'restart')


if __name__=='__main__':
    main()
