$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$out = Join-Path $root 'build\streams-shield-tests'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$javac = 'C:\Program Files\Java\jdk-21\bin\javac.exe'
$java = 'C:\Program Files\Java\jdk-21\bin\java.exe'
& $javac -d $out (Join-Path $root 'neoforge\src\main\java\com\github\xandergos\terraindiffusionmc\compat\streams\StructureShieldGeometry.java') (Join-Path $PSScriptRoot 'StructureShieldGeometryTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp $out StructureShieldGeometryTest
exit $LASTEXITCODE
