"""Compile the isolated probe without touching the project's Gradle outputs."""
import subprocess
import time
import zipfile
from pathlib import Path

import launch

root = Path(__file__).resolve().parent
classes = launch.WORK / f'probe-classes-{time.time_ns()}'
classes.mkdir(parents=True)
command = [str(launch.DEFAULT_JAVA.with_name('javac.exe')), '-encoding', 'UTF-8',
           '--release', '21', '-proc:none', '-cp', launch.compile_classpath(launch.DEFAULT_LIBRARIES),
           '-d', str(classes), *map(str, (root / 'java').rglob('*.java'))]
result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        encoding='utf-8', errors='replace',
                        creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
if result.returncode:
    print(result.stdout)
    raise SystemExit(result.returncode)
output = launch.WORK / 'optimization-bench-1.0.0.jar'
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for base in (classes, root / 'resources'):
        for path in base.rglob('*'):
            if path.is_file():
                archive.write(path, path.relative_to(base).as_posix())
print(output)
