"""Audit real vanilla moss processor resources and unsafe datapack mutations."""
from pathlib import Path
import os, shutil, subprocess, zipfile

root=Path(__file__).resolve().parents[2]
out=root/'build/moss-processor-tests'; out.mkdir(parents=True,exist_ok=True)
java=Path(os.environ.get('JAVA_HOME','C:/Program Files/Java/jdk-21'))/'bin'
caches=[root/'.gradle',root/'.gradle-user-home',Path.home()/'.gradle']
gson=next(p for c in caches for p in c.glob('caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar'))
shutil.copyfile(gson,out/'gson.jar'); gson=out/'gson.jar'
client=next(c/'caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar' for c in caches
            if (c/'caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar').exists())
fixtures=[]
with zipfile.ZipFile(client) as archive:
    for percent in [10,20,70]:
        path=out/f'mossify_{percent}.json'
        path.write_bytes(archive.read(f'data/minecraft/worldgen/processor_list/mossify_{percent}_percent.json'))
        fixtures.append(str(path))
source=root/'common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure/MossProcessorGeometry.java'
subprocess.run([str(java/'javac'),'-encoding','UTF-8','-cp',str(gson),'-d',str(out),str(source),
                str(Path(__file__).with_name('MossProcessorGeometryTest.java'))],check=True)
subprocess.run([str(java/'java'),'-cp',os.pathsep.join([str(out),str(gson)]),'MossProcessorGeometryTest',*fixtures],check=True)
