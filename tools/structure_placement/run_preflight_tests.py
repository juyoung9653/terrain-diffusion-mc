"""Regression using the inspected Streams Reflowing JAR supplied as first argument."""
from pathlib import Path
import os, subprocess, sys, tomllib, zipfile
root=Path(__file__).resolve().parents[2]
java=Path(os.environ.get('JAVA_HOME','C:/Program Files/Java/jdk-21'))/'bin'
jars=[Path(value).resolve() for value in sys.argv[1:]]
assert jars and all(jar.is_file() for jar in jars)
out=root/'build/structure-placement/preflight-tests'
out.mkdir(parents=True,exist_ok=True)
source=root/'common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure'
subprocess.run([str(java/'javac'),'-encoding','UTF-8','-d',str(out),str(source/'PlacementGeometry.java'),str(source/'StructurePreflightControl.java'),str(Path(__file__).with_name('PreflightControlTest.java'))],
               check=True, creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
SIGNAL_BY_VERSION={
    # These class names are deliberately version-specific. Newer Streams
    # releases reuse obfuscated nested names for ordinary records.
    '2.17.1':'NoDecompilePermissionEver_002',
    '2.17.3':'SourceGivenToAuthorizedOnly_002',
}
LEGACY_SIGNALS=('StructurePlatesPending','SeeAiNoticeConstant_002')
LEGACY_VERSIONS={'2.13.1','2.13.5','2.13.8','2.15.2','2.16.2'}

def streams_version(archive):
    metadata=tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode('utf-8-sig'))
    versions=[str(mod.get('version')) for mod in metadata.get('mods', [])
              if mod.get('modId') == 'streamsreflowing']
    assert len(versions)==1, 'expected exactly one streamsreflowing mod metadata entry'
    return versions[0]

for jar in jars:
    with zipfile.ZipFile(jar) as archive:
        version=streams_version(archive)
        signal=SIGNAL_BY_VERSION.get(version)
        if signal is None and version in LEGACY_VERSIONS:
            # Legacy releases keep descriptive class names; only they may use
            # this fallback because the newer obfuscated names are reused.
            signal=next((name for name in LEGACY_SIGNALS
                         if f'dev/streamsreflowing/worldgen/StreamsWorldgen${name}.class'
                         in archive.namelist()), None)
        assert signal, f'No audited plate-readiness signal for Streams {version} in {jar.name}'
        path=f'dev/streamsreflowing/worldgen/StreamsWorldgen${signal}.class'
        assert path in archive.namelist(), f'Expected {signal} for Streams {version}, missing from {jar.name}'
    result=subprocess.run([str(java/'java'),'-cp',os.pathsep.join([str(out),str(jar)]),
                    'PreflightControlTest',f'dev.streamsreflowing.worldgen.StreamsWorldgen${signal}',
                    ],capture_output=True,text=True,
                   creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    print(result.stdout,end='')
    if result.stderr: print(result.stderr,file=sys.stderr,end='')
    result.check_returncode()
