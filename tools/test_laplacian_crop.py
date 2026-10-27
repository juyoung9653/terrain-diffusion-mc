"""Compile and raw-bit test the sparse Laplacian crop evaluator without Gradle or Minecraft."""
import argparse, os, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--benchmark', action='store_true')
    parser.add_argument('--java-home', type=Path, default=Path(os.environ.get('JAVA_HOME', r'C:\Program Files\Java\jdk-21'))); args=parser.parse_args()
    home=args.java_home
    if not (home/'bin'/'javac.exe').is_file(): home=Path(r'C:\Program Files\Android\Android Studio\jbr')
    out=ROOT/'build/laplacian-crop-tests'; out.mkdir(parents=True,exist_ok=True)
    javac=home/'bin'/('javac.exe' if os.name=='nt' else 'javac'); java=home/'bin'/('java.exe' if os.name=='nt' else 'java')
    sources=[ROOT/'common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/LaplacianUtils.java',ROOT/'common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/ExactLaplacianCrop.java',ROOT/'tools/terrain_sampling/ExactLaplacianCropTest.java']
    subprocess.run([str(javac),'--release','21','-d',str(out),*map(str,sources)],check=True)
    subprocess.run([str(java),'-ea','-cp',str(out),'ExactLaplacianCropTest', *(['--benchmark'] if args.benchmark else [])],check=True)
if __name__=='__main__': main()