# jsprit cache production proof (BACKLOG "Produktionsnachweis je Maschine"). On one machine, three
# short runs of the paper Baseline (drt_baseline, fleet 120, jspritIter 100, freight on; MATSim stops
# after 2 iterations, which the cache key does not contain): 1 cache=on (expect miss), 2 cache=on
# (expect hit), 3 cache=verify (expect verified). Every run's routed carriers file must carry the
# SHA-256 of the 23 Baseline runs of 15.08.-30.09. (2c1195c3...), so all machines also agree.
# Runs from a frozen copy of the JAR, in -ModuleDir (needs input\README.md, input\lausitz, logging\).
# Status lines go to <ModuleDir>\hagrid-output\logs\cache-proof\proof_status.txt (read by polling only).
# ASCII only (Windows PowerShell 5.1 reads BOM-less scripts as cp1252).
param(
    [Parameter(Mandatory = $true)] [string] $Java,
    [Parameter(Mandatory = $true)] [string] $ModuleDir,
    [Parameter(Mandatory = $true)] [string] $JarSource,
    [string] $Xmx = '24g',
    [string] $TagPrefix = 'cproof'
)
$ErrorActionPreference = 'Stop'
$expectedSha = '2C1195C3300D411F'
$log = Join-Path $ModuleDir 'hagrid-output\logs\cache-proof'
New-Item -ItemType Directory -Force -Path $log | Out-Null
$status = Join-Path $log 'proof_status.txt'
function Mark([string] $m) {
    $line = '{0}  {1}' -f (Get-Date -Format s), $m
    for ($i = 0; $i -lt 20; $i++) {
        try { Add-Content -Path $status -Value $line -Encoding ascii -ErrorAction Stop; break }
        catch { Start-Sleep -Milliseconds 250 }
    }
    Write-Host $line
}

$jar = 'hagrid-output\logs\cache-proof\proof-hagrid-shaded.jar'   # relative to $ModuleDir
Copy-Item -Path $JarSource -Destination (Join-Path $ModuleDir $jar) -Force
Mark ('START host={0} jar_sha256={1} java={2}' -f $env:COMPUTERNAME,
      (Get-FileHash -Algorithm SHA256 -Path (Join-Path $ModuleDir $jar)).Hash, $Java)

$blocked = Join-Path $ModuleDir 'hagrid-output\shared\jsprit-cache\BLOCKED.json'
if (Test-Path $blocked) { Mark ('ABORT: the cache is blocked ({0})' -f $blocked); exit 2 }

Add-Type -Namespace Proof -Name Awake -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint f);'
[Proof.Awake]::SetThreadExecutionState([uint32]2147483649) | Out-Null   # ES_CONTINUOUS | ES_SYSTEM_REQUIRED

$jvm = "-Xms8g -Xmx$Xmx -Xss512k -XX:MaxDirectMemorySize=4g -XX:ActiveProcessorCount=12 -XX:+UseZGC " +
       '-XX:+ExitOnOutOfMemoryError --add-opens java.base/java.lang=ALL-UNNAMED ' +
       '-Dlog4j2.configurationFile=logging/log4j2_runlocal.xml -Dhagrid.pipeline.root=.'
$spec = 'concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=120,maxIter=2,' +
        'jspritIter=100,freight=true,seed=1337,kpiDashboard=false'

function RunJava([string] $tag, [string] $mainClass, [string] $cacheMode) {
    $short = $mainClass.Split('.')[-1]
    $p = Start-Process -FilePath $Java -WorkingDirectory $ModuleDir -WindowStyle Hidden -PassThru `
        -ArgumentList "$jvm -Dhagrid.jsprit.cache=$cacheMode -cp $jar $mainClass $spec,tag=$tag" `
        -RedirectStandardOutput (Join-Path $log "$tag.$short.out") `
        -RedirectStandardError (Join-Path $log "$tag.$short.err")
    $null = $p.Handle   # cache the handle, else ExitCode reads empty in PS 5.1
    $p.WaitForExit()
    return $p.ExitCode
}

$failed = $false
$runs = @(@{ N = 1; Mode = 'on'; Expect = 'miss' }, @{ N = 2; Mode = 'on'; Expect = 'hit' },
          @{ N = 3; Mode = 'verify'; Expect = 'verified' })
try {
    foreach ($r in $runs) {
        $tag = '{0}_{1}' -f $TagPrefix, $r.N
        Mark ('{0} START cache={1}' -f $tag, $r.Mode)
        $t0 = Get-Date
        $e1 = RunJava $tag 'hagrid.lausitz.drt.PrepareLausitzDrtInputs' $r.Mode
        if ($e1 -ne 0) { Mark ('{0} PREP_EXIT={1} - see {0}.*.err/out' -f $tag, $e1); $failed = $true; break }
        $e2 = RunJava $tag 'hagrid.core.simulation.HAGRIDSimulationRunner' $r.Mode
        $runId = 'DRT_BASELINE_13052025_{0}' -f $tag
        $meta = Join-Path $ModuleDir ('hagrid-matsim-output\{0}_iter2_jsprit100\run_metadata.json' -f $runId)
        $routed = Join-Path $ModuleDir ('hagrid-output\{0}\carriers\{0}_lmd_carriers_routed.xml' -f $runId)
        $cache = '?'; $key = '?'; $sha = '?'
        if (Test-Path $meta) { $m = Get-Content -Raw -Path $meta | ConvertFrom-Json; $cache = $m.jsprit_cache; $key = $m.jsprit_cache_key }
        if (Test-Path $routed) { $sha = (Get-FileHash -Algorithm SHA256 -Path $routed).Hash }
        $ok = ($e2 -eq 0) -and ($cache -eq $r.Expect) -and $sha.StartsWith($expectedSha)
        Mark ('{0} RUN_EXIT={1} jsprit_cache={2} (expected {3}) key={4} routed_sha256={5} minutes={6:N1} {7}' -f
              $tag, $e2, $cache, $r.Expect, $key, $sha, ((Get-Date) - $t0).TotalMinutes, $(if ($ok) { 'OK' } else { 'FAIL' }))
        if (-not $ok) { $failed = $true }
    }
} finally {
    [Proof.Awake]::SetThreadExecutionState([uint32]2147483648) | Out-Null
}
if (Test-Path $blocked) { Mark ('FAILED: the cache got blocked ({0})' -f $blocked); $failed = $true }
if ($failed) { Mark 'PROOF FAILED'; exit 1 } else { Mark 'PROOF PASSED'; exit 0 }
