param([string]$JavaHome = 'C:\Program Files\Java\jdk-21')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$out = Join-Path $root 'build\river-planner-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& (Join-Path $JavaHome 'bin\javac.exe') --release 21 -d $out `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\world\river\DrainagePlanner.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\world\river\RiverGeometry.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\world\river\RiverPreviewCache.java') `
  (Join-Path $PSScriptRoot 'DrainagePlannerTest.java') `
  (Join-Path $PSScriptRoot 'RiverGeometryTest.java') `
  (Join-Path $PSScriptRoot 'RiverPreviewCacheTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
foreach ($testName in @('DrainagePlannerTest', 'RiverGeometryTest', 'RiverPreviewCacheTest')) {
  & (Join-Path $JavaHome 'bin\java.exe') -ea -cp $out $testName
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
exit 0
