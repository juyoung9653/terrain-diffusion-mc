"""Snapshot the installed client and run controlled FPS trials in private copies.

No live-instance writes, hardlinks, heap changes, or account tokens. Existing
trial directories are never reused. JFR repository/output must be ASCII paths.
"""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/optimization_bench'))
import launch

WORK = ROOT / 'build/fps-probe'
TEMPLATE = WORK / 'template'
FLAGS = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


def save(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=True), encoding='utf-8')


def digest(path):
    return launch.sha256(path)


def snapshot(source, world):
    if TEMPLATE.exists():
        raise RuntimeError('Immutable template already exists')
    import psutil
    if any((p.info['name'] or '').lower() in ('java.exe', 'javaw.exe')
           for p in psutil.process_iter(['name'])):
        raise RuntimeError('Snapshot requires all Java processes closed; inspect before continuing')
    world_path = source / 'saves' / world
    if not (world_path / 'level.dat').is_file():
        raise RuntimeError('Requested saved world absent')
    TEMPLATE.mkdir(parents=True)
    for file in ('options.txt',):
        shutil.copy2(source / file, TEMPLATE / file)
    selected = json.loads(dict(line.split(':', 1) for line in
        (source / 'options.txt').read_text(encoding='utf-8').splitlines() if ':' in line)['resourcePacks'])
    packs = {entry[5:] for entry in selected if entry.startswith('file/')}
    for folder in ('mods', 'config', 'defaultconfigs', 'terrain-diffusion-models'):
        if (source / folder).exists():
            shutil.copytree(source / folder, TEMPLATE / folder)
    missing_packs = []
    for folder, names in [('resourcepacks', packs), ('shaderpacks', {
            'photon-actually-3d-crops-cavity.zip', 'photon-actually-3d-crops-cavity.zip.txt'})]:
        (TEMPLATE / folder).mkdir()
        for name in names:
            src, dst = source / folder / name, TEMPLATE / folder / name
            if not src.exists():
                if folder == 'resourcepacks':
                    missing_packs.append(name)
                    continue
                raise RuntimeError(f'Selected asset absent: {name}')
            if src.is_dir():
                shutil.copytree(src, dst)
            else:
                shutil.copy2(src, dst)
    shutil.copytree(world_path, TEMPLATE / 'saves/fps-scene',
                    ignore=shutil.ignore_patterns('session.lock'))
    shutil.copy2(source.parent / 'instance.cfg', TEMPLATE / 'source-instance.cfg')
    entries = {str(p.relative_to(TEMPLATE)): digest(p)
               for p in TEMPLATE.rglob('*') if p.is_file()}
    save(TEMPLATE / 'manifest.json', {'source': str(source), 'world': world,
                                     'files_sha256': entries, 'complete': True,
                                     'already_missing_selected_packs': missing_packs})
    print('Snapshot complete', flush=True)


def build():
    source_hashes = {str(p.relative_to(ROOT / 'tools/fps_probe')): digest(p)
                     for folder in ('java', 'resources')
                     for p in (ROOT / 'tools/fps_probe' / folder).rglob('*') if p.is_file()}
    cache = WORK / 'compiled-probe.json'
    if cache.exists():
        prior = json.loads(cache.read_text())
        if prior['source_hashes'] == source_hashes and Path(prior['jar']).is_file():
            if digest(Path(prior['jar'])) == prior['sha256']:
                return Path(prior['jar'])
    classes = WORK / f'classes-{time.time_ns()}'
    classes.mkdir(parents=True)
    sources = list((ROOT / 'tools/fps_probe/java').rglob('*.java'))
    if not sources:
        raise RuntimeError('Probe sources absent')
    result = subprocess.run([str(launch.DEFAULT_JAVA.with_name('javac.exe')),
        '-encoding', 'UTF-8', '--release', '21', '-proc:none', '-cp',
        launch.compile_classpath(launch.DEFAULT_LIBRARIES), '-d', str(classes),
        *map(str, sources)], capture_output=True, text=True, creationflags=FLAGS)
    (WORK / 'compile.log').write_text(result.stdout + result.stderr, encoding='utf-8')
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    jar = WORK / f'fps-probe-{time.time_ns()}.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for base in (classes, ROOT / 'tools/fps_probe/resources'):
            for file in base.rglob('*'):
                if file.is_file():
                    archive.write(file, file.relative_to(base).as_posix())
    save(cache, {'source_hashes': source_hashes, 'jar': str(jar), 'sha256': digest(jar)})
    return jar


def focus(pid):
    user = ctypes.windll.user32
    callback_type = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def callback(hwnd, _):
        owner = ctypes.c_ulong()
        user.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
        if owner.value == pid and user.IsWindowVisible(hwnd):
            user.ShowWindow(hwnd, 5)
            user.SetForegroundWindow(hwnd)
        return True
    user.EnumWindows(callback_type(callback), 0)


def record_driver(target, phase):
    result = subprocess.run([sys.executable, str(ROOT / 'tools/fps_probe/nvapi_readonly.py'),
        '--application', str(launch.DEFAULT_JAVA), '--output', str(target / f'driver-settings-{phase}.json')],
        capture_output=True, creationflags=FLAGS, timeout=30)
    if result.returncode:
        raise RuntimeError(f'NVIDIA read-only settings capture failed: {result.stderr.decode(errors="replace")}')


def trial(args):
    import psutil
    if any((p.info['name'] or '').lower() in ('java.exe', 'javaw.exe')
           for p in psutil.process_iter(['name'])):
        raise RuntimeError('A Java process is active; inspect it before starting another trial')
    target = WORK / 'runs' / launch.safe_name(args.name)
    if target.exists():
        raise RuntimeError('Trial already exists; use a fresh name')
    manifest = json.loads((TEMPLATE / 'manifest.json').read_text())
    if not manifest['complete']:
        raise RuntimeError('Incomplete snapshot')
    cfg = dict(line.split('=', 1) for line in (TEMPLATE / 'source-instance.cfg')
               .read_text(encoding='utf-8').splitlines() if '=' in line)
    if cfg.get('JvmArgs') != '-XX:+UseZGC -XX:+ZGenerational':
        raise RuntimeError('Unrecognized JVM arguments; do not silently replace them')
    jar = build()
    shutil.copytree(TEMPLATE, target)
    backups = target / 'settings-before'
    backups.mkdir()
    if args.no_recap:
        recap = target / 'mods/respawnrecap-neoforge-1.2.1-1.21.1.jar'
        if not recap.is_file():
            raise RuntimeError('Expected installed Respawn Recap artifact absent')
        shutil.move(str(recap), str(backups / recap.name))
    for relative in ('options.txt', 'config/acceleratedrendering-client.toml',
                     'config/iris.properties', 'config/spark/config.json',
                     'shaderpacks/photon-actually-3d-crops-cavity.zip.txt'):
        shutil.copy2(target / relative, backups / Path(relative).name)
    config = target / 'config/acceleratedrendering-client.toml'
    original = config.read_bytes()
    old = b'gecko_feature_status = "DISABLED"'
    if original.count(old) != 1:
        raise RuntimeError('Unexpected Gecko configuration')
    config.write_bytes(original.replace(old, f'gecko_feature_status = "{args.gecko}"'.encode()))
    spark_config = target / 'config/spark/config.json'
    spark = json.loads(spark_config.read_text(encoding='utf-8'))
    if spark.get('backgroundProfiler') is not True:
        raise RuntimeError('Unexpected source Spark backgroundProfiler setting')
    if args.no_spark_background:
        spark['backgroundProfiler'] = False
        save(spark_config, spark)
    shader = target / 'shaderpacks/photon-actually-3d-crops-cavity.zip.txt'
    if args.no_pom:
        contents = shader.read_bytes()
        if contents.count(b'POM=true') != 1:
            raise RuntimeError('Unexpected POM configuration')
        shader.write_bytes(contents.replace(b'POM=true', b'POM=false'))
    shutil.copy2(jar, target / 'mods' / jar.name)
    # Preserve fullscreen and image quality; only vary the declared cap/sync.
    lines = (target / 'options.txt').read_text(encoding='utf-8').splitlines()
    edits = {'enableVsync': str(args.vsync).lower(), 'maxFps': str(args.cap)}
    (target / 'options.txt').write_text('\n'.join(
        f'{line.split(":", 1)[0]}:{edits[line.split(":", 1)[0]]}'
        if line.split(':', 1)[0] in edits else line for line in lines) + '\n', encoding='utf-8')
    runtime = argparse.Namespace(width=1920, height=1080, timeout=900,
        libraries=launch.DEFAULT_LIBRARIES, assets=launch.DEFAULT_ASSETS,
        java=launch.DEFAULT_JAVA, collector='zgc', heap_min_mib=int(cfg['MinMemAlloc']),
        heap_max_mib=int(cfg['MaxMemAlloc']), property=[
            'fpsprobe.world=fps-scene', f'fpsprobe.output={target / "receipt.json"}',
            f'fpsprobe.vsync={str(args.vsync).lower()}', f'fpsprobe.cap={args.cap}',
            f'fpsprobe.warmup={args.warmup}', f'fpsprobe.seconds={args.seconds}',
            *[f'fpsprobe.{key}={getattr(args, key)}' for key in ('x', 'y', 'z', 'yaw', 'pitch')],
            f'fpsprobe.entities={str(not args.hide_entities).lower()}'])
    _, native_jars, _ = launch.production_libraries(runtime.libraries)
    launch.extract_natives(native_jars, target / 'natives')
    command = launch.make_command(runtime, target, target / 'natives')
    inputs = {relative: digest(target / relative) if (target / relative).is_file() else None
              for relative in manifest['files_sha256']}
    save(target / 'input-receipt.json', {'baseline_manifest_sha256': digest(TEMPLATE / 'manifest.json'),
        'files_sha256': inputs, 'probe_sha256': digest(jar),
        'max_heap_mib': runtime.heap_max_mib, 'min_heap_mib': runtime.heap_min_mib,
        'vsync': args.vsync, 'cap': args.cap, 'gecko': args.gecko,
        'pom_disabled': args.no_pom, 'entities_hidden': args.hide_entities,
        'spark_background_disabled': args.no_spark_background,
        'respawn_recap_disabled': args.no_recap,
        'camera': {key: getattr(args, key) for key in ('x', 'y', 'z', 'yaw', 'pitch')},
        'warmup_seconds': args.warmup,
        'jfr': args.jfr, 'presentmon': args.presentmon, 'started_epoch': time.time()})
    (target / 'logs').mkdir(exist_ok=True)
    record_driver(target, 'before')
    with (target / 'logs/launcher.log').open('w', encoding='utf-8') as log:
        process = subprocess.Popen(command, cwd=target, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=FLAGS)
    save(target / 'process.json', {'pid': process.pid})
    print(f'Launched PID {process.pid}: {target}', flush=True)
    presentmon = None
    pm_log = None
    pm_exe = WORK / 'tools/presentmon/PresentMon-2.6.0-x64.exe'
    pm_session = f'td-fps-{process.pid}'
    if args.presentmon:
        pm_log = (target / 'presentmon.log').open('w', encoding='utf-8')
        presentmon = subprocess.Popen([str(pm_exe), '--process_id', str(process.pid),
            '--output_file', str(target / 'presentmon.csv'), '--date_time', '--v2_metrics',
            '--no_console_stats', '--terminate_on_proc_exit', '--timed', '900',
            '--terminate_after_timed', '--session_name', pm_session],
            stdout=pm_log, stderr=subprocess.STDOUT, creationflags=FLAGS)
    gpu_log = (target / 'gpu.csv').open('w', encoding='utf-8')
    gpu_monitor = None
    try:
        gpu_monitor = subprocess.Popen(['nvidia-smi',
            '--query-gpu=timestamp,utilization.gpu,memory.used,power.draw,clocks.gr,temperature.gpu',
            '--format=csv,noheader,nounits', '--loop=1'], stdout=gpu_log,
            stderr=subprocess.DEVNULL, creationflags=FLAGS)
    except OSError:
        gpu_log.close()
    start = time.monotonic()
    jfr_started = False
    focused = False
    external_java_during_sample = set()
    ready = False
    resources = (target / 'resources.csv').open('w', encoding='utf-8')
    resources.write('elapsed_s,sample_phase,rss_bytes,private_bytes,page_faults,available_ram_bytes,pagefile_used_bytes,system_cpu_percent,process_cpu_seconds\n')
    watched = psutil.Process(process.pid)
    psutil.cpu_percent()
    try:
        while process.poll() is None and time.monotonic() - start < 900:
            # One focus request near startup; do not repeatedly steal user focus.
            if not focused and time.monotonic() - start > 20:
                focus(process.pid)
                focused = True
            latest = target / 'logs/latest.log'
            if not ready:
                ready = latest.exists() and 'FPS_PROBE_SAMPLE_START' in latest.read_text(encoding='utf-8', errors='replace')
            if ready and not (target / 'receipt.json').exists():
                external_java_during_sample.update(p.pid for p in psutil.process_iter(['name'])
                    if p.pid != process.pid and (p.info['name'] or '').lower() in ('java.exe', 'javaw.exe'))
            try:
                memory = watched.memory_info()
                resources.write(f'{time.monotonic()-start:.3f},{int(ready and not (target / "receipt.json").exists())},'
                    f'{memory.rss},{getattr(memory,"private",0)},{getattr(memory,"num_page_faults",0)},'
                    f'{psutil.virtual_memory().available},{psutil.swap_memory().used},'
                    f'{psutil.cpu_percent()},{sum(watched.cpu_times()[:2])}\n')
                resources.flush()
            except psutil.NoSuchProcess:
                pass
            if args.jfr and ready and not jfr_started:
                ascii_root = Path(args.jfr_root) / args.name
                if not str(ascii_root).isascii():
                    raise RuntimeError('JFR repository and output require ASCII paths')
                ascii_root.mkdir(parents=True, exist_ok=False)
                jcmd = str(launch.DEFAULT_JAVA.with_name('jcmd.exe'))
                commands = [['JFR.configure', f'repositorypath={ascii_root / "repository"}', f'dumppath={ascii_root}'],
                            ['JFR.start', 'name=fpsprobe', 'settings=profile',
                             f'duration={max(1, args.seconds - 5)}s', f'filename={ascii_root / "sample.jfr"}',
                             'jdk.ThreadDump#enabled=false']]
                for subcommand in commands:
                    result = subprocess.run([jcmd, str(process.pid), *subcommand], capture_output=True,
                                            text=True, creationflags=FLAGS, timeout=30)
                    with (target / 'jfr.log').open('a', encoding='utf-8') as log:
                        log.write(result.stdout + result.stderr)
                    if result.returncode:
                        raise RuntimeError('JFR attach failed; see jfr.log')
                jfr_started = True
            time.sleep(1)
    except BaseException:
        save(target / 'launch-result.json', {'interrupted': True, 'pid': process.pid,
             'note': 'Inspect the task-owned Java child before retrying; child may still be running'})
        raise
    finally:
        if presentmon:
            if presentmon.poll() is None:
                subprocess.run([str(pm_exe), '--session_name', pm_session, '--terminate_existing_session'],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=FLAGS, timeout=15)
                try:
                    presentmon.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    presentmon.terminate()
                    presentmon.wait(timeout=10)
            save(target / 'presentmon-result.json', {'exit_code': presentmon.returncode,
                'session_name': pm_session, 'target_pid': process.pid,
                'csv_exists': (target / 'presentmon.csv').exists()})
        if pm_log:
            pm_log.close()
        if gpu_monitor and gpu_monitor.poll() is None:
            gpu_monitor.terminate()
            gpu_monitor.wait(timeout=10)
        gpu_log.close()
        resources.close()
    if process.poll() is None:
        save(target / 'launch-result.json', {'timeout': True, 'pid': process.pid,
             'note': 'Process retained for inspection; no unrelated process stopped'})
        raise RuntimeError('Launch timed out; inspect this PID before retrying')
    save(target / 'launch-result.json', {'exit_code': process.returncode, 'jfr_started': jfr_started,
         'external_java_during_sample': sorted(external_java_during_sample)})
    if process.returncode or not (target / 'receipt.json').is_file():
        raise RuntimeError('No successful fresh probe receipt; inspect launcher/latest logs')
    if not json.loads((target / 'receipt.json').read_text(encoding='utf-8')).get('success'):
        raise RuntimeError('Fresh probe receipt is unsuccessful; inspect receipt')
    if external_java_during_sample:
        raise RuntimeError('Other Java work overlapped sampling; do not use for comparison')
    record_driver(target, 'after')
    before = json.loads((target / 'driver-settings-before.json').read_text(encoding='utf-8'))
    after = json.loads((target / 'driver-settings-after.json').read_text(encoding='utf-8'))
    if before != after:
        raise RuntimeError('Driver settings changed during trial; do not use for comparison')
    print((target / 'receipt.json').read_text(encoding='utf-8'), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    snap = sub.add_parser('snapshot')
    snap.add_argument('--source', type=Path, default=launch.DEFAULT_INSTANCE)
    snap.add_argument('--world', required=True)
    run = sub.add_parser('run')
    run.add_argument('name')
    run.add_argument('--vsync', action=argparse.BooleanOptionalAction, default=True)
    run.add_argument('--cap', type=int, choices=(60, 70, 260), default=70)
    run.add_argument('--gecko', choices=('ENABLED', 'DISABLED'), default='DISABLED')
    run.add_argument('--no-pom', action='store_true')
    run.add_argument('--no-spark-background', action='store_true')
    run.add_argument('--no-recap', action='store_true', help='Diagnostic clone only: back up and omit Respawn Recap')
    run.add_argument('--hide-entities', action='store_true')
    run.add_argument('--seconds', type=int, default=90)
    run.add_argument('--warmup', type=int, default=45)
    for key, default in [('x', 39.587), ('y', 99.88247), ('z', -572.153),
                         ('yaw', -129.3), ('pitch', 36.6)]:
        run.add_argument(f'--{key}', type=float, default=default)
    run.add_argument('--jfr', action='store_true')
    run.add_argument('--presentmon', action='store_true')
    run.add_argument('--jfr-root', default='C:/Users/Public/td-fps-profile')
    args = parser.parse_args()
    if args.command == 'snapshot':
        snapshot(args.source, args.world)
    else:
        trial(args)
