"""Regression using the inspected Streams Reflowing JAR supplied as first argument."""
from pathlib import Path
import os, subprocess, sys, zipfile
root=Path(__file__).resolve().parents[2]
java=Path(os.environ.get('JAVA_HOME','C:/Program Files/Java/jdk-21'))/'bin'
jars=[Path(value).resolve() for value in sys.argv[1:]]
assert jars and all(jar.is_file() for jar in jars)
out=root/'build/structure-placement/preflight-tests'
out.mkdir(parents=True,exist_ok=True)
source=root/'common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure'
subprocess.run([str(java/'javac'),'-encoding','UTF-8','-d',str(out),str(source/'PlacementGeometry.java'),str(source/'StructurePreflightControl.java'),str(Path(__file__).with_name('PreflightControlTest.java'))],check=True)
signals=['NoDecompilePermissionEver_002','StructurePlatesPending','SeeAiNoticeConstant_002']
for jar in jars:
    with zipfile.ZipFile(jar) as archive:
        present=[f'dev.streamsreflowing.worldgen.StreamsWorldgen${name}' for name in signals
                 if f'dev/streamsreflowing/worldgen/StreamsWorldgen${name}.class' in archive.namelist()]
    assert present, f'No audited plate-readiness signal in {jar.name}'
    # Obfuscated sibling names are reused for unrelated classes in newer releases.
    for signal in present[:1]:
        result=subprocess.run([str(java/'java'),'-cp',os.pathsep.join([str(out),str(jar)]),
                        'PreflightControlTest',signal],capture_output=True,text=True,
                       creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        print(result.stdout,end='')
        if result.stderr: print(result.stderr,file=sys.stderr,end='')
        result.check_returncode()
