param([string]$JavaHome = 'C:\Program Files\Java\jdk-21')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$out = Join-Path $root 'build\river-planner-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& (Join-Path $JavaHome 'bin\javac.exe') --release 21 -d $out `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\world\river\DrainagePlanner.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\world\river\RiverGeometry.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\world\river\RiverPreviewCache.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\world\river\NaturalRiverBankShape.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\world\river\NaturalRiverBankMargin.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\world\river\NaturalRiverBankSurface.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\world\ecology\FeatureEcologyMath.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\pipeline\EcologyClassifier.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\pipeline\EcologyShowcaseProfile.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\pipeline\ShoreClassifier.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\pipeline\TerralithBiomeClassifier.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\pipeline\FastNoiseLite.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\world\river\NaturalRiverProfile.java') `
  (Join-Path $root 'neoforge\src\main\java\com\github\xandergos\terraindiffusionmc\compat\streams\StreamsNaturalConfig.java') `
  (Join-Path $root 'neoforge\src\main\java\com\github\xandergos\terraindiffusionmc\compat\streams\StreamsRecordAccess.java') `
  (Join-Path $root 'neoforge\src\main\java\com\github\xandergos\terraindiffusionmc\compat\streams\StreamsBankLakeMask.java') `
  (Join-Path $root 'neoforge\src\main\java\com\github\xandergos\terraindiffusionmc\compat\terrainslabs\TerrainSlabRiverPolicy.java') `
  (Join-Path $root 'neoforge\src\main\java\com\github\xandergos\terraindiffusionmc\compat\terrainslabs\TerrainSlabSurfacePolicy.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\world\river\NaturalRiverMorphology.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\world\river\NaturalRiverShallowProfile.java') `
  (Join-Path $PSScriptRoot 'DrainagePlannerTest.java') `
  (Join-Path $PSScriptRoot 'RiverGeometryTest.java') `
  (Join-Path $PSScriptRoot 'RiverPreviewCacheTest.java') `
  (Join-Path $PSScriptRoot 'NaturalRiverBankShapeTest.java') `
  (Join-Path $PSScriptRoot 'NaturalRiverBankMarginTest.java') `
  (Join-Path $PSScriptRoot 'NaturalRiverBankSurfaceTest.java') `
  (Join-Path $PSScriptRoot 'StreamsBankLakeMaskTest.java') `
  (Join-Path $PSScriptRoot 'StreamsNaturalConfigTest.java') `
  (Join-Path $PSScriptRoot 'NaturalLakeShoreTest.java') `
  (Join-Path $PSScriptRoot 'TerrainSlabRiverPolicyTest.java') `
  (Join-Path $PSScriptRoot 'TerrainSlabSurfacePolicyTest.java') `
  (Join-Path $root 'common\src\main\java\com\github\xandergos\terraindiffusionmc\world\river\NaturalRiverConfluenceWidths.java') `
  (Join-Path $PSScriptRoot 'NaturalRiverConfluenceWidthsTest.java') `
  (Join-Path $PSScriptRoot 'NaturalRiverShallowProfileTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
foreach ($testName in @('DrainagePlannerTest', 'RiverGeometryTest', 'RiverPreviewCacheTest', 'NaturalRiverBankShapeTest', 'NaturalRiverBankMarginTest', 'com.github.xandergos.terraindiffusionmc.compat.streams.StreamsBankLakeMaskTest', 'StreamsNaturalConfigTest', 'com.github.xandergos.terraindiffusionmc.compat.streams.NaturalLakeShoreTest', 'TerrainSlabRiverPolicyTest', 'NaturalRiverShallowProfileTest', 'NaturalRiverConfluenceWidthsTest')) {
  & (Join-Path $JavaHome 'bin\java.exe') -ea -cp "$out;$root\common\src\main\resources" $testName
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
& (Join-Path $JavaHome 'bin\java.exe') -ea -cp "$out;$root\common\src\main\resources" NaturalRiverBankSurfaceTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $JavaHome 'bin\java.exe') -ea -cp "$out;$root\common\src\main\resources" TerrainSlabSurfacePolicyTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
exit 0
