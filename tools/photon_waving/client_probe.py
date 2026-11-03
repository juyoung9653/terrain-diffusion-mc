"""Run a fixed-time Photon visual diagnostic in a fresh, workspace-only clone.

The copied world is the existing FPS-probe fixture.  The current Prism instance
is read only; this tool copies its mods/config/resource packs and exactly one
chosen shader ZIP into a fresh run directory, then starts an offline client.
"""
from __future__ import annotations
import argparse, copy, hashlib, json, math, os, re, shutil, subprocess, sys, time, zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/optimization_bench'))
import launch
sys.path.insert(0,str(ROOT))
from tools.photon_dof.build import apply_setting

WORK=ROOT/'build/photon-waving-9chunks-20261029/client-probe'
FIXTURE=ROOT/'build/fps-probe/template/saves/fps-scene'
SRC=ROOT/'tools/photon_waving/client_src'
FLAGS=getattr(subprocess,'CREATE_NO_WINDOW',0)
SAMPLER_FREEZES={
    'pcf': ('shaders/include/lighting/shadows/pcss.glsl','dither = r1(frameCounter, dither);','dither = r1(0, dither);'),
    'dof': ('shaders/program/c2_dof.fsh','theta = r1(frameCounter, theta);','theta = r1(0, theta);'),
    'ao': ('shaders/program/d3_ao.fsh','dither = r2(frameCounter, dither);','dither = r2(0, dither);'),
    'ssrt': ('shaders/include/lighting/shadows/ssrt.glsl','dither = r1(frameCounter, dither);','dither = r1(0, dither);'),
    'volumetric': ('shaders/program/c0_vl.fsh','dither = r1(frameCounter, dither);','dither = r1(0, dither);'),
    'deferred': ('shaders/program/d4_deferred_shading.fsh','dither = r1(frameCounter, dither);','dither = r1(0, dither);'),
}
STOCHASTIC_GROUPS={
    'surface': {'shaders/include/lighting/specular_lighting.glsl','shaders/program/gbuffers_all_solid.fsh','shaders/program/gbuffers_all_translucent.fsh','shaders/program/dh_terrain.fsh','shaders/program/dh_water.fsh'},
    'clouds': {'shaders/program/d1_clouds.fsh','shaders/include/sky/blocky_clouds.glsl'},
    'lighting': {entry[0] for entry in SAMPLER_FREEZES.values()},
}

def sha(path:Path): return launch.sha256(path)
def fail(message:str): raise RuntimeError(message)
def safe(name:str): return launch.safe_name(name)
def write(path:Path,value): path.write_text(json.dumps(value,indent=2,ensure_ascii=True)+'\n',encoding='utf-8')
def current_instance(): return Path(os.environ['APPDATA'])/'PrismLauncher/instances/1.21.1 neoforge rpg/minecraft'
def active_pack(instance:Path):
    iris=instance/'config/iris.properties'
    values=dict(line.split('=',1) for line in iris.read_text(encoding='utf-8').splitlines() if '=' in line)
    name=values.get('shaderPack','')
    if not name or Path(name).name != name: fail('Iris shaderPack is absent or unsafe')
    return instance/'shaderpacks'/name
def selected_packs(instance:Path):
    options=dict(line.split(':',1) for line in (instance/'options.txt').read_text(encoding='utf-8').splitlines() if ':' in line)
    return {item[5:] for item in json.loads(options.get('resourcePacks','[]')) if item.startswith('file/') and Path(item[5:]).name==item[5:]}
def build():
    sources=sorted(p for p in SRC.rglob('*') if p.is_file())
    if not sources: fail('client probe Java source missing')
    hashes={str(p.relative_to(SRC)):sha(p) for p in sources}
    iris_jars=sorted((current_instance()/'mods').glob('iris-*.jar'))
    if len(iris_jars)!=1: fail('Expected exactly one Iris JAR for diagnostic compilation')
    hashes['@compile-iris']=sha(iris_jars[0])
    cache=WORK/'compiled.json'
    if cache.exists():
        prior=json.loads(cache.read_text(encoding='utf-8'))
        jar=Path(prior['jar'])
        if prior.get('sources')==hashes and jar.is_file() and sha(jar)==prior.get('sha256'): return jar
    classes=WORK/f'classes-{time.time_ns()}'; classes.mkdir(parents=True)
    java=launch.DEFAULT_JAVA.with_name('javac.exe')
    classpath=launch.compile_classpath(launch.DEFAULT_LIBRARIES)+os.pathsep+str(iris_jars[0])
    result=subprocess.run([str(java),'-encoding','UTF-8','--release','21','-proc:none','-cp',classpath,'-d',str(classes),*map(str,[p for p in sources if p.suffix=='.java'])],capture_output=True,text=True,creationflags=FLAGS)
    (WORK/'compile.log').write_text(result.stdout+result.stderr,encoding='utf-8')
    if result.returncode: fail('Java compilation failed; see '+str(WORK/'compile.log'))
    jar=WORK/f'photon-waving-probe-{time.time_ns()}.jar'
    with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
        for p in classes.rglob('*'):
            if p.is_file(): z.write(p,p.relative_to(classes).as_posix())
        for p in SRC.rglob('*'):
            if p.is_file() and p.suffix!='.java': z.write(p,p.relative_to(SRC).as_posix())
    write(cache,{'sources':hashes,'jar':str(jar),'sha256':sha(jar)})
    return jar
def edit_options(path:Path,width=1280,height=720,render_distance=8,max_fps=120):
    edits={'fullscreen':'false','overrideWidth':str(width),'overrideHeight':str(height),'renderDistance':str(render_distance),'simulationDistance':'8','enableVsync':'false','maxFps':str(max_fps),'pauseOnLostFocus':'false'}
    original=path.read_text(encoding='utf-8').splitlines(); seen=set()
    lines=[]
    for line in original:
        key=line.split(':',1)[0]
        if key in edits: lines.append(key+':'+edits[key]); seen.add(key)
        else: lines.append(line)
    lines.extend(k+':'+v for k,v in edits.items() if k not in seen)
    path.write_text('\n'.join(lines)+'\n',encoding='utf-8')
def prepare(args):
    scene=None
    if args.scene_config:
        scene=json.loads(args.scene_config.read_text(encoding='utf-8'))
        if scene.get('kind')!='existing': fail('Only an existing-world scene configuration is supported')
        camera=scene.get('camera',{})
        if set(camera)!={'x','y','z','yaw','pitch'} or not all(isinstance(v,(float,int)) and math.isfinite(v) for v in camera.values()):
            fail('Existing-world scene requires a finite complete camera pose')
    if not 2<=args.render_distance<=32 or not 1<=args.max_fps<=260: fail('Invalid render distance or FPS limit')
    if args.benchmark_seconds and args.benchmark_seconds < 30: fail('Benchmark requires at least 30 seconds')
    if args.caster_mode != 'baseline' and not args.caster_metadata: fail('Caster mode needs exported registry metadata')
    name=safe(args.name); target=WORK/'runs'/name
    if target.exists(): fail('Fresh run directory already exists: '+str(target))
    instance=args.instance.resolve(); fixture=args.fixture.resolve()
    if not (fixture/'level.dat').is_file(): fail('Fixture world missing: '+str(fixture))
    pack=(args.pack.resolve() if args.pack else active_pack(instance).resolve())
    if pack.parent != (instance/'shaderpacks').resolve(): fail('--pack must be a file in this Prism instance shaderpacks directory')
    if not pack.is_file() or pack.suffix.lower()!='.zip': fail('Selected shader ZIP missing')
    settings=(args.settings.resolve() if args.settings else Path(str(pack)+'.txt'))
    if not settings.is_file(): fail('Matching shader settings are missing')
    if args.settings and settings.parent != (instance/'shaderpacks').resolve(): fail('--settings must be in this Prism instance shaderpacks directory')
    target.mkdir(parents=True); target=(target.resolve())
    # Copy, never hardlink. Keep the fixture save immutable.
    shutil.copy2(instance/'options.txt',target/'options.txt'); edit_options(target/'options.txt',args.width,args.height,args.render_distance,args.max_fps)
    if scene: write(target/'scene-config.json',scene)
    for folder in ('mods','config','defaultconfigs','terrain-diffusion-models','purescale_natives'):
        source=instance/folder
        if source.exists(): shutil.copytree(source,target/folder)
    if args.purescale_off:
        config=target/'config/purescale-modern.json'
        values=json.loads(config.read_text(encoding='utf-8'))
        if not isinstance(values.get('enabled'),bool): fail('Unexpected PureScale enabled setting')
        values['enabled']=False
        write(config,values)
    (target/'resourcepacks').mkdir()
    absent=[]
    for name in selected_packs(instance):
        source=instance/'resourcepacks'/name
        if not source.exists(): absent.append(name); continue
        if source.is_dir(): shutil.copytree(source,target/'resourcepacks'/name)
        else: shutil.copy2(source,target/'resourcepacks'/name)
    (target/'shaderpacks').mkdir(); shutil.copy2(pack,target/'shaderpacks'/pack.name); shutil.copy2(settings,target/'shaderpacks'/(pack.name+'.txt'))
    staged_pack=target/'shaderpacks'/pack.name
    caster_report=None
    caster_verification={'required':bool(args.export_registry),'mode':'baseline',
                         'metadata_path':'caster-registry.json','metadata_sha256':None,
                         'report_path':'caster-verification.json'}
    if args.caster_mode != 'baseline':
        from tools.photon_waving.build_shadow_casters import build as build_casters
        metadata=json.loads(args.caster_metadata.read_text(encoding='utf-8'))
        if metadata.get('source_shader_sha256') != sha(pack): fail('Caster registry source shader does not match')
        staged_metadata=target/'caster-source-metadata.json'
        shutil.copy2(args.caster_metadata,staged_metadata)
        metadata_sha256=sha(staged_metadata)
        caster_verification={'required':True,'mode':args.caster_mode,
                             'metadata_path':staged_metadata.name,'metadata_sha256':metadata_sha256,
                             'report_path':'caster-verification.json'}
        caster_report=build_casters(pack,staged_metadata,staged_pack,args.caster_mode)
        write(target/'caster-build.json',caster_report)
    seed_edits={}
    if args.freeze_jitter or args.freeze_sampler or args.freeze_stochastic or args.freeze_time_motion or args.freeze_stochastic_group:
        temporary=staged_pack.with_suffix('.diagnostic-tmp')
        patched=set()
        with zipfile.ZipFile(staged_pack) as source, zipfile.ZipFile(temporary,'w') as output:
            for info in source.infolist():
                data=source.read(info.filename)
                if args.freeze_jitter and info.filename=='shaders/shaders.properties':
                    text=data.decode('utf-8')
                    for multiplier in ('1.3247179572','1.7548776662'):
                        old=f'{multiplier} * frameCounter + 0.5'
                        if text.count(old)!=1: fail('Unexpected jitter expression')
                        text=text.replace(old,f'{multiplier} * 0 + 0.5')
                    data=text.encode('utf-8')
                    patched.add('jitter')
                for sampler in set(args.freeze_sampler):
                    member,old,new=SAMPLER_FREEZES[sampler]
                    if info.filename==member:
                        text=data.decode('utf-8')
                        if text.count(old)!=1: fail('Unexpected sampler expression: '+sampler)
                        data=text.replace(old,new).encode('utf-8')
                        patched.add(sampler)
                freeze_seed=(args.freeze_stochastic or any(info.filename in STOCHASTIC_GROUPS[group] for group in args.freeze_stochastic_group))
                if info.filename.endswith(('.glsl','.fsh','.vsh')) and (freeze_seed or args.freeze_time_motion):
                    lines=data.decode('utf-8').splitlines(keepends=True)
                    edits=0
                    for index,line in enumerate(lines):
                        if 'uniform ' in line: continue
                        if freeze_seed and 'frameCounter' in line:
                            # These are convergence schedules, not sample randomness.
                            scheduled=('clouds_checkerboard_offsets[frameCounter % checkerboard_area]' in line
                                       or info.filename=='shaders/include/lighting/lpv/blocklight.glsl')
                            if not scheduled:
                                line,count=re.subn(r'\bframeCounter\b','0',line); edits+=count
                        if args.freeze_time_motion:
                            line,count=re.subn(r'\bframeTimeCounter\b','0.0',line); edits+=count
                        lines[index]=line
                    if edits:
                        seed_edits[info.filename]=edits
                        data=''.join(lines).encode('utf-8')
                output.writestr(copy.copy(info),data)
        if patched != (set(args.freeze_sampler) | ({'jitter'} if args.freeze_jitter else set())):
            fail('A requested diagnostic shader member was missing')
        temporary.replace(staged_pack)
    staged_settings=target/'shaderpacks'/(pack.name+'.txt')
    if args.no_wind:
        lines=staged_settings.read_text(encoding='utf-8').splitlines()
        lines=[line for line in lines if not line.startswith(('WAVING_PLANTS=', 'WAVING_LEAVES='))]
        staged_settings.write_text('\n'.join(lines+['WAVING_PLANTS=false','WAVING_LEAVES=false'])+'\n',encoding='utf-8')
    overrides={}
    if args.shader_option:
        with zipfile.ZipFile(pack) as z:
            definitions=z.read('shaders/settings.glsl').decode('utf-8-sig')
        for entry in args.shader_option:
            key,value=entry.split('=',1)
            if not key.replace('_','').isalnum() or '\n' in value: fail('Invalid shader override')
            apply_setting(definitions,key,value)  # Require a real, unique setting in this pack.
            overrides[key]=value
        lines=staged_settings.read_text(encoding='utf-8').splitlines()
        lines=[line for line in lines if line.split('=',1)[0] not in overrides]
        staged_settings.write_text('\n'.join(lines+[key+'='+value for key,value in overrides.items()])+'\n',encoding='utf-8')
    iris=target/'config/iris.properties'; text=iris.read_text(encoding='utf-8')
    if text.count('shaderPack=')!=1: fail('Unexpected Iris config shader selection')
    iris.write_text('\n'.join((f'shaderPack={pack.name}' if line.startswith('shaderPack=') else line) for line in text.splitlines())+'\n',encoding='utf-8')
    # Old Voxy caches contain registry IDs removed from the current mod set.
    # Preserve saved Minecraft chunks; rebuild only derived renderer data in the clone.
    shutil.copytree(fixture,target/'saves/photon-waving-scene',
                    ignore=lambda directory,names: {'session.lock','voxy'} if Path(directory)==fixture else {'session.lock'})
    probe_reused_from=None
    if args.probe_from:
        source=(WORK/'runs'/safe(args.probe_from)).resolve()
        if source.parent != (WORK/'runs').resolve() or source == target: fail('Unsafe probe source run')
        source_receipt=source/'prepare-receipt.json'
        if not source_receipt.is_file(): fail('Probe source run was not prepared')
        source_inputs=json.loads(source_receipt.read_text(encoding='utf-8'))
        source_name=Path(source_inputs.get('probe_jar',''))
        if source_name.name != str(source_name) or source_name.suffix != '.jar': fail('Unsafe source probe JAR name')
        source_jar=(source/'mods'/source_name).resolve()
        if source_jar.parent != (source/'mods').resolve() or not source_jar.is_file(): fail('Probe source JAR missing')
        if sha(source_jar) != source_inputs.get('probe_sha256'): fail('Probe source JAR differs from its receipt')
        jar=source_jar
        probe_reused_from={'run':source.name,'probe_sha256':sha(jar)}
    else:
        jar=build()
    shutil.copy2(jar,target/'mods'/jar.name)
    entries={str(p.relative_to(target)):sha(p) for p in target.rglob('*') if p.is_file()}
    write(target/'prepare-receipt.json',{'prepared_utc':datetime.now(timezone.utc).isoformat(),'instance':str(instance),'fixture':str(fixture),'shader_zip':pack.name,'shader_sha256':sha(pack),'settings_sha256':sha(staged_settings),'source_settings_sha256':sha(settings),'no_wind_control':args.no_wind,'voxy_cache':'fresh; legacy fixture cache excluded','probe_jar':jar.name,'probe_sha256':sha(jar),'clone_only_option_edits':['fullscreen','overrideWidth','overrideHeight','renderDistance','simulationDistance','enableVsync','maxFps','pauseOnLostFocus'],'missing_selected_resourcepacks':sorted(absent),'files_sha256':entries})
    if overrides:
        receipt=json.loads((target/'prepare-receipt.json').read_text(encoding='utf-8'))
        receipt['shader_option_overrides']=overrides
        write(target/'prepare-receipt.json',receipt)
    if args.purescale_off:
        receipt=json.loads((target/'prepare-receipt.json').read_text(encoding='utf-8'))
        receipt['config_overrides']={'config/purescale-modern.json':{'enabled':False}}
        write(target/'prepare-receipt.json',receipt)
    receipt=json.loads((target/'prepare-receipt.json').read_text(encoding='utf-8'))
    receipt['width']=args.width; receipt['height']=args.height
    receipt['source_shader_sha256']=sha(pack)
    receipt['shader_sha256']=sha(staged_pack)
    receipt['diagnostic_instrumentation']='fixed projection jitter only' if args.freeze_jitter else None
    if args.freeze_sampler:
        receipt['diagnostic_instrumentation']={'fixed_jitter':args.freeze_jitter,'fixed_sampler_seeds':sorted(set(args.freeze_sampler))}
    if args.freeze_stochastic or args.freeze_time_motion or args.freeze_stochastic_group:
        receipt['diagnostic_instrumentation']={'fixed_jitter':args.freeze_jitter,'fixed_stochastic_seeds':args.freeze_stochastic,'fixed_groups':args.freeze_stochastic_group,'fixed_time_motion':args.freeze_time_motion,'expression_edits':seed_edits}
    receipt['require_dlss_bridge']=(not args.purescale_off and (target/'purescale_natives/purescale_dlss.dll').is_file())
    receipt['benchmark_seconds']=args.benchmark_seconds
    receipt['render_distance']=args.render_distance
    receipt['max_fps']=args.max_fps
    if scene: receipt['scene']=scene
    receipt['export_registry']=args.export_registry
    receipt['caster_mode']=args.caster_mode
    receipt['caster_verification']=caster_verification
    if probe_reused_from: receipt['probe_reused_from']=probe_reused_from
    if caster_report: receipt['caster_build']=caster_report
    write(target/'prepare-receipt.json',receipt)
    print(json.dumps({'prepared':str(target),'shader':pack.name,'shader_sha256':sha(staged_pack),'probe':jar.name}))
def running_java():
    try:
        import psutil
        active=[]
        for process in psutil.process_iter(['name']):
            if (process.info['name'] or '').lower() not in ('java.exe','javaw.exe'): continue
            try:
                command=process.cmdline()
                if len(command)==2 and command[1] in ('-version','--version'): continue
            except (psutil.AccessDenied,psutil.NoSuchProcess): pass
            active.append(process.pid)
        return active
    except ImportError: return []

def refresh_probe(args):
    target=(WORK/'runs'/safe(args.name)).resolve()
    if target.parent != (WORK/'runs').resolve(): fail('Unsafe run path')
    if any((target/name).exists() for name in ('logs','process.json','launch-receipt.json','receipt.json')):
        fail('Probe refresh requires an unrun fixture')
    ready=target/'prepare-receipt.json'
    inputs=json.loads(ready.read_text(encoding='utf-8'))
    if 'caster_verification' not in inputs and inputs.get('export_registry'):
        if inputs.get('caster_mode','baseline') != 'baseline':
            fail('Legacy caster variant lacks frozen verification metadata; prepare a fresh fixture')
        inputs['caster_mode']='baseline'
        inputs['caster_verification']={'required':True,'mode':'baseline',
                                       'metadata_path':'caster-registry.json','metadata_sha256':None,
                                       'report_path':'caster-verification.json'}
    staged=target/'mods'/inputs['probe_jar']
    if sha(staged)!=inputs['probe_sha256']: fail('Staged probe differs from receipt')
    old_hash=inputs['probe_sha256']
    updated=build()
    shutil.copyfile(updated,staged)
    inputs['probe_sha256']=sha(staged)
    inputs['files_sha256'][str(staged.relative_to(target))]=sha(staged)
    if args.width or args.height:
        inputs['width']=args.width or inputs['width']
        inputs['height']=args.height or inputs['height']
        edit_options(target/'options.txt',inputs['width'],inputs['height'],inputs.get('render_distance',8),inputs.get('max_fps',120))
        inputs['files_sha256']['options.txt']=sha(target/'options.txt')
    inputs.setdefault('probe_refresh_history',[]).append({'previous_sha256':old_hash,'new_sha256':sha(staged),'reason':args.reason})
    write(ready,inputs)
    print(json.dumps({'refreshed':str(target),'probe_sha256':sha(staged)}))
def run(args):
    target=(WORK/'runs'/safe(args.name)).resolve()
    if (target/'archived-evidence.json').exists(): fail('Archived evidence is not a writable game fixture; prepare a fresh run')
    ready=target/'prepare-receipt.json'
    if not ready.is_file(): fail('Run must be prepared first')
    if (target/'launch-receipt.json').exists(): fail('Run was already launched; create a fresh name')
    active=running_java()
    if active: fail('A Java process is active; inspect it before launching: '+str(active))
    inputs=json.loads(ready.read_text(encoding='utf-8'))
    jar=target/'mods'/inputs['probe_jar']
    if not jar.is_file() or sha(jar)!=inputs['probe_sha256']: fail('Probe JAR changed after preparation')
    pack=target/'shaderpacks'/inputs['shader_zip']
    if not pack.is_file() or sha(pack)!=inputs['shader_sha256']: fail('Staged shader changed after preparation')
    settings=target/'shaderpacks'/(inputs['shader_zip']+'.txt')
    if not settings.is_file() or sha(settings)!=inputs['settings_sha256']: fail('Staged shader settings changed after preparation')
    caster_verification=inputs.get('caster_verification',{})
    caster_metadata=None
    if caster_verification.get('required'):
        metadata_relative=Path(caster_verification.get('metadata_path',''))
        report_relative=Path(caster_verification.get('report_path',''))
        if metadata_relative.is_absolute() or report_relative.is_absolute() or '..' in metadata_relative.parts or '..' in report_relative.parts:
            fail('Unsafe caster verification receipt paths')
        caster_metadata=(target/metadata_relative).resolve()
        if caster_metadata.parent != target and target not in caster_metadata.parents: fail('Caster metadata escapes run directory')
        if caster_verification.get('mode') != 'baseline':
            expected_hash=caster_verification.get('metadata_sha256')
            if not expected_hash or not caster_metadata.is_file() or sha(caster_metadata)!=expected_hash:
                fail('Frozen caster metadata changed after preparation')
    runtime=argparse.Namespace(width=inputs.get('width',1280),height=inputs.get('height',720),timeout=args.timeout,libraries=launch.DEFAULT_LIBRARIES,assets=launch.DEFAULT_ASSETS,java=launch.DEFAULT_JAVA,collector='zgc',heap_min_mib=args.heap_mib,heap_max_mib=args.heap_mib,property=[f'photonwaving.world=photon-waving-scene',f'photonwaving.output={target/"receipt.json"}'])
    runtime.property.extend([f'photonwaving.width={runtime.width}',f'photonwaving.height={runtime.height}'])
    if inputs.get('scene'):
        runtime.property.append('photonwaving.scene=existing')
        runtime.property.extend(f'photonwaving.{key}={value}' for key,value in inputs['scene']['camera'].items())
    if inputs.get('benchmark_seconds'):
        runtime.property.extend(['photonwaving.benchmark=true',f'photonwaving.benchmarkSeconds={inputs["benchmark_seconds"]}'])
    if inputs.get('export_registry'): runtime.property.append('photonwaving.exportRegistry=true')
    if caster_verification.get('required'):
        runtime.property.extend(['photonwaving.verifyCaster=true',
                                 f'photonwaving.casterMode={caster_verification["mode"]}',
                                 f'photonwaving.casterMetadata={caster_metadata}'])
    _,native_jars,_=launch.production_libraries(runtime.libraries); natives=target/'natives'; launch.extract_natives(native_jars,natives)
    command=launch.make_command(runtime,target,natives); logs=target/'logs'; logs.mkdir(); started=time.monotonic()
    started_utc=datetime.now(timezone.utc).isoformat()
    crash_files=[]; timed_out=False; fatal_mod_state=False
    with (logs/'launcher.log').open('w',encoding='utf-8') as out:
        process=subprocess.Popen(command,cwd=target,stdout=out,stderr=subprocess.STDOUT,creationflags=FLAGS)
        write(target/'process.json',{'pid':process.pid})
        while process.poll() is None:
            crash_files=list((target/'crash-reports').glob('*.txt'))
            timed_out=time.monotonic()-started >= args.timeout
            latest=logs/'latest.log'
            if latest.exists():
                with latest.open('rb') as stream:
                    stream.seek(max(0,latest.stat().st_size-512*1024))
                    tail=stream.read()
                fatal_mod_state=(b'Failed to initialize mod containers' in tail
                                 or b'to a broken mod state' in tail)
            if crash_files or timed_out or fatal_mod_state:
                # Stop only the child created by this runner, including recoverable crash screens.
                process.terminate()
                break
            time.sleep(2)
        try: exit_code=process.wait(timeout=15)
        except subprocess.TimeoutExpired: process.kill(); exit_code=process.wait()
    receipt={'started_utc':started_utc,'elapsed_seconds':time.monotonic()-started,'exit_code':exit_code,'timed_out':timed_out,'fatal_mod_state':fatal_mod_state,'crash_reports':[str(p.relative_to(target)) for p in crash_files],'command':command,'shader_sha256':sha(pack)}
    write(target/'launch-receipt.json',receipt)
    probe=target/'receipt.json'
    if timed_out or exit_code!=0 or not probe.is_file(): raise SystemExit(1)
    detail=json.loads(probe.read_text(encoding='utf-8'))
    if inputs.get('require_dlss_bridge'):
        log=(logs/'latest.log').read_text(encoding='utf-8',errors='replace')
        if 'OpenGL D3D12 SR/FG bridge initialized' not in log or 'DLSS native bridge not installed' in log:
            fail('DLSS bridge did not initialize; this run is not equivalent to the installed runtime')
    expected=[target/'screenshots'/f'photon-waving-{i}.png' for i in range(3)]
    captures=detail.get('captures',[])
    expected_camera=inputs.get('scene',{}).get('camera',{'x':40.5,'y':166.0,'z':-572.5,'yaw':-90.0,'pitch':7.0})
    if inputs.get('scene'):
        if detail.get('scene_kind')!='existing' or detail.get('render_distance_chunks')!=inputs['render_distance']:
            fail('Existing-world scene or render distance was not applied')
    valid_captures=(len(captures)==3 and all(c.get('day_time')==6000 for c in captures)
                    and all(abs(c.get('camera',{}).get(key, float('nan'))-value)<0.01
                            for c in captures for key,value in expected_camera.items())
                    and all(captures[i]['elapsed_ms']-captures[i-1]['elapsed_ms']>=6000 for i in range(1,3)))
    if not detail.get('success') or not valid_captures or any(not p.is_file() for p in expected): raise SystemExit(1)
    if detail.get('window')!={'width':runtime.width,'height':runtime.height}: fail('Actual resolution differs from requested diagnostic resolution')
    if inputs.get('export_registry'):
        registry=target/'caster-registry.json'
        metadata=json.loads(registry.read_text(encoding='utf-8'))
        metadata['source_shader_sha256']=inputs.get('source_shader_sha256',inputs['shader_sha256'])
        metadata['source_probe_sha256']=inputs['probe_sha256']
        write(registry,metadata)
    if caster_verification.get('required'):
        report=target/caster_verification['report_path']
        if not report.is_file(): fail('Caster verification report is missing')
        verified=json.loads(report.read_text(encoding='utf-8'))
        expected_mode=caster_verification['mode']
        if (verified.get('mode') != expected_mode or verified.get('mismatches') != 0
                or not isinstance(verified.get('checkedStates'),int) or verified['checkedStates'] <= 0
                or not isinstance(verified.get('expectedStates'),int) or verified['expectedStates'] <= 0):
            fail('Caster verification report is incomplete or mismatched')
        if Path(verified.get('metadata','')).resolve() != caster_metadata:
            fail('Caster verification report used unexpected metadata')
        metadata_hash=sha(caster_metadata)
        expected_hash=caster_verification.get('metadata_sha256')
        if expected_hash and metadata_hash != expected_hash: fail('Frozen caster metadata changed during launch')
        if not detail.get('caster_verification_requested') or not detail.get('caster_verification_passed'):
            fail('Probe receipt did not confirm caster verification')
        receipt['caster_verification']={'mode':expected_mode,'metadata_path':caster_verification['metadata_path'],
                                        'metadata_sha256':metadata_hash,'report_path':caster_verification['report_path'],
                                        'checked_states':verified['checkedStates'],'expected_states':verified['expectedStates'],
                                        'mismatches':verified['mismatches']}
        write(target/'launch-receipt.json',receipt)
    if inputs.get('benchmark_seconds'):
        timing=json.loads((target/'timing/shadow-gpu-timing.json').read_text(encoding='utf-8'))
        if not timing.get('gl_timestamp_supported') or timing.get('query_failures'):
            fail('GPU timer unavailable or query failures occurred')
        if not detail.get('benchmark_glfw_focused_at_start'):
            fail('Benchmark did not begin with a GLFW-focused game window')
        if not isinstance(timing.get('glfw_focused_frame_count'),int) or timing['glfw_focused_frame_count'] <= 0:
            fail('GPU timer recorded no GLFW-focused measurement frames')
        if timing.get('glfw_unfocused_frame_count') != 0:
            fail('GPU timer recorded GLFW-unfocused measurement frames')
        for key in ('frame_interval','shadow_pass'):
            if timing[key]['completed']<100 or timing[key]['unbalanced'] or timing[key]['dropped']:
                fail('GPU timing incomplete: '+key)
    print(json.dumps({'success':True,'run':str(target),'screenshots':[str(p) for p in expected]}))
def main():
    parser=argparse.ArgumentParser(description=__doc__); sub=parser.add_subparsers(dest='action',required=True)
    prep=sub.add_parser('prepare'); prep.add_argument('name'); prep.add_argument('--instance',type=Path,default=current_instance()); prep.add_argument('--fixture',type=Path,default=FIXTURE); prep.add_argument('--pack',type=Path); prep.add_argument('--settings',type=Path); prep.add_argument('--no-wind',action='store_true',help='Clone-only control: disable all plant and leaf waving'); prep.set_defaults(func=prepare)
    prep.add_argument('--shader-option',action='append',default=[],metavar='NAME=VALUE',help='Clone-only shader setting override; repeat for multiple options')
    prep.add_argument('--purescale-off',action='store_true',help='Clone-only control: disable the installed PureScale Modern upscaler')
    prep.add_argument('--freeze-jitter',action='store_true',help='Clone-only diagnostic: fix the two Photon projection jitter expressions')
    prep.add_argument('--freeze-sampler',action='append',choices=sorted(SAMPLER_FREEZES),default=[],help='Clone-only diagnostic: fix one sampling seed without disabling its effect')
    prep.add_argument('--freeze-stochastic',action='store_true',help='Fix sample randomness while preserving cloud checkerboard and LPV update schedules')
    prep.add_argument('--freeze-stochastic-group',action='append',choices=sorted(STOCHASTIC_GROUPS),default=[])
    prep.add_argument('--freeze-time-motion',action='store_true',help='Fix frameTimeCounter-based visual motion in diagnostic shader code')
    prep.add_argument('--benchmark-seconds',type=int,default=0)
    prep.add_argument('--export-registry',action='store_true')
    prep.add_argument('--caster-mode',choices=['baseline','cube','cube-stairs-slabs'],default='baseline')
    prep.add_argument('--caster-metadata',type=Path)
    prep.add_argument('--probe-from',help='Copy a hash-verified probe JAR from another prepared run')
    prep.add_argument('--width',type=int,default=1280)
    prep.add_argument('--height',type=int,default=720)
    prep.add_argument('--scene-config',type=Path,help='Existing-world camera and provenance JSON; skips all synthetic block placement')
    prep.add_argument('--render-distance',type=int,default=8)
    prep.add_argument('--max-fps',type=int,default=120)
    runp=sub.add_parser('run'); runp.add_argument('name'); runp.add_argument('--timeout',type=int,default=360); runp.add_argument('--heap-mib',type=int,default=12000); runp.set_defaults(func=run)
    refresh=sub.add_parser('refresh-probe'); refresh.add_argument('name'); refresh.add_argument('--reason',required=True); refresh.add_argument('--width',type=int); refresh.add_argument('--height',type=int); refresh.set_defaults(func=refresh_probe)
    args=parser.parse_args()
    try: args.func(args)
    except RuntimeError as error: print('error:',error,file=sys.stderr); raise SystemExit(2)
if __name__=='__main__': main()
