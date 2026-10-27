"""Build the temporary C2ME read counter without Gradle or production outputs."""
import shutil, subprocess, sys, time, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parents[1] / "optimization_bench"))
import launch  # noqa: E402

classes = ROOT / ".classes"
shutil.rmtree(classes, ignore_errors=True)
classes.mkdir()
sources = [*map(str, ROOT.rglob("*.java"))]
command = [str(launch.DEFAULT_JAVA.with_name("javac.exe")), "-encoding", "UTF-8", "--release", "21", "-proc:none",
           "-cp", launch.compile_classpath(launch.DEFAULT_LIBRARIES), "-d", str(classes), *sources]
run = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
if run.returncode:
    print(run.stdout); raise SystemExit(run.returncode)
out = ROOT / "td-chunk-read-probe-1.0.0.jar"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as jar:
    for base in (classes, ROOT / "resources"):
        for file in base.rglob("*"):
            if file.is_file(): jar.write(file, file.relative_to(base).as_posix())
print(out)
