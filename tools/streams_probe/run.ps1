param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Name,
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21',
    [string]$GradleUserHome = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle'),
    [ValidateRange(1, 16)][int]$MaxChunks = 4,
    [ValidateRange(1, 128)][int]$RouteSearchRadius = 32,
    [int]$CenterChunkX,
    [int]$CenterChunkZ,
    [ValidatePattern('^-?\d+,-?\d+(;-?\d+,-?\d+)*$')][string]$Chunks,
    [switch]$Offline,
    [switch]$ShieldAudit,
    [switch]$RegistryOnly,
    [switch]$NonTdShieldCheck
)
$ErrorActionPreference = 'Stop'
$probeRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$probeRun = Join-Path $probeRepo "build\streams-probe\$Name"
if (-not (Test-Path -LiteralPath (Join-Path $probeRun 'input-manifest.json'))) {
    throw 'Use prepare.py first to create a fresh isolated run.'
}
$env:JAVA_HOME = $JavaHome
$env:GRADLE_USER_HOME = $GradleUserHome
$probeArguments = @('-I', 'tools/streams_probe/probe.init.gradle', ':neoforge:runServer',
    '-PuseCuda=true', '-Pneoforge_version=21.1.249', "-PtdProbeDir=$probeRun",
    "-PtdProbe.maxChunks=$MaxChunks", "-PtdProbe.routeSearchRadius=$RouteSearchRadius",
    '-PtdProbe.timeoutSeconds=900', '--console=plain')
if ($Offline) { $probeArguments += '--offline' }
if ($ShieldAudit) { $probeArguments += '-PtdProbe.shieldAudit=true' }
if ($RegistryOnly) { $probeArguments += '-PtdProbe.registryOnly=true' }
if ($NonTdShieldCheck) { $probeArguments += '-PtdProbe.registryOnly=true'; $probeArguments += '-PtdProbe.nonTdShieldCheck=true' }
if ($PSBoundParameters.ContainsKey('CenterChunkX')) { $probeArguments += "-PtdProbe.centerChunkX=$CenterChunkX" }
if ($PSBoundParameters.ContainsKey('CenterChunkZ')) { $probeArguments += "-PtdProbe.centerChunkZ=$CenterChunkZ" }
if ($Chunks) { $probeArguments += "-PtdProbe.chunks=$Chunks" }
Push-Location -LiteralPath $probeRepo
try {
    # Pass each -P option as one string: unquoted dotted versions can be split by PowerShell.
    # The launch is foreground/headless. Detailed output stays in the isolated run directory.
    $ErrorActionPreference = 'Continue'
    $probeStart = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    & (Join-Path $probeRepo 'gradlew.bat') @probeArguments > (Join-Path $probeRun 'build.log') 2>&1
    $probeExit = $LASTEXITCODE
    if ($probeExit -ne 0) {
        Get-Content -LiteralPath (Join-Path $probeRun 'build.log') -Tail 24
    } else {
        Select-String -LiteralPath (Join-Path $probeRun 'build.log') -Pattern '^BUILD SUCCESSFUL' | ForEach-Object { $_.Line }
    }
    & python (Join-Path $PSScriptRoot 'summarize.py') $probeRun --since $probeStart --compact
    exit $probeExit
} finally {
    Pop-Location
}
