param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21'
)

$ErrorActionPreference = 'Stop'
$sourceRoot = Join-Path $PSScriptRoot 'pipeline_models_lifecycle\src'
$temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\')
$outputRoot = Join-Path $temporaryRoot ('pipeline-models-lifecycle-' + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $outputRoot | Out-Null

try {
    $sources = @(
        (Join-Path $sourceRoot 'org\slf4j\Logger.java'),
        (Join-Path $sourceRoot 'org\slf4j\LoggerFactory.java'),
        (Join-Path $sourceRoot 'com\github\xandergos\terraindiffusionmc\pipeline\ModelAssetManager.java'),
        (Join-Path $sourceRoot 'com\github\xandergos\terraindiffusionmc\pipeline\OnnxModel.java'),
        (Join-Path $PSScriptRoot '..\common\src\main\java\com\github\xandergos\terraindiffusionmc\pipeline\PipelineModels.java'),
        (Join-Path $sourceRoot 'com\github\xandergos\terraindiffusionmc\pipeline\PipelineModelsLifecycleHarness.java')
    )
    & (Join-Path $JavaHome 'bin\javac.exe') -d $outputRoot @sources
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & (Join-Path $JavaHome 'bin\java.exe') -cp $outputRoot com.github.xandergos.terraindiffusionmc.pipeline.PipelineModelsLifecycleHarness
    exit $LASTEXITCODE
} finally {
    $resolvedOutput = (Resolve-Path -LiteralPath $outputRoot).Path
    if ([System.IO.Path]::GetDirectoryName($resolvedOutput) -ne $temporaryRoot -or
        [System.IO.Path]::GetFileName($resolvedOutput) -notlike 'pipeline-models-lifecycle-*') {
        throw 'Refusing to remove a directory outside the lifecycle test temporary scope.'
    }
    Remove-Item -LiteralPath $resolvedOutput -Recurse -Force -ErrorAction SilentlyContinue
}
