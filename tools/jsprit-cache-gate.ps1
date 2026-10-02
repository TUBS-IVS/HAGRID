# jsprit cache gate (spec 2026-10-01 section 9.3). Runs short Baseline and 1d runs from the built
# shaded JAR: A (cache off), C (cache on, empty), B (cache on, hit), D (verify), then compares
# A with B (the cache) and A with C (control: two fresh computations) via compare_run_outputs.py.
# BOTH must be equal: if two fresh runs differ, byte-identity is not shown at all and the gate fails.
# Precondition: no entries and no BLOCKED.json in hagrid-output\shared\jsprit-cache (C must be a real miss).
# Keep the laptop on AC with the lid open: lid-close standby freezes the run.
# ASCII only (Windows PowerShell 5.1 reads BOM-less scripts as cp1252).
param(
    [string] $Java = 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe',
    [string] $Python = 'python'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$module = Join-Path $repo 'hagrid\simulation'
$jar = 'target\hagrid-1.0-SNAPSHOT-shaded.jar'
$log = Join-Path $module 'hagrid-output\logs\cache-gate'
$cacheDir = Join-Path $module 'hagrid-output\shared\jsprit-cache'
New-Item -ItemType Directory -Force -Path $log | Out-Null
$status = Join-Path $log 'gate_status.txt'
function Mark([string] $m) {
    Add-Content -Path $status -Value ('{0}  {1}' -f (Get-Date -Format s), $m) -Encoding ascii
    Write-Host $m
}

$blockedMarker = Join-Path $cacheDir 'BLOCKED.json'
if (Test-Path $cacheDir) {
    $existing = @(Get-ChildItem -Path $cacheDir -Directory | Where-Object { -not $_.Name.StartsWith('.') })
    if ($existing.Count -gt 0) {
        Mark ('ABORT: {0} cache entries exist in {1}; move them away for a clean gate' -f $existing.Count, $cacheDir)
        exit 2
    }
    if (Test-Path $blockedMarker) {
        Mark ('ABORT: the cache is blocked ({0}); understand the mismatch first' -f $blockedMarker)
        exit 2
    }
}

Add-Type -Namespace Gate -Name Awake -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint f);'
[Gate.Awake]::SetThreadExecutionState([uint32]2147483649) | Out-Null   # ES_CONTINUOUS | ES_SYSTEM_REQUIRED

$jvm = '-Xms8g -Xmx24g -Xss512k -XX:MaxDirectMemorySize=4g -XX:+UseZGC -XX:+ExitOnOutOfMemoryError ' +
       '--add-opens java.base/java.lang=ALL-UNNAMED -Dlog4j2.configurationFile=logging/log4j2_dev.xml'
$variants = @(
    @{ Short = 'b'; Concept = 'DRT_BASELINE'; JsIter = '1'; Modular = $false;
       Args = 'concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=120,maxIter=2,jspritIter=1,freight=true,seed=1337,kpiDashboard=false' },
    @{ Short = 'm'; Concept = 'DRT_MODULAR'; JsIter = '100'; Modular = $true;
       Args = 'concept=drt_modular,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=130,maxIter=2,jspritIter=100,idleThreshold=0.02,maxTourDuration=10800,budgetMode=selfref,seed=1337,kpiDashboard=false' }
)

function RunJava([string] $tag, [string] $mainClass, [string] $runArgs, [string] $cacheMode) {
    $short = $mainClass.Split('.')[-1]
    $argLine = "$jvm -Dhagrid.jsprit.cache=$cacheMode -cp $jar $mainClass $runArgs,tag=$tag"
    $p = Start-Process -FilePath $Java -WorkingDirectory $module -WindowStyle Hidden -PassThru `
        -ArgumentList $argLine `
        -RedirectStandardOutput (Join-Path $log "$tag.$short.out") `
        -RedirectStandardError (Join-Path $log "$tag.$short.err")
    $null = $p.Handle   # cache the handle, else ExitCode reads empty in PS 5.1
    $p.WaitForExit()
    return $p.ExitCode
}

function OneRun($v, [string] $letter, [string] $cacheMode) {
    $tag = 'cgate_{0}_{1}' -f $v.Short, $letter
    Mark ("{0} START cache={1}" -f $tag, $cacheMode)
    $e1 = RunJava $tag 'hagrid.lausitz.drt.PrepareLausitzDrtInputs' $v.Args $cacheMode
    if ($e1 -ne 0) { Mark ("{0} PREP_EXIT={1}" -f $tag, $e1); return $null }
    $e2 = RunJava $tag 'hagrid.core.simulation.HAGRIDSimulationRunner' $v.Args $cacheMode
    Mark ("{0} RUN_EXIT={1}" -f $tag, $e2)
    if ($e2 -ne 0) { return $null }
    $runId = '{0}_13052025_{1}' -f $v.Concept, $tag
    $dir = Join-Path $module ('hagrid-matsim-output\{0}_iter2_jsprit{1}' -f $runId, $v.JsIter)
    $metaFile = Join-Path $dir 'run_metadata.json'
    if (-not (Test-Path $metaFile)) { Mark ("{0} NO run_metadata.json in {1}" -f $tag, $dir); return $null }
    $meta = Get-Content -Raw -Path $metaFile | ConvertFrom-Json
    Mark ("{0} jsprit_cache={1}" -f $tag, $meta.jsprit_cache)
    return @{ RunId = $runId; Dir = $dir; Cache = $meta.jsprit_cache;
              Routed = Join-Path $module ('hagrid-output\{0}\carriers\{0}_lmd_carriers_routed.xml' -f $runId) }
}

# Not named "Compare": that is a built-in alias of Compare-Object, and aliases win over functions.
function CompareRuns($v, $x, $y, [string] $label) {
    $cmpArgs = @('tools\compare_run_outputs.py', '--a', $x.Dir, '--run-a', $x.RunId, '--b', $y.Dir, '--run-b', $y.RunId,
                 '--routed-a', $x.Routed, '--routed-b', $y.Routed)
    if ($v.Modular) { $cmpArgs += '--modular' }
    # no 2>&1: in PS 5.1 stderr of a native exe becomes an ErrorRecord and stops the script
    $out = & $Python @cmpArgs | Out-String
    $code = $LASTEXITCODE
    Add-Content -Path (Join-Path $log ('compare_{0}_{1}.txt' -f $v.Short, $label)) -Value $out -Encoding ascii
    Mark ("{0} {1} compare exit={2}" -f $v.Short, $label, $code)
    return $code
}

$failed = $false
Push-Location $repo
try {
    foreach ($v in $variants) {
        $a = OneRun $v 'A' 'off'
        $c = OneRun $v 'C' 'on'
        $b = OneRun $v 'B' 'on'
        $d = OneRun $v 'D' 'verify'
        if ($null -eq $a -or $null -eq $b -or $null -eq $c -or $null -eq $d) { Mark ("{0} FAILED: a run did not finish" -f $v.Short); $failed = $true; continue }
        $expect = @{ A = 'off'; C = 'miss'; B = 'hit'; D = 'verified' }
        foreach ($k in $expect.Keys) {
            $actual = (Get-Variable -Name $k.ToLower() -ValueOnly).Cache
            if ($actual -ne $expect[$k]) { Mark ("{0} FAILED: run {1} jsprit_cache={2}, expected {3}" -f $v.Short, $k, $actual, $expect[$k]); $failed = $true }
        }
        $control = CompareRuns $v $a $c 'A_vs_C'
        $gate = CompareRuns $v $a $b 'A_vs_B'
        if ($control -ne 0) { Mark ("{0} FAILED: A vs C differ - two fresh runs are not byte-identical, so A vs B proves nothing" -f $v.Short); $failed = $true }
        if ($gate -ne 0) { Mark ("{0} FAILED: A vs B differ" -f $v.Short); $failed = $true }
    }
} finally {
    Pop-Location
    [Gate.Awake]::SetThreadExecutionState([uint32]2147483648) | Out-Null
}
if (Test-Path $blockedMarker) { Mark ('FAILED: the cache got blocked during the gate ({0})' -f $blockedMarker); $failed = $true }
if ($failed) { Mark 'GATE FAILED'; exit 1 } else { Mark 'GATE PASSED'; exit 0 }
