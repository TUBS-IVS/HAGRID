<#
    Queue: 1c chi=600 rerun WITH the detour instrumentation, on the Dev-PC.

    Waits for the running Hannover sweep (290v2) to finish, then rebuilds and runs the
    Shared-Use arm so that <runId>.shareduse_detour_min.csv is produced -- the measurement
    METHODS-LOG 2.31 needs and that the retracted chi counters could not deliver.

    WHY A LISTENER AND NOT A PLAIN CHAIN
    The shaded jar is held open by the running 290v2 JVM (-jar target\...-shaded.jar), so a
    rebuild NOW would fail on a Windows file lock (the open [M] backlog item). The build
    therefore has to happen after the wait, inside this script -- not before it.

    RECIPE PROVENANCE
    Args are the ones that produced chid600w21 (run_nightbc.bat STEP2A/2B, cross-checked
    against that run's run_metadata.json: fleet 120, iter 150, jsprit 100, freight=false,
    chi=600, seed 1337). Only the tag differs, so nothing is overwritten -- same discipline as
    bandz_* vs band_*. Demand: the Dev-PC carries the current PANDA state (level_central,
    SHA256 fdac2435...), which is also what chid600w21 ran, so the two are directly paired.

    GUARDS (a marker file alone cannot see a launch that never started)
      * liveness: if the watched PIDs are already gone, proceed but log it as such
      * timeout: give up with a distinct marker instead of waiting forever
      * file lock: one retry after 5 min if the build trips over a lingering JVM
        (the LMD/jsprit JVM is known to linger after exit 0)
      * smoke gate: a maxIter=1 run must actually produce shareduse_detour_min.csv before the
        ~7 h run starts. This is the only check that can catch a broken instrumentation, and
        it costs minutes instead of hours.

    Usage
      .\queue_chi_detour_rerun.ps1                     # wait for the default PIDs, then run
      .\queue_chi_detour_rerun.ps1 -DryRun             # print the plan, touch nothing
      .\queue_chi_detour_rerun.ps1 -SkipWait           # start immediately (machine already free)
#>
[CmdletBinding()]
param(
    # PID 30784 = cmd.exe running run_stepB_v2dev_batch.bat (the whole sweep queue, aborted
    # after 290v2 -> its exit means the queue is done). PID 1568 = the 290v2 JVM itself.
    [int[]] $WaitForPid = @(30784, 1568),
    [string] $Tag = 'chid600det',
    [string] $SmokeTag = 'chidetsmoke',
    [int] $PollSeconds = 60,
    [int] $MaxWaitHours = 72,
    [switch] $SkipWait,
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'
$repo = 'C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID'
$module = Join-Path $repo 'parcel-demand-2-matsim-pipeline'
$logDir = Join-Path $module 'hagrid-output\logs'
$log = Join-Path $logDir 'chi_detour_rerun.log'
$outRoot = Join-Path $module 'hagrid-matsim-output'

$commonArgs = 'concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,' +
              'fleetSize=120,freight=false,chiThreshold=600'
$prepareClass = 'hagrid.integrated.drt.PrepareLausitzDrtInputs'
$runClass = 'hagrid.HAGRIDSimulationRunner'

# Write-Host, NOT Write-Output: a log line on the success stream becomes part of the enclosing
# function's return value, so `$code = Invoke-Maven ...` would receive the log text instead of
# the exit code and every step would read as failed. (Found by the -DryRun pass.)
function Write-Log {
    param([string] $Message)
    $line = '{0}  {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Write-Host $line
    if (-not $DryRun) {
        if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
        Add-Content -Path $log -Value $line -Encoding utf8
    }
}

function Set-Marker {
    param([string] $Name)
    Write-Log ("MARKER {0}" -f $Name)
}

function Test-AnyAlive {
    param([int[]] $Ids)
    foreach ($id in $Ids) {
        try {
            $p = Get-Process -Id $id -ErrorAction Stop
            if ($p) { return $true }
        } catch {
            # gone - keep checking the rest
        }
    }
    return $false
}

# Invokes one maven goal. Returns the exit code only; never throws, so the caller decides.
#
# Runs it through cmd.exe with the streams redirected to files rather than piping `mvn 2>&1`
# into PowerShell: under Windows PowerShell 5.1 a redirected native stderr line arrives as an
# ErrorRecord, which with $ErrorActionPreference='Stop' turns maven's ordinary warnings into a
# terminating error and kills the queue. stdout and stderr need separate files (Start-Process
# refuses the same path for both), they are concatenated into the log afterwards.
# All exec args are comma-separated and space-free, so no cmd quoting is needed.
function Invoke-Maven {
    param([string] $Label, [string[]] $MvnArgs)
    Write-Log ("STEP {0}: mvn {1}" -f $Label, ($MvnArgs -join ' '))
    if ($DryRun) { Write-Log ("DRYRUN {0} skipped" -f $Label); return 0 }
    $stdout = Join-Path $env:TEMP ("mvn_{0}_out.log" -f $Label)
    $stderr = Join-Path $env:TEMP ("mvn_{0}_err.log" -f $Label)
    $proc = Start-Process -FilePath 'cmd.exe' `
        -ArgumentList @('/c', ('mvn ' + ($MvnArgs -join ' '))) `
        -WorkingDirectory $repo -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $code = $proc.ExitCode
    foreach ($f in @($stdout, $stderr)) {
        if (Test-Path $f) {
            Get-Content $f | Add-Content -Path $log -Encoding utf8
            Remove-Item $f -Force -ErrorAction SilentlyContinue
        }
    }
    Write-Log ("STEP {0} EXIT={1}" -f $Label, $code)
    return $code
}

function Invoke-Arm {
    param([string] $Label, [string] $ArgLine)
    $code = Invoke-Maven -Label ($Label + '_PREPARE') -MvnArgs @(
        '-pl', 'parcel-demand-2-matsim-pipeline', 'exec:java',
        "-Dexec.mainClass=$prepareClass", "-Dexec.args=$ArgLine")
    if ($code -ne 0) { return $code }
    return Invoke-Maven -Label ($Label + '_RUN') -MvnArgs @(
        '-pl', 'parcel-demand-2-matsim-pipeline', 'exec:java',
        "-Dexec.mainClass=$runClass", "-Dexec.args=$ArgLine")
}

# The acceptance check the whole queue exists for: did the instrumentation write its file?
function Find-DetourCsv {
    param([string] $TagName)
    $dirs = Get-ChildItem $outRoot -Directory -Filter ("DRT_SHAREDUSE_*_" + $TagName + "_*") -ErrorAction SilentlyContinue
    foreach ($d in $dirs) {
        $hit = Get-ChildItem $d.FullName -Filter '*.shareduse_detour_min.csv' -ErrorAction SilentlyContinue
        if ($hit) { return $hit[0].FullName }
    }
    return $null
}

# --------------------------------------------------------------------------------- run

$env:MAVEN_OPTS = '-Xmx16g -Xms4g -Dhagrid.log.dir=hagrid-output/logs --add-opens java.base/java.lang=ALL-UNNAMED'

# Pin the toolchain explicitly. Measured on this machine: Maven sits ONLY in the USER PATH and
# JAVA_HOME is a USER variable, while a WMI-created (detached) process inherits the MACHINE
# environment -- so an unpinned queue would die on "mvn is not recognized" hours from now, with
# a marker file that says nothing. JAVA_HOME must stay 21.0.10: `java` on the session PATH is a
# JDK 25 (Adoptium), the running 290v2 and every prior Lausitz run used 21.
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$mavenBin = 'C:\maven\wrapper\dists\apache-maven-3.9.8\8fbe43aa8cab8504a064f61871f5c160\bin'
if ($env:Path -notlike ('*' + $mavenBin + '*')) { $env:Path = $env:Path + ';' + $mavenBin }

Set-Marker 'CHI_DETOUR_QUEUE_STARTED'

# Preflight: assert the tools resolve BEFORE the wait, so a broken environment fails now
# instead of silently after 290v2 finishes (a marker alone cannot tell the two apart).
foreach ($tool in @('mvn', 'java', 'python')) {
    $found = Get-Command $tool -ErrorAction SilentlyContinue
    if (-not $found) {
        Set-Marker ('CHI_DETOUR_ABORTED_NO_' + $tool.ToUpper())
        Write-Log ("preflight: {0} not resolvable in this process - fix the pinning above" -f $tool)
        exit 1
    }
    Write-Log ("preflight OK: {0} -> {1}" -f $tool, $found.Source)
}
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    Set-Marker 'CHI_DETOUR_ABORTED_NO_JAVA_HOME'
    Write-Log ("preflight: JAVA_HOME does not contain bin\java.exe ({0})" -f $env:JAVA_HOME)
    exit 1
}
Write-Log ("preflight OK: JAVA_HOME -> {0}" -f $env:JAVA_HOME)
Write-Log ("watching PIDs {0}; tag={1}; smokeTag={2}" -f ($WaitForPid -join ','), $Tag, $SmokeTag)

if ($SkipWait) {
    Write-Log 'SkipWait set - not waiting for anything'
} elseif (-not (Test-AnyAlive -Ids $WaitForPid)) {
    # Liveness branch: "already gone" is a legitimate state, but it must be visible in the log,
    # because it is indistinguishable from "never ran" if nobody writes it down.
    Write-Log 'LIVENESS: none of the watched PIDs is alive at start - proceeding immediately'
} else {
    $deadline = (Get-Date).AddHours($MaxWaitHours)
    Write-Log ("waiting for 290v2 to finish (deadline {0})" -f $deadline.ToString('yyyy-MM-dd HH:mm'))
    if ($DryRun) {
        Write-Log 'DRYRUN: watched PIDs are alive, the real run would block here until they exit'
    } else {
        while (Test-AnyAlive -Ids $WaitForPid) {
            if ((Get-Date) -gt $deadline) {
                Set-Marker 'CHI_DETOUR_ABORTED_WAIT_TIMEOUT'
                Write-Log 'the watched PIDs outlived the deadline - not starting anything'
                exit 2
            }
            Start-Sleep -Seconds $PollSeconds
        }
        Write-Log 'the watched PIDs are gone - the sweep queue is done'
    }
}

# Build. The jar was locked while 290v2 held it; a lingering JVM (known after jsprit, exit 0)
# can still hold it for a while, hence one retry rather than an immediate abort.
$code = Invoke-Maven -Label 'BUILD' -MvnArgs @('install', '-DskipTests')
if ($code -ne 0) {
    Write-Log 'build failed - waiting 300 s in case a JVM still holds the shaded jar, then one retry'
    if (-not $DryRun) { Start-Sleep -Seconds 300 }
    $code = Invoke-Maven -Label 'BUILD_RETRY' -MvnArgs @('install', '-DskipTests')
}
if ($code -ne 0) {
    Set-Marker 'CHI_DETOUR_ABORTED_BUILD'
    exit 3
}
Set-Marker 'CHI_DETOUR_BUILD_OK'

# Smoke: 2 MATSim iterations, minutes not hours. Its only job is to prove the new CSV appears
# before the real run commits ~7 h.
$smokeArgs = $commonArgs + ',maxIter=1,tag=' + $SmokeTag
$code = Invoke-Arm -Label 'SMOKE' -ArgLine $smokeArgs
if ($code -ne 0) {
    Set-Marker 'CHI_DETOUR_ABORTED_SMOKE_EXIT'
    exit 4
}
$csv = if ($DryRun) { 'dryrun' } else { Find-DetourCsv -TagName $SmokeTag }
if (-not $csv) {
    Set-Marker 'CHI_DETOUR_ABORTED_SMOKE_NO_CSV'
    Write-Log 'the smoke run produced no shareduse_detour_min.csv - the instrumentation did not fire, NOT starting the long run'
    exit 5
}
Write-Log ("SMOKE produced {0}" -f $csv)
Set-Marker 'CHI_DETOUR_SMOKE_OK'

# The real thing.
$runArgs = $commonArgs + ',maxIter=150,jspritIter=100,tag=' + $Tag
$code = Invoke-Arm -Label 'RUN' -ArgLine $runArgs
if ($code -ne 0) {
    Set-Marker 'CHI_DETOUR_ABORTED_RUN_EXIT'
    exit 6
}
$csv = if ($DryRun) { 'dryrun' } else { Find-DetourCsv -TagName $Tag }
if (-not $csv) {
    Set-Marker 'CHI_DETOUR_RUN_DONE_BUT_NO_CSV'
    Write-Log 'run finished with exit 0 but wrote no detour CSV - investigate before drawing anything'
    exit 7
}
Write-Log ("RUN produced {0}" -f $csv)

# KPI pipeline, so the distribution rows land in kpi_distributions.csv without a second pass.
$runDir = if ($DryRun) { '<dryrun>' } else { (Get-Item $csv).Directory.FullName }
Write-Log ("STEP KPIS: build_kpis.py --run-dir {0}" -f $runDir)
if (-not $DryRun) {
    Push-Location (Join-Path $module 'analysis\kpi')
    try {
        & python -u build_kpis.py --run-dir $runDir 2>&1 | Add-Content -Path $log -Encoding utf8
        Write-Log ("STEP KPIS EXIT={0}" -f $LASTEXITCODE)
    } finally {
        Pop-Location
    }
}

Set-Marker 'CHI_DETOUR_RUN_DONE'
Write-Log 'queue complete'
exit 0
