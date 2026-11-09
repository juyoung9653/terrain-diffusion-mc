from pathlib import Path
import os, subprocess, sys
ROOT=Path(__file__).resolve().parents[2]; OUT=ROOT/'build/river-planner-tests/shortcuts'
SOURCES=[ROOT/'common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverMorphology.java',ROOT/'common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverPlanform.java',ROOT/'common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/ShortRiverTunnelPlanner.java',ROOT/'common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/ShortRiverShortcutPlanner.java',ROOT/'tools/river_planner/ShortRiverShortcutPlannerTest.java']
def main():
 home=Path(os.environ.get('JAVA_HOME',r'C:\Program Files\Java\jdk-21'))
 if not (home/'bin/javac.exe').is_file(): home=Path(r'C:\Program Files\Android\Android Studio\jbr')
 OUT.mkdir(parents=True,exist_ok=True); flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
 for cmd in ([str(home/'bin/javac'),'--release','21','-d',str(OUT),*map(str,SOURCES)],[str(home/'bin/java'),'-ea','-cp',str(OUT),'ShortRiverShortcutPlannerTest']):
  p=subprocess.run(cmd,text=True,capture_output=True,creationflags=flags); print(p.stdout,end=''); print(p.stderr,end='',file=sys.stderr); p.check_returncode()
if __name__=='__main__':main()
