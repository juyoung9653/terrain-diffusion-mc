"""Compile/link Photon composite2 variants in a hidden GL 4 compatibility context.

This checks standalone GLSL expansion only. It is not an Iris runtime test or FPS benchmark.
"""
import argparse, hashlib, json, re, subprocess, zipfile
from itertools import product
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.gradle/caches/modules-2/files-2.1/org.lwjgl'

def java_tool(name):
    # WindowsApps/Oracle javapath shims can block unattended invocations here.
    # Prefer an installed JDK executable; do not download or alter JAVA_HOME.
    candidates = sorted(Path('C:/Program Files/Java').glob('jdk-*/bin/' + name + '.exe'), reverse=True)
    return str(candidates[0]) if candidates else name

def jar(module, native=False):
    pattern = f'{module}-3.3.3-natives-windows.jar' if native else f'{module}-3.3.3.jar'
    hits = list((CACHE/module/'3.3.3').rglob(pattern))
    if len(hits) != 1: raise RuntimeError(f'expected one {pattern}, got {hits}')
    return hits[0]

def expand(files, name, stack=()):
    if name in stack: raise ValueError('include cycle: ' + ' -> '.join(stack + (name,)))
    try: text = files[name].decode('utf-8-sig').replace('\r\n', '\n').replace('\r', '\n')
    except KeyError: raise ValueError(f'missing include {name}, requested by {stack[-1] if stack else "root"}')
    def repl(m):
        include = m.group(1).lstrip('/')
        return '\n// begin include ' + include + '\n' + expand(files, include, stack + (name,)) + '\n// end include ' + include + '\n'
    return re.sub(r'^\s*#include\s+"([^"]+)"\s*$', repl, text, flags=re.M)

def setting(text, name, enabled, required=True):
    pattern = r'(?m)^[ \t]*(?://[ \t]*)?#define[ \t]+' + re.escape(name) + r'\b.*$'
    replacement = ('#define ' + name) if enabled else ('// #define ' + name)
    rewritten, count = re.subn(pattern, replacement, text)
    if required and count != 1:
        raise ValueError(f'expected exactly one setting {name}, got {count}')
    return rewritten

def variant(files, world, lod, taau, fast, has_fastpath):
    fsh = expand(files, f'world{world}/composite2.fsh')
    vsh = expand(files, f'world{world}/composite2.vsh')
    for name, enabled, required in [('DOF', True, True), ('TAA', taau, True), ('TAAU', taau, True), ('DOF_SKIP_SUBPIXEL', fast, has_fastpath)]:
        fsh, vsh = setting(fsh, name, enabled, required), setting(vsh, name, enabled, required)
    world_macro = {'0':'WORLD_OVERWORLD', '-1':'WORLD_NETHER', '1':'WORLD_END'}[world]
    for source in ('fsh', 'vsh'):
        value = fsh if source == 'fsh' else vsh
        value = re.sub(r'(?m)^#define WORLD_(OVERWORLD|NETHER|END)\s*$', '#define ' + world_macro, value, count=1)
        # Iris normally supplies loader macros. This harmless value is used only
        # to let an isolated desktop compiler type-check Photon helper code.
        head = '#define MC_VERSION 12101\n#define MC_HAND_DEPTH 0.125\n' + (('#define ' + lod + '\n') if lod else '')
        value = re.sub(r'(?m)^(#version[^\n]*\n)', r'\1' + head, value, count=1)
        if source == 'fsh': fsh = value
        else: vsh = value
    return vsh, fsh

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('shader_zip', type=Path); ap.add_argument('--output', type=Path, required=True)
    args=ap.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    if not args.shader_zip.is_file(): raise SystemExit('shader ZIP is missing: ' + str(args.shader_zip))
    with zipfile.ZipFile(args.shader_zip) as z:
        roots = [n.split('shaders/',1)[0] for n in z.namelist() if n.endswith('shaders/world0/composite2.fsh')]
        if len(roots) != 1: raise SystemExit('expected one shaders/world0/composite2.fsh')
        prefix=roots[0]+'shaders/'
        files={n[len(prefix):]:z.read(n) for n in z.namelist() if n.startswith(prefix) and not n.endswith('/')}
    has_fastpath = any(b'DOF_SKIP_SUBPIXEL' in body for body in files.values())
    classes=args.output/'classes'; classes.mkdir(exist_ok=True)
    cp=';'.join(map(str,[jar('lwjgl'),jar('lwjgl-glfw'),jar('lwjgl-opengl')]))
    source=ROOT/'tools/photon_dof/ShaderCompileCheck.java'
    subprocess.run([java_tool('javac'),'-cp',cp,'-d',str(classes),str(source)],check=True)
    runtime_cp=';'.join(map(str,[classes,jar('lwjgl'),jar('lwjgl-glfw'),jar('lwjgl-opengl'),jar('lwjgl',True),jar('lwjgl-glfw',True),jar('lwjgl-opengl',True)]))
    rows=[]
    for world,lod,taau,fast in product(('0','-1','1'),('', 'VOXY','DISTANT_HORIZONS'),(False,True),(False,True)):
        label=f'world{world}-lod{lod or "none"}-taau{int(taau)}-fast{int(fast)}'; folder=args.output/'variants'/label; folder.mkdir(parents=True,exist_ok=True)
        vsh,fsh=variant(files,world,lod,taau,fast,has_fastpath); (folder/'composite2.vsh').write_text(vsh,encoding='utf-8'); (folder/'composite2.fsh').write_text(fsh,encoding='utf-8')
        run=subprocess.run([java_tool('java'),'-cp',runtime_cp,'ShaderCompileCheck',str(folder/'composite2.vsh'),str(folder/'composite2.fsh')],text=True,capture_output=True, timeout=30)
        try: detail=json.loads(run.stdout.strip().splitlines()[-1])
        except (IndexError,json.JSONDecodeError): detail={'ok':False,'error':run.stderr[-4000:] or run.stdout[-4000:]}
        rows.append({'variant':label,'returncode':run.returncode,**detail})
    report={'shader_zip':str(args.shader_zip.resolve()),'shader_zip_sha256':hashlib.file_digest(args.shader_zip.open('rb'),'sha256').hexdigest(),'has_dof_skip_subpixel_option':has_fastpath,'variants':rows,'passed':sum(bool(x['ok']) for x in rows),'total':len(rows),'scope':'Standalone GLFW OpenGL 4 compatibility compile/link; not Iris runtime or FPS evidence.'}
    (args.output/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8'); print(json.dumps({'passed':report['passed'],'total':report['total'],'report':str((args.output/'report.json').resolve())}))
    raise SystemExit(0 if report['passed']==report['total'] else 1)
if __name__=='__main__': main()
