"""Measure actual terrain/shadow vertex motion using GPU transform feedback.

Runs the pack's complete vertex entry points in a hidden OpenGL context, with
controlled textures, matrices, attributes and uniforms. This is not a live Iris
capture: it isolates the pack logic from renderer/mod interactions.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.photon_waving.validate import expand
from tools.photon_waving.build import replace_once
from tools.photon_dof.validate_gl import jar, java_tool
from tools.photon_dof.build import apply_setting


def prepare(pack, settings, folder):
    folder.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(pack) as z:
        files = {n[8:]: z.read(n) for n in z.namelist()
                 if n.startswith('shaders/') and not n.endswith('/')}
    text = files['settings.glsl'].decode('utf-8-sig')
    for line in settings.splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            name, value = line.split('=', 1)
            text = apply_setting(text, name.strip(), value.strip())
    files['settings.glsl'] = text.encode('utf-8')
    paths = []
    for program in ('gbuffers_terrain', 'shadow', 'shadow_cutout', 'shadow_solid'):
        text = expand(files, f'world0/{program}.vsh')
        text = replace_once(text, 'void main() {', 'out vec3 probe_position;\nvoid main() {')
        text = replace_once(text, '    pos = pos - cameraPosition;',
                            '    probe_position = pos;\n    pos = pos - cameraPosition;')
        head = '#define MC_VERSION 12101\n#define MC_HAND_DEPTH 0.125\n#define MC_SHADOW_QUALITY 1.0\n#define IS_IRIS\n#define VOXY\n'
        text = re.sub(r'(?m)^(#version[^\n]*\n)', r'\1' + head, text, count=1)
        path = folder / f'{program}.vsh'
        path.write_text(text, encoding='utf-8')
        paths.append(str(path))
    return paths


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('shader_zip', type=Path)
    ap.add_argument('--baseline', type=Path, required=True)
    ap.add_argument('--settings', type=Path, required=True)
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    classes = args.output / 'classes'
    classes.mkdir(exist_ok=True)
    libs = [jar(m) for m in ('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')]
    flags = {'creationflags': subprocess.CREATE_NO_WINDOW} if sys.platform == 'win32' else {}
    subprocess.run([java_tool('javac'), '-cp', ';'.join(map(str, libs)), '-d', str(classes),
                    str(ROOT/'tools/photon_waving/WavingProbe.java')], check=True, **flags)
    cp = ';'.join(map(str, [classes, *libs, *[jar(m, True) for m in ('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')]]))
    report = {'scope': __doc__.strip(), 'settings_sha256': hashlib.sha256(args.settings.read_bytes()).hexdigest(),
              'times_seconds': [0, 7.25, 23.5], 'results': {}}
    for label, pack in [('baseline', args.baseline), ('nine_chunks', args.shader_zip)]:
        paths = prepare(pack, args.settings.read_text(encoding='utf-8-sig'), args.output/label)
        run = subprocess.run([java_tool('java'), '-cp', cp, 'WavingProbe', *paths],
                             capture_output=True, text=True, timeout=240, **flags)
        (args.output/f'{label}.stdout.log').write_text(run.stdout, encoding='utf-8')
        (args.output/f'{label}.stderr.log').write_text(run.stderr, encoding='utf-8')
        if run.returncode:
            raise RuntimeError(f'{label} failed: {run.stderr[-4000:]}')
        rows = [json.loads(line) for line in run.stdout.splitlines() if line.startswith('{')]
        if len(rows) != 4:
            raise RuntimeError('Incomplete GPU capture')
        report['results'][label] = {'sha256': hashlib.sha256(pack.read_bytes()).hexdigest(), 'programs': rows}
        print(json.dumps({'pack': label, 'programs': rows}), flush=True)
    original = report['results']['baseline']['programs']
    limited = report['results']['nine_chunks']['programs']
    checks = {
        'original_outside_motion_detected': all(r['outside_moving'] > 0 for r in original),
        'limited_outside_motion_zero': all(r['max_outside_motion'] == 0 for r in limited),
        'limited_inside_motion_detected': all(r['inside_moving'] > 0 for r in limited),
        'terrain_shadow_positions_match': all(r['max_terrain_shadow_difference'] < 1e-5 for r in limited),
        'fixed_plant_shadow_moves_with_light': all(r['light_rotation_clip_motion'] > 0 and r['light_rotation_world_motion'] < 1e-4
                                                  for r in limited if r['program'].startswith('shadow')),
    }
    report['checks'] = checks
    report['passed'] = all(checks.values())
    (args.output/'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({'passed': report['passed'], 'checks': checks}))
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
