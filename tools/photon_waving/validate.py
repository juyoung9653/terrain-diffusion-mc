"""Compile affected Photon programs with the installed settings, without Minecraft."""
import argparse
import hashlib
import json
import math
import posixpath
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.photon_dof.validate_gl import jar, java_tool, setting
from tools.photon_dof.build import apply_setting


def expand(files, name, stack=()):
    if name in stack:
        raise ValueError('Include cycle: ' + name)
    source = files[name].decode('utf-8-sig').replace('\r\n', '\n')
    def include(match):
        path = match.group(1)
        path = path.lstrip('/') if path.startswith('/') else posixpath.normpath(posixpath.join(posixpath.dirname(name), path))
        return '\n' + expand(files, path, stack + (name,)) + '\n'
    return re.sub(r'^\s*#include\s+"([^"]+)"\s*$', include, source, flags=re.M)


def check_boundaries():
    # The domain contract: exactly nine whole chunk columns, including negatives.
    cases = 0
    for px, pz in [(0, 0), (15.999, 15.999), (16, 16), (-0.001, -0.001),
                   (-16, -16), (-16.001, 16), (100000, -100000)]:
        cx, cz = math.floor(px / 16), math.floor(pz / 16)
        accepted = set()
        for x in range(cx - 3, cx + 4):
            for z in range(cz - 3, cz + 4):
                expected = abs(x - cx) <= 1 and abs(z - cz) <= 1
                for bx, bz in [(0, 0), (15, 15), (0, 15), (15, 0)]:
                    center = (16*x + bx + .5, 16*z + bz + .5)
                    # Vertices on or over a chunk boundary still use their owner.
                    for dx, dz in [(-.5, -.5), (.5, .5), (-.75, .75)]:
                        vertex = (center[0] + dx, center[1] + dz)
                        mid = (-dx*64, -dz*64)
                        owner = [math.floor(vertex[i] + mid[i]/64) + .5 for i in (0, 1)]
                        actual = max(abs(math.floor(owner[0]/16) - cx),
                                     abs(math.floor(owner[1]/16) - cz)) <= 1
                        assert actual == expected
                        cases += 1
                        if actual: accepted.add((x, z))
        assert len(accepted) == 9
    return cases


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('shader_zip', type=Path)
    ap.add_argument('--settings', type=Path, required=True)
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.shader_zip) as z:
        files = {n[len('shaders/'):]: z.read(n) for n in z.namelist()
                 if n.startswith('shaders/') and not n.endswith('/')}
    settings_text = files['settings.glsl'].decode('utf-8-sig')
    for line in args.settings.read_text(encoding='utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            settings_text = apply_setting(settings_text, key.strip(), value.strip())
    files['settings.glsl'] = settings_text.encode('utf-8')
    classes = args.output / 'classes'
    classes.mkdir(exist_ok=True)
    libs = [jar(m) for m in ('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')]
    flags = {'creationflags': subprocess.CREATE_NO_WINDOW} if sys.platform == 'win32' else {}
    subprocess.run([java_tool('javac'), '-cp', ';'.join(map(str, libs)), '-d', str(classes),
                    str(ROOT/'tools/photon_dof/ShaderCompileCheck.java')], check=True, **flags)
    cp = ';'.join(map(str, [classes, *libs, *[jar(m, True) for m in ('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')]]))
    programs = ['gbuffers_terrain', 'gbuffers_terrain_solid', 'gbuffers_water',
                'gbuffers_entities', 'gbuffers_hand', 'gbuffers_armor_glint',
                'shadow', 'shadow_solid', 'shadow_cutout', 'shadow_water', 'shadow_entities']
    variants = [(w, p, True, True) for w in ('0', '-1', '1') for p in programs]
    variants += [('0', p, plants, leaves) for p in ('gbuffers_terrain', 'shadow_cutout')
                 for plants, leaves in ((False, False), (True, False), (False, True))]
    rows = []
    for world, program, plants, leaves in variants:
        label = f'world{world}-{program}-plants{int(plants)}-leaves{int(leaves)}'
        folder = args.output / label
        folder.mkdir(exist_ok=True)
        paths = []
        for stage in ('vsh', 'fsh'):
            source = expand(files, f'world{world}/{program}.{stage}')
            source = setting(setting(source, 'WAVING_PLANTS', plants), 'WAVING_LEAVES', leaves)
            head = '#define MC_VERSION 12101\n#define MC_HAND_DEPTH 0.125\n#define MC_SHADOW_QUALITY 1.0\n#define IS_IRIS\n#define VOXY\n'
            source = re.sub(r'(?m)^(#version[^\n]*\n)', r'\1' + head, source, count=1)
            path = folder / ('shader.' + stage)
            path.write_text(source, encoding='utf-8')
            paths.append(str(path))
        run = subprocess.run([java_tool('java'), '-cp', cp, 'ShaderCompileCheck', *paths],
                             capture_output=True, text=True, timeout=45, **flags)
        try:
            detail = json.loads(run.stdout.strip().splitlines()[-1])
        except (IndexError, json.JSONDecodeError):
            detail = {'ok': False, 'error': run.stderr[-4000:] or run.stdout[-4000:]}
        rows.append({'variant': label, 'returncode': run.returncode, **detail})
        if not detail['ok']: print(json.dumps(rows[-1]), flush=True)
    report = {'shader_sha256': hashlib.sha256(args.shader_zip.read_bytes()).hexdigest(),
              'settings_sha256': hashlib.sha256(args.settings.read_bytes()).hexdigest(),
              'boundary_cases': check_boundaries(), 'variants': rows,
              'passed': sum(r['ok'] and r['returncode'] == 0 for r in rows), 'total': len(rows),
              'scope': 'Standalone OpenGL compile/link with Iris/VOXY macros and settings; CPU boundary contract. No in-game/FPS validation.'}
    (args.output/'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({k: report[k] for k in ('passed', 'total', 'boundary_cases', 'scope')}))
    raise SystemExit(0 if report['passed'] == report['total'] else 1)


if __name__ == '__main__':
    main()
