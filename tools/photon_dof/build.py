"""Build an isolated Photon DOF experiment from the inspected local shader pack."""
import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path

SOURCE_SHA256 = 'bae80fa276cb2d7e23e2414711a648f8f3254497aed1864db37cfceb5bc3bca0'


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def replace_once(text, before, after):
    if text.count(before) != 1:
        raise ValueError('Source anchor changed: ' + before[:90])
    return text.replace(before, after, 1)


def apply_setting(text, name, value):
    text = text.replace('\r\n', '\n')
    pattern = re.compile(r'^([ \t]*)(?://[ \t]*)?#define[ \t]+' + re.escape(name)
                         + r'(?:[ \t]+([^\r\n]*))?$', re.MULTILINE)
    matches = list(pattern.finditer(text))
    if not matches:
        constant = re.compile(r'^(\s*const\s+(?:int|float|bool)\s+' + re.escape(name)
                              + r'\s*=\s*)[^;\n]+(;[^\n]*)$', re.MULTILINE)
        if len(list(constant.finditer(text))) == 1:
            return constant.sub(lambda m: m.group(1) + value + m.group(2), text)
    if len(matches) != 1:
        raise ValueError('Expected one shader option: ' + name)
    match = matches[0]
    # Match indentation without consuming neighbouring blank lines.
    original = match.group(0)
    prefix = original[:len(original) - len(original.lstrip())]
    tail = match.group(2) or ''
    comment = ' //' + tail.split('//', 1)[1] if '//' in tail else ''
    if value in ('true', 'false'):
        replacement = prefix + ('' if value == 'true' else '//') + '#define ' + name + comment
    else:
        replacement = prefix + '#define ' + name + ' ' + value + comment
    return text[:match.start()] + replacement + text[match.end():]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--settings', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    source_bytes = args.source.read_bytes()
    if sha256(source_bytes) != SOURCE_SHA256:
        raise SystemExit('Source ZIP changed; review the new source before rebuilding')
    if args.output.exists() or args.output.resolve() == args.source.resolve():
        raise SystemExit('Output must be a new file, separate from the installed shader')
    settings_bytes = args.settings.read_bytes()
    overrides = {}
    for line in settings_bytes.decode('utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            overrides[key.strip()] = value.strip()

    with zipfile.ZipFile(args.source) as source:
        roots = [n[:-len('shaders/settings.glsl')] for n in source.namelist()
                 if n.endswith('shaders/settings.glsl')]
        if len(roots) != 1:
            raise ValueError('Expected one shader root')
        root = roots[0]
        files = {}
        for info in source.infolist():
            if info.is_dir():
                continue
            if not info.filename.startswith(root):
                raise ValueError('Unexpected archive member outside shader root')
            name = info.filename[len(root):]
            if '..' in Path(name).parts or Path(name).is_absolute() or name in files:
                raise ValueError('Unsafe or duplicate ZIP member')
            files[name] = source.read(info)
    originals = dict(files)

    path = 'shaders/program/c2_dof.fsh'
    text = files[path].decode('utf-8-sig')
    noise = '''    // Calculate vogel disk rotation
    float theta = texelFetch(noisetex, texel & 511, 0).b;
    theta = r1(frameCounter, theta);
    theta *= tau;

'''
    text = replace_once(text, noise, '')
    insertion = '''#ifdef DOF_SKIP_SUBPIXEL
    // CoC is measured in output UV coordinates, including when TAAU is active.
    // A quarter-output-pixel cutoff keeps visibly defocused pixels unchanged.
    vec2 radius_pixels = abs(CoC) / view_pixel_size;
    if (max(radius_pixels.x, radius_pixels.y) <= 0.25) {
        scene_color = texelFetch(colortex0, texel, 0).rgb;
        return;
    }
#endif

'''
    text = replace_once(text, '    scene_color = vec3(0.0);', insertion + noise
                        + '    scene_color = vec3(0.0);')
    files[path] = text.encode('utf-8')

    path = 'shaders/settings.glsl'
    text = files[path].decode('utf-8-sig')
    for name, value in overrides.items():
        text = apply_setting(text, name, value)
    text = apply_setting(text, 'DOF', 'true')
    text = replace_once(text, '#define DOF\n',
                        '#define DOF\n  #define DOF_SKIP_SUBPIXEL\n')
    files[path] = text.encode('utf-8')

    path = 'shaders/shaders.properties'
    text = files[path].decode('utf-8-sig')
    text = replace_once(text, 'DOF DOF_INTENSITY DOF_SAMPLES',
                        'DOF DOF_SKIP_SUBPIXEL DOF_INTENSITY DOF_SAMPLES')
    files[path] = text.encode('utf-8')
    translations = {
        'en_US': ('Skip Subpixel DOF', 'Experimental: bypass blur at radii of 0.25 output pixels or less. '
                  'Preserves the full sample count for larger blur. Does not reduce cloud or terrain rendering.'),
        'ko_KR': ('미세한 피사계 심도 계산 생략', '실험 기능: 흐림 반경이 출력 화면의 0.25픽셀 이하이면 계산을 생략합니다. '
                  '더 큰 흐림의 샘플 수와 구름·지형 렌더링은 유지합니다.'),
    }
    for language, (label, comment) in translations.items():
        path = f'shaders/lang/{language}.lang'
        text = files.get(path, b'').decode('utf-8-sig').rstrip('\r\n')
        text += '\n\noption.DOF_SKIP_SUBPIXEL = ' + label
        text += '\noption.DOF_SKIP_SUBPIXEL.comment = ' + comment + '\n'
        files[path] = text.encode('utf-8')

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'x', compression=zipfile.ZIP_DEFLATED) as target:
        for name, data in files.items():
            target.writestr(name, data)
    with zipfile.ZipFile(args.output) as target:
        if target.testzip() is not None or set(target.namelist()) != set(files):
            raise ValueError('Output archive verification failed')
        for name, expected in files.items():
            if target.read(name) != expected:
                raise ValueError('Output member mismatch: ' + name)
    changed = [name for name in files if files[name] != originals.get(name)]
    if set(changed) != {'shaders/program/c2_dof.fsh', 'shaders/settings.glsl',
                        'shaders/shaders.properties', 'shaders/lang/en_US.lang',
                        'shaders/lang/ko_KR.lang'}:
        raise ValueError('Unexpected changed-member set')
    result = {'source_sha256': SOURCE_SHA256, 'source_settings_sha256': sha256(settings_bytes),
              'output_sha256': sha256(args.output.read_bytes()), 'output': str(args.output.resolve()),
              'changed_members': changed, 'unchanged_members': len(files) - len(changed),
              'source_settings_snapshot': overrides, 'experiment_overrides': {'DOF': 'true', 'DOF_SKIP_SUBPIXEL': 'true'},
              'output_pixel_radius_cutoff': 0.25, 'source_unchanged': args.source.read_bytes() == source_bytes,
              'source_settings_unchanged': args.settings.read_bytes() == settings_bytes,
              'runtime_validated': False, 'fps_gain_measured': False}
    args.output.with_suffix('.manifest.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k != 'source_settings_snapshot'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
