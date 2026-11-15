"""Compile the narrow Lush Cave Ruins 1.3-LTS Gson fallback against its installed JSON."""
from pathlib import Path
import argparse, os, shutil, subprocess, zipfile
ROOT=Path(__file__).resolve().parents[2]; OUT=ROOT/"build/lush-cave-ruins-legacy-data-tests"; OUT.mkdir(parents=True,exist_ok=True)
caches=[ROOT/".gradle",ROOT/".gradle-user-home",Path.home()/".gradle"]
gson=next(p for c in caches for p in c.glob("caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar")); shutil.copyfile(gson,OUT/"gson.jar"); gson=OUT/"gson.jar"
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--jar",type=Path,help="optional Lush Cave Ruins JAR whose affected JSON is audited")
args=parser.parse_args()
fixture=OUT/"column_ruins_feature.json"; pot=OUT/"lush_cave_ruins_pot.json"; common=OUT/"lush_cave_ruins_common.json"; structure_set=OUT/"lush_ruins.json"
if args.jar:
    with zipfile.ZipFile(args.jar) as archive:
        fixture.write_bytes(archive.read("data/keeb_struc_lush/worldgen/configured_feature/column_ruins_feature.json"))
        pot.write_bytes(archive.read("data/keeb_struc_lush/loot_table/lush_cave_ruins_pot.json"))
        common.write_bytes(archive.read("data/keeb_struc_lush/loot_table/lush_cave_ruins_common.json"))
        structure_set.write_bytes(archive.read("data/keeb_struc_lush/worldgen/structure_set/lush_ruins.json"))
java=Path(os.environ.get("JAVA_HOME",r"C:\Program Files\Android\Android Studio\jbr"))/"bin"
source=ROOT/"common/src/main/java/com/github/xandergos/terraindiffusionmc/world/structure/LushCaveRuinsLegacyData.java"
harness=Path(__file__).with_name("LushCaveRuinsLegacyDataTest.java")
flags=getattr(subprocess,"CREATE_NO_WINDOW",0)
subprocess.run([str(java/"javac.exe"),"--release","21","-cp",str(gson),"-d",str(OUT),str(source),str(harness)],cwd=ROOT,check=True,creationflags=flags)
command=[str(java/"java.exe"),"-ea","-cp",os.pathsep.join((str(OUT),str(gson))),"LushCaveRuinsLegacyDataTest"]
if args.jar: command.extend((str(fixture),str(pot),str(common),str(structure_set)))
result=subprocess.run(command,cwd=ROOT,check=True,creationflags=flags,capture_output=True,text=True)
print(result.stdout.strip())
