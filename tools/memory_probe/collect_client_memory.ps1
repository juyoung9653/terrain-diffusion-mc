[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int]$TargetPid,

    [ValidateRange(1, 120)]
    [int]$Samples = 3,

    [ValidateRange(1, 3600)]
    [int]$IntervalSeconds = 60,

    [ValidateRange(1, 300)]
    [int]$JcmdTimeoutSeconds = 15,

    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results'),

    [string]$JavaHome = 'C:\Program Files\Java\jdk-21',

    [switch]$ClassHistogram
)

$ErrorActionPreference = 'Stop'

function Get-UtcStamp {
    return [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
}

function Invoke-JcmdCapture {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory = $true)][string]$Path
    )

    $started = Get-UtcStamp
    $stdoutPath = "$Path.stdout.tmp"
    $stderrPath = "$Path.stderr.tmp"
    try {
        # Keep the jcmd command and every argument separate. None of the
        # collector's commands contain user-provided text or a command line.
        $jcmdArguments = @($TargetPid, $Command) + $Arguments
        $commandProcess = Start-Process -FilePath $jcmd -ArgumentList $jcmdArguments `
            -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -WindowStyle Hidden -PassThru
        if ($commandProcess.WaitForExit($JcmdTimeoutSeconds * 1000)) {
            $exitCode = $commandProcess.ExitCode
        } else {
            # The target JVM is never touched. Only this probe's jcmd child is stopped.
            Stop-Process -Id $commandProcess.Id -Force -ErrorAction SilentlyContinue
            $exitCode = 124
        }
        $output = @(
            if (Test-Path -LiteralPath $stdoutPath) { Get-Content -LiteralPath $stdoutPath -Raw }
            if (Test-Path -LiteralPath $stderrPath) { Get-Content -LiteralPath $stderrPath -Raw }
        ) -join [Environment]::NewLine
    } catch {
        $output = $_ | Out-String
        $exitCode = -1
    } finally {
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
    @(
        "# UTC: $started",
        "# jcmd: $Command $($Arguments -join ' ')",
        "# exit code: $exitCode",
        '',
        $output.TrimEnd()
    ) | Set-Content -LiteralPath $Path -Encoding utf8
    return $exitCode
}

$jcmd = Join-Path $JavaHome 'bin\jcmd.exe'
if (-not (Test-Path -LiteralPath $jcmd -PathType Leaf)) {
    throw "jcmd.exe was not found at '$jcmd'. Supply -JavaHome for a JDK that contains jcmd.exe."
}

$target = Get-Process -Id $TargetPid -ErrorAction Stop
if ($target.ProcessName -notin @('java', 'javaw')) {
    throw "PID $TargetPid is '$($target.ProcessName)', not java/javaw. Refusing to attach."
}
$targetStartTimeUtc = $target.StartTime.ToUniversalTime().ToString('o')

function Get-OriginalTargetProcess {
    try {
        $currentTarget = Get-Process -Id $TargetPid -ErrorAction Stop
        $currentTarget.Refresh()
    } catch {
        throw "The target process ended. Partial results remain in '$runDirectory'."
    }
    if ($currentTarget.ProcessName -ne $target.ProcessName -or
            $currentTarget.StartTime.ToUniversalTime().ToString('o') -ne $targetStartTimeUtc) {
        throw "PID $TargetPid was reused by a different process. Partial results remain in '$runDirectory'."
    }
    return $currentTarget
}

$runDirectory = Join-Path $OutputDirectory ('memory-probe-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-pid' + $TargetPid)
New-Item -ItemType Directory -Path $runDirectory -ErrorAction Stop | Out-Null

$manifest = [ordered]@{
    format = 1
    startedUtc = Get-UtcStamp
    pid = $TargetPid
    processName = $target.ProcessName
    processStartTimeUtc = $targetStartTimeUtc
    samplesRequested = $Samples
    intervalSeconds = $IntervalSeconds
    jcmdTimeoutSeconds = $JcmdTimeoutSeconds
    classHistogramRequested = [bool]$ClassHistogram
    jcmdPath = $jcmd
    collection = @(
        'Get-Process working set, private bytes, virtual bytes, handles, threads, and CPU time',
        'jcmd GC.heap_info for every sample',
        'jcmd VM.flags once',
        'jcmd GC.class_histogram -all once only when -ClassHistogram is supplied'
    )
    excluded = @(
        'No JVM command line is collected',
        'No heap dump, forced GC, JVM termination, or game/configuration change is requested'
    )
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runDirectory 'manifest.json') -Encoding utf8

$null = Invoke-JcmdCapture -Command 'VM.flags' -Path (Join-Path $runDirectory 'vm-flags.txt')

if ($ClassHistogram) {
    # -all includes unreachable objects and avoids requesting collection for a
    # live-only histogram. It still walks the heap and can pause the target.
    $null = Invoke-JcmdCapture -Command 'GC.class_histogram' -Arguments @('-all') `
        -Path (Join-Path $runDirectory 'class-histogram.txt')
}

$measurements = @()
for ($index = 1; $index -le $Samples; $index++) {
    $current = Get-OriginalTargetProcess

    # Process metrics are sampled immediately before jcmd. The jcmd file has
    # its own command-start timestamp; the two reads are not atomic.
    $timestamp = Get-UtcStamp
    $heapFile = Join-Path $runDirectory ('sample-{0:D2}-heap-info.txt' -f $index)
    $jcmdExitCode = Invoke-JcmdCapture -Command 'GC.heap_info' -Path $heapFile
    $null = Get-OriginalTargetProcess
    $measurement = [ordered]@{
        sample = $index
        timestampUtc = $timestamp
        workingSetBytes = [int64]$current.WorkingSet64
        privateBytes = [int64]$current.PrivateMemorySize64
        virtualBytes = [int64]$current.VirtualMemorySize64
        handleCount = [int]$current.HandleCount
        threadCount = [int]$current.Threads.Count
        cpuSeconds = [Math]::Round($current.CPU, 3)
        heapInfoFile = [System.IO.Path]::GetFileName($heapFile)
        heapInfoExitCode = $jcmdExitCode
    }
    $measurements += [pscustomobject]$measurement
    $measurement | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDirectory ('sample-{0:D2}-process.json' -f $index)) -Encoding utf8

    if ($index -lt $Samples) { Start-Sleep -Seconds $IntervalSeconds }
}

$measurements | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $runDirectory 'measurements.json') -Encoding utf8
Write-Host "Collected $Samples sample(s) in: $runDirectory"
